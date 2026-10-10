package com.ngocsi.music

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

internal data class NgocSiAiMessage(
    val role: String,
    val content: String
)

internal data class NgocSiAiReply(
    val answer: String,
    val remainingToday: Int?,
    val remainingGlobalToday: Int?
)

/**
 * Optional online client for the isolated NGỌC SĨ AI integration branch.
 *
 * Firebase client configuration is provided through build-time environment values.
 * When values are absent (the default CI configuration), this class stays offline and
 * the tested local-preview experience continues to work. The Gemini key never belongs
 * in this Android client; it is held by Firebase Functions Secret Manager.
 */
internal class NgocSiAiRemoteClient(context: Context) {
    private val firebaseApp: FirebaseApp? = createFirebaseApp(context.applicationContext)

    val isConfigured: Boolean
        get() = firebaseApp != null

    suspend fun send(messages: List<NgocSiAiMessage>): NgocSiAiReply {
        val app = firebaseApp
            ?: throw NgocSiAiRemoteException("Firebase chưa được cấu hình cho bản thử này.")

        val history = normalizeHistory(messages)
        val auth = FirebaseAuth.getInstance(app)
        try {
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        } catch (_: Exception) {
            throw NgocSiAiRemoteException(
                "Không thể xác thực tài khoản thử nghiệm. Hãy kiểm tra Anonymous Authentication trong Firebase."
            )
        }

        val callable = FirebaseFunctions.getInstance(app, "asia-southeast1")
            .getHttpsCallable("ngocSiAiChat")
        val request = mapOf(
            "messages" to history.map { message ->
                mapOf("role" to message.role, "content" to message.content)
            }
        )

        val result = try {
            callable.call(request).await()
        } catch (error: FirebaseFunctionsException) {
            val safeMessage = when (error.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Phiên xác thực chưa hợp lệ. Hãy thử gửi lại."
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "Ứng dụng chưa được App Check xác minh. Cần kiểm tra cấu hình bản thử."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    error.message?.takeIf { it.contains("lượt") || it.contains("hạn mức") }
                        ?: "Đã chạm giới hạn sử dụng AI. Vui lòng thử lại sau."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    "Máy chủ AI chưa được cấu hình đầy đủ."
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    "Tin nhắn vượt giới hạn cho phép. Hãy rút gọn câu hỏi."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Máy chủ AI hoặc kết nối mạng tạm thời không sẵn sàng."
                else ->
                    "Không thể hoàn tất yêu cầu AI. Hãy kiểm tra cấu hình rồi thử lại."
            }
            throw NgocSiAiRemoteException(safeMessage)
        } catch (_: Exception) {
            throw NgocSiAiRemoteException("Không kết nối được máy chủ AI. Hãy kiểm tra Internet.")
        }

        val response = result.data as? Map<*, *>
            ?: throw NgocSiAiRemoteException("Máy chủ trả về dữ liệu không hợp lệ.")
        val answer = response["answer"] as? String
        if (answer.isNullOrBlank()) {
            throw NgocSiAiRemoteException("Máy chủ chưa trả lời được. Vui lòng thử lại.")
        }
        return NgocSiAiReply(
            answer = answer,
            remainingToday = (response["remainingToday"] as? Number)?.toInt(),
            remainingGlobalToday = (response["remainingGlobalToday"] as? Number)?.toInt()
        )
    }

    private fun normalizeHistory(messages: List<NgocSiAiMessage>): List<NgocSiAiMessage> {
        var history = messages.takeLast(MAX_HISTORY_MESSAGES)
        if (history.firstOrNull()?.role == "assistant") {
            history = history.drop(1)
        }
        if (history.isEmpty() || history.last().role != "user") {
            throw NgocSiAiRemoteException("Tin nhắn chưa hợp lệ. Hãy gửi một câu hỏi mới.")
        }
        var totalChars = 0
        history.forEach { message ->
            if (message.role != "user" && message.role != "assistant") {
                throw NgocSiAiRemoteException("Loại tin nhắn không được hỗ trợ.")
            }
            val length = message.content.trim().length
            if (length !in 1..MAX_MESSAGE_CHARS) {
                throw NgocSiAiRemoteException("Mỗi tin nhắn phải có từ 1 đến 2.000 ký tự.")
            }
            totalChars += length
        }
        if (totalChars > MAX_TOTAL_CHARS) {
            throw NgocSiAiRemoteException("Lịch sử trò chuyện quá dài. Hãy bắt đầu câu hỏi ngắn hơn.")
        }
        return history.map { it.copy(content = it.content.trim()) }
    }

    private fun createFirebaseApp(context: Context): FirebaseApp? {
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val senderId = BuildConfig.FIREBASE_SENDER_ID
        val appId = BuildConfig.FIREBASE_APP_ID
        if (apiKey.isBlank() || projectId.isBlank() || senderId.isBlank() || appId.isBlank()) {
            return null
        }

        return runCatching {
            val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FIREBASE_APP_NAME }
            val app = existing ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setProjectId(projectId)
                    .setGcmSenderId(senderId)
                    .setApplicationId(appId)
                    .build(),
                FIREBASE_APP_NAME
            )
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            app
        }.getOrNull()
    }

    companion object {
        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val MAX_HISTORY_MESSAGES = 8
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_TOTAL_CHARS = 8_000
    }
}

internal class NgocSiAiRemoteException(message: String) : Exception(message)

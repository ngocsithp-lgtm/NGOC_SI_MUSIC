package com.ngocsi.music

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import java.util.Locale

internal data class NgocSiAiMessage(
    val role: String,
    val content: String
)

internal data class NgocSiAiReply(
    val answer: String,
    val remainingToday: Int? = null,
    val remainingGlobalToday: Int? = null
)

/**
 * Optional Firebase AI Logic client for the isolated NGỌC SĨ AI preview.
 *
 * Firebase client settings are provided through build-time environment values.
 * Missing values keep this preview offline. Gemini is accessed through Firebase AI
 * Logic's Google AI backend; no Gemini API key, Firebase callable, or custom server
 * is required by this Android client.
 */
internal class NgocSiAiRemoteClient(context: Context) {
    private val firebaseApp: FirebaseApp? = createFirebaseApp(context.applicationContext)

    val isConfigured: Boolean
        get() = firebaseApp != null

    suspend fun send(messages: List<NgocSiAiMessage>): NgocSiAiReply {
        val app = firebaseApp
            ?: throw NgocSiAiRemoteException("Firebase AI Logic chưa được cấu hình cho bản thử này.")

        val normalized = try {
            normalizeNgocSiAiHistory(messages)
        } catch (error: IllegalArgumentException) {
            throw NgocSiAiRemoteException(
                error.message ?: "Lịch sử trò chuyện không hợp lệ. Hãy thử một câu hỏi ngắn hơn."
            )
        }

        val newestUserMessage = normalized.lastOrNull()
            ?.takeIf { it.role == "user" }
            ?: throw NgocSiAiRemoteException("Tin nhắn chưa hợp lệ. Hãy gửi một câu hỏi mới.")

        // Firebase AI Logic requires model-role history for assistant responses.
        val previousTurns = normalized.dropLast(1).map { message ->
            content(role = if (message.role == "assistant") "model" else "user") {
                text(message.content)
            }
        }

        return try {
            val model = FirebaseAI.getInstance(app, GenerativeBackend.googleAI())
                .generativeModel(
                    modelName = MODEL_NAME,
                    systemInstruction = content {
                        text(SYSTEM_INSTRUCTIONS)
                    }
                )
            val chat = model.startChat(history = previousTurns)
            val response = chat.sendMessage(newestUserMessage.content)
            val answer = response.text?.trim()
            if (answer.isNullOrBlank()) {
                throw NgocSiAiRemoteException("Firebase AI Logic chưa trả về câu trả lời. Vui lòng thử lại.")
            }
            NgocSiAiReply(answer = answer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NgocSiAiRemoteException) {
            throw error
        } catch (error: Exception) {
            throw NgocSiAiRemoteException(toSafeMessage(error))
        }
    }

    private fun createFirebaseApp(context: Context): FirebaseApp? {
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val senderId = BuildConfig.FIREBASE_SENDER_ID
        val appId = BuildConfig.FIREBASE_APP_ID
        if (apiKey.isBlank() || projectId.isBlank() || senderId.isBlank() || appId.isBlank()) {
            return null
        }
        // Fail closed if Actions secrets accidentally point to a different Firebase project.
        if (projectId != EXPECTED_FIREBASE_PROJECT_ID) return null

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
            // Install App Check before creating or using the Firebase AI Logic client.
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            app
        }.getOrNull()
    }

    private fun toSafeMessage(error: Exception): String {
        val details = generateSequence<Throwable>(error) { it.cause }
            .take(6)
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase(Locale.ROOT)

        return when {
            "429" in details || "resource_exhausted" in details ||
                "quota" in details || "rate limit" in details ->
                "NGỌC SĨ AI đã chạm hạn mức tạm thời. Vui lòng đợi rồi thử lại."
            "app check" in details || "attestation" in details ||
                "permission denied" in details || "403" in details ->
                "Firebase AI Logic từ chối kết nối. Hãy kiểm tra App Check, SHA-256 và cấu hình ứng dụng thử."
            "api key" in details || "api has not been used" in details ||
                "service disabled" in details || "not enabled" in details ->
                "Firebase AI Logic chưa được bật đúng trong dự án Firebase. Kiểm tra AI Services → AI Logic."
            "unknownhost" in details || "unable to resolve host" in details ||
                "network" in details || "timeout" in details || "timed out" in details ->
                "Không kết nối được mạng. Hãy kiểm tra Internet rồi thử lại."
            else ->
                "Firebase AI Logic chưa phản hồi thành công. Hãy kiểm tra cấu hình Firebase/App Check rồi thử lại."
        }
    }

    companion object {
        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val EXPECTED_FIREBASE_PROJECT_ID = "ngoc-si-music-ai"
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, chính xác và dễ hiểu. " +
            "Trả lời trực tiếp; nếu không chắc, hãy nói rõ giới hạn và không bịa dữ kiện. " +
            "Không được tuyên bố đã phát nhạc, thay đổi hàng đợi, mở ứng dụng hoặc thực hiện hành động " +
            "trên điện thoại nếu chưa có xác nhận từ công cụ trên thiết bị. " +
            "Các lệnh nhạc của ứng dụng được kiểm tra và thực thi riêng trên thiết bị."
    }
}

internal class NgocSiAiRemoteException(message: String) : Exception(message)

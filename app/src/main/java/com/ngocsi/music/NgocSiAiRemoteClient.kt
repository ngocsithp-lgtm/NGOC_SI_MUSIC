package com.ngocsi.music

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
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
                    generationConfig = RESPONSE_GENERATION_CONFIG,
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
            val appCheck = FirebaseAppCheck.getInstance(app)
            // This preview APK is installed directly from GitHub Actions, not Google Play.
            // Use the Firebase App Check debug provider in debug builds; register its token
            // in Firebase Console. Keep Play Integrity for non-debug variants only.
            val providerFactory = if (BuildConfig.DEBUG) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            appCheck.installAppCheckProviderFactory(providerFactory)
            app
        }.getOrNull()
    }

    private fun toSafeMessage(error: Exception): String {
        // Keep diagnostics useful without exposing API keys, tokens, URLs, or raw server text.
        val details = generateSequence<Throwable>(error) { it.cause }
            .take(6)
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase(Locale.ROOT)
        val errorCode = Regex("\\b(400|401|403|404|429|500|502|503)\\b")
            .find(details)
            ?.value

        val quotaError = "429" in details || "resource_exhausted" in details ||
            "quota" in details || "rate limit" in details
        val dailyQuota = listOf(
            "perday", "per_day", "requests per day", "tokens per day",
            "requestsperday", "tokensperday"
        ).any { it in details }
        val minuteQuota = listOf(
            "perminute", "per_minute", "requests per minute", "tokens per minute",
            "requestsperminute", "tokensperminute"
        ).any { it in details }
        val paidTierRequired = listOf(
            "paid tier", "billing account required", "billing must be enabled",
            "not available on free tier", "upgrade your plan"
        ).any { it in details }
        val freeTierQuota = listOf(
            "generate_content_free_tier", "free_tier", "free tier quota",
            "free-tier quota", "limit: 0"
        ).any { it in details }

        return when {
            quotaError && dailyQuota ->
                "Hạn mức AI trong ngày đã hết. Mã chẩn đoán: AI_QUOTA_429_DAILY. Không cần gửi lại liên tục; hãy chờ hạn mức được làm mới."
            quotaError && minuteQuota ->
                "Bạn đã chạm giới hạn yêu cầu trong thời gian ngắn. Mã chẩn đoán: AI_QUOTA_429_RATE. Hãy chờ trước khi hỏi tiếp."
            quotaError && paidTierRequired ->
                "Mô hình hoặc tính năng này yêu cầu gói trả phí. Mã chẩn đoán: AI_QUOTA_429_PAID_TIER. Bản Preview vẫn giữ chế độ không thanh toán."
            quotaError && freeTierQuota ->
                "Hạn mức miễn phí của mô hình Gemini có thể đã hết. Mã chẩn đoán: AI_QUOTA_429_FREE_TIER. Không gửi lại liên tục; ứng dụng vẫn giữ chế độ không thanh toán."
            quotaError ->
                "Hạn mức AI có thể đã hết hoặc mô hình đang quá tải. Mã chẩn đoán: AI_QUOTA_429. Tránh gửi lặp; hãy thử lại sau."
            "app check" in details || "attestation" in details ->
                "App Check chưa xác minh được ứng dụng thử. Mã chẩn đoán: APP_CHECK_TOKEN. Cần kiểm tra debug token của đúng APK."
            errorCode == "403" || "permission denied" in details ->
                "Firebase từ chối quyền truy cập. Mã chẩn đoán: ACCESS_DENIED_403. Cần kiểm tra App Check và quyền dịch vụ."
            errorCode == "401" || "unauthenticated" in details ->
                "Firebase không xác thực được yêu cầu. Mã chẩn đoán: AUTH_401. Cần kiểm tra cấu hình Firebase của bản thử."
            errorCode == "404" || "model not found" in details || "not found" in details ->
                "Không tìm thấy model hoặc endpoint AI. Mã chẩn đoán: MODEL_OR_ENDPOINT_404."
            "api key" in details || "api has not been used" in details ||
                "service disabled" in details || "not enabled" in details ->
                "Firebase AI Logic chưa được bật hoặc cấu hình đúng. Mã chẩn đoán: FIREBASE_CONFIG."
            "unknownhost" in details || "unable to resolve host" in details ||
                "network" in details || "timeout" in details || "timed out" in details ->
                "Không kết nối được mạng. Mã chẩn đoán: NETWORK. Hãy kiểm tra Internet rồi thử lại."
            else -> {
                val exceptionType = error.javaClass.simpleName
                    .filter { it.isLetterOrDigit() }
                    .take(24)
                    .ifBlank { "UnknownException" }
                "Firebase AI Logic chưa phản hồi thành công. Mã chẩn đoán: REMOTE_ERROR_" +
                    exceptionType + (errorCode?.let { "_$it" } ?: "") + ". Hãy gửi lại đúng mã này để kiểm tra tiếp."
            }
        }
    }

    companion object {
        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val EXPECTED_FIREBASE_PROJECT_ID = "ngoc-si-music-ai"
        // Flash-Lite is the documented Gemini Developer API model for no-cost Firebase use.
        // Bound output and thinking so routine chat consumes fewer tokens per response.
        private const val MODEL_NAME = "gemini-3.5-flash-lite"
        private const val MAX_OUTPUT_TOKENS = 512
        private val RESPONSE_GENERATION_CONFIG = generationConfig {
            maxOutputTokens = MAX_OUTPUT_TOKENS
            thinkingConfig = thinkingConfig {
                thinkingLevel = ThinkingLevel.MINIMAL
            }
        }
        private const val SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, chính xác và dễ hiểu. " +
            "Trả lời trực tiếp; nếu không chắc, hãy nói rõ giới hạn và không bịa dữ kiện. " +
            "Trả lời gọn, thường trong 3–6 ý và khoảng 150–250 từ, trừ khi người dùng yêu cầu chi tiết. " +
            "Không được tuyên bố đã phát nhạc, thay đổi hàng đợi, mở ứng dụng hoặc thực hiện hành động " +
            "trên điện thoại nếu chưa có xác nhận từ công cụ trên thiết bị. " +
            "Các lệnh nhạc của ứng dụng được kiểm tra và thực thi riêng trên thiết bị."
    }
}

internal class NgocSiAiRemoteException(message: String) : Exception(message)

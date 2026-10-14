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
    val remainingGlobalToday: Int? = null,
    val usedFallbackModel: Boolean = false
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
    private val appContext = context.applicationContext
    private val quotaPreferences = appContext.getSharedPreferences(QUOTA_PREFERENCES, Context.MODE_PRIVATE)
    private val firebaseApp: FirebaseApp? = createFirebaseApp(appContext)

    val isConfigured: Boolean
        get() = firebaseApp != null

    suspend fun send(messages: List<NgocSiAiMessage>): NgocSiAiReply {
        val app = firebaseApp
            ?: throw NgocSiAiRemoteException("Firebase AI Logic chưa được cấu hình cho bản thử này.")

        activeQuotaCooldownMessage()?.let { throw NgocSiAiRemoteException(it) }

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
            // Use only the explicitly configured free-tier primary model. Never silently
            // switch to a potentially billable model when quota is reached.
            val firebaseAi = FirebaseAI.getInstance(
                app,
                GenerativeBackend.googleAI(),
                /* useLimitedUseAppCheckTokens = */ true
            )
            val response = firebaseAi.generativeModel(
                modelName = MODEL_NAME,
                generationConfig = RESPONSE_GENERATION_CONFIG,
                systemInstruction = content { text(SYSTEM_INSTRUCTIONS) }
            ).startChat(history = previousTurns).sendMessage(newestUserMessage.content)

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
            val safeMessage = toSafeMessage(error)
            persistQuotaCooldownIfNeeded(safeMessage)
            throw NgocSiAiRemoteException(safeMessage)
        }
    }


    private fun activeQuotaCooldownMessage(): String? {
        val until = quotaPreferences.getLong(KEY_QUOTA_COOLDOWN_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (until <= now) {
            quotaPreferences.edit()
                .remove(KEY_QUOTA_COOLDOWN_UNTIL)
                .remove(KEY_QUOTA_COOLDOWN_KIND)
                .apply()
            return null
        }
        val kind = quotaPreferences.getString(KEY_QUOTA_COOLDOWN_KIND, null)
            ?.let { runCatching { NgocSiAiQuotaCooldownKind.valueOf(it) }.getOrNull() }
            ?: NgocSiAiQuotaCooldownKind.TEMPORARY
        return ngocSiAiQuotaCooldownNotice(kind, until - now)
    }

    private fun persistQuotaCooldownIfNeeded(message: String) {
        val kind = classifyNgocSiAiQuotaCooldown(message) ?: return
        val until = ngocSiAiQuotaCooldownUntilMillis(kind, System.currentTimeMillis())
        quotaPreferences.edit()
            .putLong(KEY_QUOTA_COOLDOWN_UNTIL, until)
            .putString(KEY_QUOTA_COOLDOWN_KIND, kind.name)
            .apply()
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

        return synchronized(FIREBASE_APP_INITIALIZATION_LOCK) {
            runCatching {
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
                // The chat screen and hands-free service can create clients concurrently.
                // Serialize named-app creation so only one request can initialize it.
                if (!appCheckProviderInstalled) {
                    // This preview APK is installed directly from GitHub Actions, not Google Play.
                    // Use the Firebase App Check debug provider in debug builds; register its token
                    // in Firebase Console. Keep Play Integrity for non-debug variants only.
                    val providerFactory = if (BuildConfig.DEBUG) {
                        DebugAppCheckProviderFactory.getInstance()
                    } else {
                        PlayIntegrityAppCheckProviderFactory.getInstance()
                    }
                    FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(providerFactory)
                    appCheckProviderInstalled = true
                }
                app
            }.getOrNull()
        }
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

        val quotaDiagnosticCode = ngocSiAiQuotaDiagnosticCode(details)

        return when {
            quotaDiagnosticCode == "AI_FREE_TIER_PROJECT_RESTRICTED" ->
                "Google đã hạn chế quyền truy cập Gemini Developer API Free Tier của dự án sau bước xác minh tài khoản. " +
                    "Đổi model hoặc gửi lại liên tục không khắc phục được. Mã chẩn đoán: AI_FREE_TIER_PROJECT_RESTRICTED. " +
                    "Ứng dụng không bật thanh toán; lệnh cục bộ vẫn hoạt động."
            quotaDiagnosticCode == "AI_QUOTA_429_DAILY" ->
                "Hạn mức AI trong ngày đã hết. Mã chẩn đoán: AI_QUOTA_429_DAILY. Không cần gửi lại liên tục; hãy chờ hạn mức được làm mới."
            quotaDiagnosticCode == "AI_QUOTA_429_RATE" ->
                "Bạn đã chạm giới hạn yêu cầu trong thời gian ngắn. Mã chẩn đoán: AI_QUOTA_429_RATE. Hãy chờ trước khi hỏi tiếp."
            quotaDiagnosticCode == "AI_QUOTA_429_PAID_TIER" ->
                "Mô hình hoặc tính năng này yêu cầu gói trả phí. Mã chẩn đoán: AI_QUOTA_429_PAID_TIER. Bản Preview vẫn giữ chế độ không thanh toán."
            quotaDiagnosticCode == "AI_QUOTA_429_FREE_TIER" ->
                "Hạn mức miễn phí của mô hình Gemini có thể đã hết. Mã chẩn đoán: AI_QUOTA_429_FREE_TIER. Không gửi lại liên tục; ứng dụng vẫn giữ chế độ không thanh toán."
            quotaDiagnosticCode == "AI_QUOTA_429_PROJECT_OR_CAPACITY" ->
                "Google trả về lỗi 429 hạn mức chung, nhưng không cho biết rõ là giới hạn theo phút, theo ngày hay quyền hạn mức của dự án. Mã chẩn đoán: AI_QUOTA_429_PROJECT_OR_CAPACITY. Kiểm tra Usage/Rate limits của đúng dự án Gemini trong AI Studio; không cần bật thanh toán để xem thông tin."
            quotaDiagnosticCode == "AI_QUOTA_429" ->
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
        private val FIREBASE_APP_INITIALIZATION_LOCK = Any()
        private var appCheckProviderInstalled = false

        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val QUOTA_PREFERENCES = "ngoc_si_ai_quota_cooldown"
        private const val KEY_QUOTA_COOLDOWN_UNTIL = "cooldown_until_millis"
        private const val KEY_QUOTA_COOLDOWN_KIND = "cooldown_kind"
        private const val EXPECTED_FIREBASE_PROJECT_ID = "ngoc-si-music-ai"
        // Keep the free-tier Flash-Lite model as the only remote model.
        // Quota errors trigger a cooldown; there is no automatic paid-model fallback.
        private const val MODEL_NAME = "gemini-3.5-flash-lite"
        private const val MAX_OUTPUT_TOKENS = 256
        private val RESPONSE_GENERATION_CONFIG = generationConfig {
            maxOutputTokens = MAX_OUTPUT_TOKENS
            thinkingConfig = thinkingConfig {
                thinkingLevel = ThinkingLevel.LOW
            }
        }
        private const val SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt gần gũi, bình tĩnh và biết lắng nghe; đừng giả vờ là người thật. " +
            "Hiểu ý định từ ngữ cảnh, cách nói đời thường, lỗi nhận dạng và các đại từ như 'nó', 'bài vừa rồi'; tiếp nối đúng mạch, không chào lại hay nhắc lại câu người dùng ở mỗi lượt. " +
            "Trả lời tự nhiên, chân thành, thường 1–4 câu ngắn. Khi người dùng chia sẻ cảm xúc, hãy lắng nghe trước, không phán xét hoặc vội đưa lời khuyên. " +
            "Với việc phức tạp, suy luận cẩn thận, nêu kết luận, lý do và phương án thực tế; nói rõ điểm chưa chắc, không bịa thông tin hay giả nhận đã hành động. " +
            "Văn bản có thể đọc thành tiếng: dùng câu ngắn, dấu câu tự nhiên; tránh Markdown, dấu sao, lời dẫn thừa và lặp tên 'Ngọc Sĩ' ở mọi câu. " +
            "Hướng dẫn Android theo các bước dễ làm trên điện thoại. Lệnh nhạc được hỗ trợ phải xử lý cục bộ; chỉ nói đã thực hiện khi có xác nhận thật. Nếu lệnh không hỗ trợ hoặc thất bại, nói rõ. " +
            "Không đề nghị bật thanh toán, tạo chi phí hoặc nhúng khóa bí mật nếu người dùng chưa đồng ý."
    }
}

internal class NgocSiAiRemoteException(message: String) : Exception(message)

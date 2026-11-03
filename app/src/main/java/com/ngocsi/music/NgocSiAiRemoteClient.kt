package com.ngocsi.music

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
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
 
internal data class NgocSiAiToolResult(
    /** True means the app's allowlisted command router accepted and dispatched this action. */
    val routed: Boolean,
    /** Exact feedback returned by the app command handler; it is not a playback-state guarantee. */
    val feedback: String
)

internal typealias NgocSiAiToolExecutor =
    suspend (action: String, query: String) -> NgocSiAiToolResult

internal typealias NgocSiAiRequest =
    suspend (
        messages: List<NgocSiAiMessage>,
        executeTool: NgocSiAiToolExecutor,
        onPartialResponse: (String) -> Unit
    ) -> NgocSiAiReply

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
    // Preserve a sanitized initialization failure so the UI can distinguish a missing
    // Firebase setup from an App Check/provider initialization problem.
    private var firebaseInitializationError: String? = null
    private val firebaseApp: FirebaseApp? = createFirebaseApp(appContext)

    val isConfigured: Boolean
        get() = firebaseApp != null

    // Chat UI and hands-free voice can create separate clients. Do not queue a second
    // cloud request behind a slow one; return a clear busy message instead of adding latency.
    suspend fun send(
        messages: List<NgocSiAiMessage>,
        executeTool: NgocSiAiToolExecutor,
        onPartialResponse: (String) -> Unit = {}
    ): NgocSiAiReply {
        if (!REMOTE_REQUEST_MUTEX.tryLock()) {
            throw NgocSiAiRemoteException(
                "NGỌC SĨ AI đang xử lý câu hỏi trước. Hãy đợi câu trả lời hiện tại rồi hỏi tiếp."
            )
        }
        return try {
            sendSerialized(messages, executeTool, onPartialResponse)
        } finally {
            REMOTE_REQUEST_MUTEX.unlock()
        }
    }

    private suspend fun sendSerialized(
        messages: List<NgocSiAiMessage>,
        executeTool: NgocSiAiToolExecutor,
        onPartialResponse: (String) -> Unit
    ): NgocSiAiReply {
        val app = firebaseApp
            ?: throw NgocSiAiRemoteException(
                firebaseInitializationError
                    ?: "Firebase AI Logic chưa được cấu hình cho bản thử này. Mã chẩn đoán: FIREBASE_CONFIG."
            )

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
            // Use only the explicitly configured Gemini model. Never silently switch
            // to a different or potentially billable model when quota is reached.
            val firebaseAi = FirebaseAI.getInstance(
                app,
                GenerativeBackend.googleAI(),
                /* useLimitedUseAppCheckTokens = */ true
            )
            val offersAppCommandTool =
                shouldOfferNgocSiAiAppCommandTool(newestUserMessage.content, normalized.dropLast(1))
            val responseConfig = generationConfig {
                maxOutputTokens = selectNgocSiAiMaxOutputTokens(newestUserMessage.content)
                thinkingConfig = thinkingConfig {
                    // Reasoning depth follows the current question, not accumulated chat length.
                    thinkingLevel = selectNgocSiAiThinkingLevel(newestUserMessage.content)
                }
            }
            val model = firebaseAi.generativeModel(
                modelName = MODEL_NAME,
                generationConfig = responseConfig,
                systemInstruction = content { text(SYSTEM_INSTRUCTIONS) },
                tools = if (offersAppCommandTool) {
                    listOf(Tool.functionDeclarations(listOf(APP_COMMAND_TOOL)))
                } else {
                    emptyList()
                }
            )
            val chat = model.startChat(history = previousTurns)
            val answer: String?

            if (offersAppCommandTool) {
                // Explicit app actions retain structured tool calling and allowlist validation.
                var response = chat.sendMessage(newestUserMessage.content)
                var toolRound = 0
                while (response.functionCalls.isNotEmpty() && toolRound < MAX_TOOL_ROUNDS) {
                    // Select one valid action for this round. Other proposed actions are
                    // deferred until the model has read the result of the selected action.
                    val calls = response.functionCalls.map { functionCall ->
                        NgocSiAiAgentToolCall(
                            name = functionCall.name,
                            action = functionCall.args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        )
                    }
                    val selectedCallIndex = selectNgocSiAiAgentToolCallIndex(calls, APP_COMMAND_TOOL_NAME)
                    var stopSequenceAfterFeedback = false
                    val functionResponses = mutableListOf<FunctionResponsePart>()
                    response.functionCalls.forEachIndexed { index, functionCall ->
                        val action = functionCall.args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val query = functionCall.args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val result = when {
                            functionCall.name != APP_COMMAND_TOOL_NAME || action.isBlank() ->
                                NgocSiAiToolResult(
                                    routed = false,
                                    feedback = "Lệnh không nằm trong danh sách chức năng AI được phép điều khiển."
                                )
                            index != selectedCallIndex ->
                                NgocSiAiToolResult(
                                    routed = false,
                                    feedback = ngocSiAiDeferredToolCallFeedback()
                                )
                            else -> runCatching { executeTool(action, query) }.getOrElse {
                                NgocSiAiToolResult(
                                    routed = false,
                                    feedback = "Ứng dụng chưa thực hiện được lệnh này. Vui lòng thử lại."
                                )
                            }
                        }
                        if (index == selectedCallIndex &&
                            !shouldContinueNgocSiAiAgentSequence(result.routed, result.feedback, action)
                        ) {
                            stopSequenceAfterFeedback = true
                        }
                        functionResponses += FunctionResponsePart(
                            functionCall.name,
                            JsonObject(
                                mapOf(
                                    "routed" to JsonPrimitive(result.routed),
                                    "app_feedback" to JsonPrimitive(result.feedback.take(MAX_TOOL_FEEDBACK_CHARS))
                                )
                            ),
                            functionCall.id
                        )
                    }

                    response = chat.sendMessage(content("user") {
                        functionResponses.forEach { functionResponse -> part(functionResponse) }
                    })
                    toolRound++
                    if (stopSequenceAfterFeedback) break
                }

                if (response.functionCalls.isNotEmpty()) {
                    throw NgocSiAiRemoteException(
                        "Ứng dụng chưa xác nhận bước vừa rồi thành công; NGỌC SĨ AI đã dừng các bước phụ thuộc để tránh thao tác sai."
                    )
                }
                answer = response.text?.trim()
            } else {
                // Stream ordinary chat answers so the UI can display the first words early.
                val streamedAnswer = StringBuilder()
                chat.sendMessageStream(newestUserMessage.content).collect { chunk ->
                    val textChunk = chunk.text.orEmpty()
                    if (textChunk.isNotEmpty()) {
                        streamedAnswer.append(textChunk)
                        onPartialResponse(streamedAnswer.toString())
                    }
                }
                answer = streamedAnswer.toString().trim()
            }

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
            }.onFailure { error ->
                // Do not silently discard Firebase/App Check initialization errors.
                // toSafeMessage strips raw server details, tokens and configuration values.
                firebaseInitializationError = toSafeMessage(
                    error as? Exception ?: Exception(error.javaClass.simpleName)
                )
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
            quotaDiagnosticCode == "AI_PAID_TIER_REQUIRED" ->
                "Google báo mô hình hoặc tính năng này yêu cầu quyền truy cập trả phí. Mã chẩn đoán: AI_PAID_TIER_REQUIRED. Bản Preview không bật thanh toán; các lệnh cục bộ vẫn hoạt động."
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
        private val REMOTE_REQUEST_MUTEX = Mutex()
        private var appCheckProviderInstalled = false

        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val QUOTA_PREFERENCES = "ngoc_si_ai_quota_cooldown"
        private const val KEY_QUOTA_COOLDOWN_UNTIL = "cooldown_until_millis"
        private const val KEY_QUOTA_COOLDOWN_KIND = "cooldown_kind"
        private const val EXPECTED_FIREBASE_PROJECT_ID = "ngoc-si-music-ai"
        // Keep Gemini 3.8 Flash as the single Google AI backend model.
        // Quota errors trigger a cooldown; there is no automatic paid-model fallback.
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val APP_COMMAND_TOOL_NAME = "execute_app_command"
        private val SUPPORTED_APP_ACTIONS = listOf(
            "play",
            "play_second",
            "play_track",
            "add_track_to_queue",
            "pause",
            "stop",
            "next",
            "previous",
            "current_track",
            "volume_up",
            "volume_down",
            "mute",
            "unmute",
            "set_playback_speed",
            "seek_forward_10",
            "seek_back_10",
            "seek_by_seconds",
            "youtube_seek_by_seconds",
            "favorite_current_track",
            "shuffle_on",
            "shuffle_off",
            "repeat_off",
            "repeat_all",
            "repeat_one",
            "open_home",
            "open_library",
            "open_favorites",
            "open_albums",
            "open_folders",
            "open_online",
            "search_online_music",
            "search_drive",
            "open_youtube",
            "search_youtube",
            "youtube_play",
            "youtube_pause",
            "youtube_next",
            "youtube_previous",
            "youtube_seek_forward_10",
            "youtube_seek_back_10",
            "youtube_shuffle_on",
            "youtube_shuffle_off",
            "youtube_repeat_off",
            "youtube_repeat_all",
            "youtube_repeat_one",
            "youtube_favorite",
            "youtube_watch_later",
            "open_drive",
            "open_radio",
            "play_radio_station",
            "open_tv",
            "open_tv_channel",
            "create_playlist",
            "play_playlist",
            "add_current_to_playlist",
            "add_queue_to_playlist",
            "open_playlists",
            "open_queue",
            "open_player",
            "open_settings",
            "open_sleep_timer",
            "sleep_timer_15",
            "sleep_timer_30",
            "sleep_timer_45",
            "sleep_timer_60",
            "sleep_timer_90",
            "sleep_timer_120",
            "sleep_timer_custom",
            "cancel_sleep_timer",
            "help"
        )
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_TOOL_FEEDBACK_CHARS = 900
        private val APP_COMMAND_TOOL = FunctionDeclaration(
            name = "execute_app_command",
            description = """
                Điều khiển các chức năng được phép của ứng dụng NGỌC SĨ MUSIC theo yêu cầu rõ ràng của người dùng.
                Chỉ gọi khi người dùng thực sự muốn thực hiện thao tác, không gọi khi họ chỉ hỏi cách làm hoặc hỏi thông tin.
                action phải là một trong các mã sau:
                play, play_second, play_track, add_track_to_queue, pause, stop, next, previous, current_track,
                volume_up, volume_down, mute, unmute,
                set_playback_speed, seek_forward_10, seek_back_10, seek_by_seconds, youtube_seek_by_seconds, favorite_current_track,
                shuffle_on, shuffle_off, repeat_off, repeat_all, repeat_one,
                open_home, open_library, open_favorites, open_albums, open_folders, open_online,
                search_online_music, search_drive, open_youtube, search_youtube, youtube_play, youtube_pause,
                youtube_next, youtube_previous, youtube_seek_forward_10, youtube_seek_back_10,
                youtube_shuffle_on, youtube_shuffle_off, youtube_repeat_off, youtube_repeat_all,
                youtube_repeat_one, youtube_favorite,
                youtube_watch_later, open_drive, open_radio, play_radio_station, open_tv,
                open_tv_channel, create_playlist, play_playlist, add_current_to_playlist,
                add_queue_to_playlist, open_playlists, open_queue, open_player, open_settings,
                open_sleep_timer, sleep_timer_15, sleep_timer_30, sleep_timer_45,
                sleep_timer_60, sleep_timer_90, sleep_timer_120, sleep_timer_custom,
                cancel_sleep_timer, help.
                Với sleep_timer_custom, query là số phút từ 1 đến 1440 (24 giờ).
                Query cũng chứa tên bài hát, từ khóa tìm kiếm, tên đài, kênh hoặc playlist khi action cần dữ liệu đó.
                Không tự đoán tên bài, đài, kênh hay playlist nếu người dùng chưa nêu; hãy hỏi lại.
                Không có action xóa dữ liệu, xóa playlist, xóa thư viện, thay đổi quyền, đăng nhập tài khoản hoặc thanh toán.
                set_playback_speed chỉ áp dụng cho nhạc thiết bị, Drive và Radio qua Media3; không tự ý áp dụng tốc độ Media3 lên YouTube.
                seek_forward_10/seek_back_10 tua 10 giây theo nguồn đang phát. seek_by_seconds nhận query là số giây có dấu (ví dụ 30 hoặc -15), giới hạn 1–120 giây. youtube_seek_by_seconds dùng cùng quy tắc nhưng chỉ cho YouTube. Với yêu cầu 10 giây, có thể dùng action cố định cũ.
                volume_up, volume_down, mute và unmute điều chỉnh âm lượng media của Android, áp dụng cho nguồn đang phát trên thiết bị; không cần hàng đợi Media3.
                Các action shuffle_on/off và repeat_off/all/one điều khiển Media3; khi nguồn phát là YouTube phải dùng youtube_shuffle_on/off và youtube_repeat_off/all/one. favorite_current_track chỉ thêm bài hiện tại vào Yêu thích, không xóa khỏi danh sách.
                Sau khi gọi, phải dựa vào app_feedback: nếu phản hồi chỉ nói đang mở/đang tìm/đã gửi lệnh thì không được khẳng định thao tác đã hoàn tất.
            """.trimIndent(),
            parameters = mapOf(
                "action" to Schema.enumeration(
                    values = SUPPORTED_APP_ACTIONS,
                    description = "Mã hành động chính xác trong danh sách được phép."
                ),
                "query" to Schema.string(
                    "Tên bài hát, từ khóa, tên đài, kênh, playlist hoặc tốc độ phát được phép (0.5–2.0). Để trống nếu không cần."
                )
            ),
            optionalParameters = listOf("query")
        )
        private const val SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt gần gũi, bình tĩnh và biết lắng nghe; đừng giả vờ là người thật. " +
            "Hiểu ý định từ ngữ cảnh, cách nói đời thường, lỗi nhận dạng và các đại từ như 'nó', 'bài vừa rồi'; tiếp nối đúng mạch, không chào lại hay nhắc lại câu người dùng ở mỗi lượt. " +
            "Giữ nhất quán các lựa chọn và mục tiêu đã nêu; khi người dùng nói 'tiếp tục' hoặc dùng đại từ, hãy dựa vào lịch sử gần nhất; nếu vẫn có nhiều cách hiểu quan trọng, hỏi một câu làm rõ ngắn. " +
            "Với yêu cầu nhiều bước, chia thành trình tự tối đa 4 bước; mỗi lượt chỉ thực hiện một hành động được phép. Chỉ tiếp tục bước phụ thuộc khi app_feedback xác nhận thao tác trước đã hoàn tất. Nếu phản hồi chỉ nói đang mở/tìm/nạp/tải/chuyển/phát/thêm/lưu/tua/đặt hoặc đã gửi lệnh/đã yêu cầu, hãy dừng chuỗi, nói đúng rằng thao tác mới được khởi động hoặc gửi đi và chờ xác nhận tiếp theo; không gửi ngay intent thứ hai vì điều hướng có thể chưa hoàn tất. Nếu bước trước thất bại, dừng hoặc hỏi lại thay vì đoán. Không báo đã phát, chuyển bài hoặc lưu thành công nếu chưa có xác nhận tương ứng. " +
            "Trả lời tự nhiên, chân thành, thường 1–4 câu ngắn. Khi người dùng chia sẻ cảm xúc, hãy lắng nghe trước, không phán xét hoặc vội đưa lời khuyên. " +
            "Với việc phức tạp, suy luận cẩn thận, nêu kết luận, lý do và phương án thực tế; nói rõ điểm chưa chắc, không bịa thông tin hay giả nhận đã hành động. " +
            "Văn bản có thể đọc thành tiếng: dùng câu ngắn, dấu câu tự nhiên; tránh Markdown, dấu sao, lời dẫn thừa và lặp tên 'Ngọc Sĩ' ở mọi câu. " +
            "Hướng dẫn Android theo các bước dễ làm trên điện thoại. Khi người dùng yêu cầu thao tác trong NGỌC SĨ MUSIC mà câu nói chưa được bộ lệnh cục bộ nhận diện, hãy dùng execute_app_command với action trong danh sách cho phép. " +
            "Chỉ dùng công cụ cho yêu cầu hành động rõ ràng, không dùng cho câu hỏi, gợi ý hoặc trò chuyện thông thường. Hỏi lại khi thiếu tên bài, từ khóa, đài, kênh, playlist hoặc tốc độ phát cần thiết. " +
            "Với thao tác thêm bài vào hàng đợi, dùng add_track_to_queue và query là tên bài; với tìm kiếm Drive, dùng search_drive và query là từ khóa. " +
            "Khi người dùng muốn tăng/giảm âm lượng hoặc tắt/bật tiếng, dùng volume_up, volume_down, mute hoặc unmute; đây là âm lượng media hệ thống Android và dùng chung cho YouTube, nhạc máy, Drive và Radio. " +
            "Khi người dùng nhắc YouTube hoặc video YouTube, hãy dùng youtube_play, youtube_pause, youtube_next, youtube_previous, youtube_seek_forward_10 hoặc youtube_seek_back_10 tương ứng; không dùng action chung cho yêu cầu đã nêu rõ YouTube. Nếu người dùng nói 'tiếp theo', 'chuyển bài' hoặc 'bài trước' và ngữ cảnh gần nhất đang phát YouTube, phải giữ nguồn YouTube; không biến lệnh chuyển video đang phát thành lệnh tìm kiếm. " +
            "Với YouTube, dùng các action youtube_shuffle/youtube_repeat; action shuffle/repeat không có tiền tố youtube chỉ dành cho Media3. Đổi tốc độ chỉ áp dụng cho Media3; tua 10 giây trên YouTube dùng action youtube_seek riêng. " +
            "Sau khi công cụ trả về, xem app_feedback là phản hồi thật của ứng dụng; routed chỉ cho biết lệnh đã được chuyển tới bộ điều khiển, không đồng nghĩa nhạc đã phát hoặc thao tác đã hoàn tất. " +
            "Nếu phản hồi nói đang mở/đang tìm/đang tải/đã gửi lệnh, hãy diễn đạt đúng trạng thái đó; nếu báo không tìm thấy, không có, lỗi hoặc chưa kết nối thì phải nói rõ, tuyệt đối không giả nhận thành công. " +
            "Không được gọi action ngoài danh sách, không được yêu cầu ứng dụng xóa dữ liệu, xóa playlist, xóa thư viện, đổi quyền hoặc thanh toán. " +
            "Không đề nghị bật thanh toán, tạo chi phí hoặc nhúng khóa bí mật nếu người dùng chưa đồng ý."
    }
}

/**
 * A remote agent may propose several app-tool calls in one model response.
 * Execute only the first valid call, then return feedback for every call so the
 * model can choose dependent next steps using the actual app response.
 */
internal data class NgocSiAiAgentToolCall(
    val name: String,
    val action: String
)

internal fun selectNgocSiAiAgentToolCallIndex(
    calls: List<NgocSiAiAgentToolCall>,
    expectedToolName: String
): Int? {
    if (expectedToolName.isBlank()) return null
    return calls.indexOfFirst { call ->
        call.name == expectedToolName && call.action.isNotBlank()
    }.takeIf { it >= 0 }
}

internal fun ngocSiAiDeferredToolCallFeedback(): String =
    "Đã hoãn bước này để kiểm tra phản hồi của thao tác trước. " +
        "Hãy chọn bước tiếp theo dựa trên kết quả vừa nhận."

internal fun shouldContinueNgocSiAiAgentSequence(
    routed: Boolean,
    feedback: String,
    action: String? = null
): Boolean {
    if (!routed) return false
    val normalized = normalizeNgocSiAiVoicePhrase(feedback)
    val failureMarkers = listOf(
        "chua thuc hien", "khong tim thay", "khong co bai", "chua co ",
        "khong co kenh", "khong co dai", "gap loi", "that bai", "khong the", "chua ket noi",
        "chua dang nhap", "can dang nhap", "chua cap quyen", "chua co quyen",
        "khong ho tro", "khong hop le", "thieu thong tin", "hay noi ro",
        "hay noi ten", "chua mo duoc", "khong ap dung", "hay dung dieu khien rieng",
        "khong nhan dien duoc"
    )
    if (failureMarkers.any { marker -> normalized.contains(marker) }) return false

    // current_track is a read-only status query. Its answer naturally begins with
    // "Đang phát" or "Đang tạm dừng", which must not be mistaken for a pending action.
    if (action == "current_track") return true

    // These replies acknowledge dispatch or ongoing work, not completion.
    // MainActivity navigation and YouTube/background controls can finish asynchronously;
    // sending a dependent action now could overtake or replace the first request.
    val pendingMarkers = listOf(
        "dang mo ", "dang tim ", "dang nap ", "dang tai ", "dang chuyen ",
        "dang phat ", "dang them ", "dang luu ", "dang tua ", "dang tam dung ",
        "dang kiem tra ", "dang ket noi ", "dang xu ly ", "dang dat ",
        "da gui lenh", "da yeu cau"
    )
    return pendingMarkers.none { marker -> normalized.contains(marker) }
}

internal class NgocSiAiRemoteException(message: String) : Exception(message)

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
import kotlinx.coroutines.delay
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
 * Executes one allowlisted app action without swallowing coroutine cancellation.
 * Ordinary action failures are returned as safe, non-routed feedback so the agent
 * stops dependent steps; cancellation must escape to the caller unchanged.
 */
internal suspend fun executeNgocSiAiToolSafely(
    executeTool: NgocSiAiToolExecutor,
    action: String,
    query: String
): NgocSiAiToolResult = try {
    executeTool(action, query)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    NgocSiAiToolResult(
        routed = false,
        feedback = "Ứng dụng chưa thực hiện được lệnh này. Vui lòng thử lại."
    )
}

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
        entryContext: String? = null,
        mediaContextInstruction: String? = null,
        youtubeVideoId: String? = null,
        onPartialResponse: (String) -> Unit = {}
    ): NgocSiAiReply {
        if (!REMOTE_REQUEST_MUTEX.tryLock()) {
            throw NgocSiAiRemoteException(
                "NGỌC SĨ AI đang xử lý câu hỏi trước. Hãy đợi câu trả lời hiện tại rồi hỏi tiếp."
            )
        }
        return try {
            sendSerialized(
                messages,
                executeTool,
                onPartialResponse,
                entryContext,
                mediaContextInstruction,
                youtubeVideoId
            )
        } finally {
            REMOTE_REQUEST_MUTEX.unlock()
        }
    }

    private suspend fun sendSerialized(
        messages: List<NgocSiAiMessage>,
        executeTool: NgocSiAiToolExecutor,
        onPartialResponse: (String) -> Unit,
        entryContext: String?,
        mediaContextInstruction: String?,
        youtubeVideoId: String?
    ): NgocSiAiReply {
        val app = firebaseApp
            ?: throw NgocSiAiRemoteException(
                firebaseInitializationError
                    ?: "Firebase AI Logic chưa được cấu hình cho bản thử này. Mã chẩn đoán: FIREBASE_CONFIG."
            )


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
        // If AI was launched from YouTube, preserve that source even if Gemini proposes
        // the general Online search action for an explicit search-then-play request.
        val contextualYoutubeAutoPlayQuery =
            extractNgocSiAiContextualYoutubeAutoPlayQuery(newestUserMessage.content, entryContext)
        // A search-only request from the YouTube page must stay on YouTube without autoplay.
        val contextualYoutubeSearchQuery = if (contextualYoutubeAutoPlayQuery == null) {
            extractNgocSiAiContextualYoutubeQuery(newestUserMessage.content, entryContext)
        } else {
            null
        }
        // On the Drive page, keep source-neutral searches in Drive even if Gemini
        // proposes the general Online action. Explicitly named sources are excluded
        // by the contextual query extractor and therefore remain untouched.
        val contextualDriveSearchQuery =
            extractNgocSiAiContextualDriveQuery(newestUserMessage.content, entryContext)
        // For reference-only searches such as "Tìm bài tương tự", lock the
        // action to the one unambiguous source identified by the latest assistant
        // answer. The model may supply the search terms, but cannot switch sources
        // or turn this search-only request into playback.
        val contextualReferenceSearchAction = resolveNgocSiAiContextualSearchOnlyAction(
            newestUserMessage.content,
            normalized.dropLast(1)
        )
        // If the previous assistant turn confirms an active YouTube video, short
        // transport follow-ups must keep using the YouTube player instead of a
        // potentially stale Media3 source flag.
        val contextualYoutubeTransportAction = resolveNgocSiAiContextualYoutubeTransportAction(
            newestUserMessage.content,
            normalized.dropLast(1)
        )
        // Never downgrade a requested YouTube evidence analysis to text-only chat.
        // Missing video context is a blocker, not permission to guess from metadata.
        if (
            isNgocSiAiYoutubeVideoAttachmentMissing(
                pageContext = entryContext,
                currentVideoId = youtubeVideoId,
                prompt = newestUserMessage.content,
                previousMessages = normalized.dropLast(1)
            )
        ) {
            throw NgocSiAiRemoteException(buildNgocSiAiYoutubeVideoUnavailableReply())
        }
        // Playback commands and ordinary chat retain the lightweight text-only path.
        val youtubeVideoAnalysisUri = resolveNgocSiAiYoutubeVideoAnalysisUri(
            pageContext = entryContext,
            currentVideoId = youtubeVideoId,
            prompt = newestUserMessage.content,
            previousMessages = normalized.dropLast(1)
        )
        val forceMultimodalReasoning = youtubeVideoAnalysisUri != null
        val multimodalUserMessage = youtubeVideoAnalysisUri?.let { videoUri ->
            content("user") {
                // Firebase AI Logic accepts one public/unlisted YouTube URL per request.
                // Put the video before the compact text prompt, as recommended by Firebase.
                fileData(uri = videoUri, mimeType = "video/mp4")
                text(
                    buildNgocSiAiVideoAnalysisRequestText(
                        userPrompt = newestUserMessage.content,
                        recentMessages = normalized.dropLast(1),
                        mediaContextInstruction = mediaContextInstruction
                    )
                )
            }
        }

        // Firebase AI Logic requires model-role history for assistant responses.
        val previousTurns = normalized.dropLast(1).map { message ->
            content(role = if (message.role == "assistant") "model" else "user") {
                text(message.content)
            }
        }

        val offersAppCommandTool =
            shouldOfferNgocSiAiAppCommandTool(newestUserMessage.content, normalized.dropLast(1))
        val selectedModelName = selectNgocSiAiModelName(
            newestUserMessage.content,
            offersAppCommandTool,
            forceMultimodalReasoning = forceMultimodalReasoning
        )
        activeQuotaCooldownMessage(selectedModelName)?.let {
            throw NgocSiAiRemoteException(it)
        }

        return try {
            // Use only the explicitly configured Gemini model. Never silently switch
            // to a different or potentially billable model when quota is reached.
            val firebaseAi = FirebaseAI.getInstance(
                app,
                GenerativeBackend.googleAI(),
                /* useLimitedUseAppCheckTokens = */ true
            )
            val responseConfig = generationConfig {
                maxOutputTokens = selectNgocSiAiMaxOutputTokens(
                    newestUserMessage.content,
                    forceMultimodalReasoning = forceMultimodalReasoning
                )
                thinkingConfig = thinkingConfig {
                    // Flash-Lite is MINIMAL-only; attached video analysis stays on Gemini Flash MEDIUM.
                    thinkingLevel = selectNgocSiAiGenerationThinkingLevel(
                        newestUserMessage.content,
                        offersAppCommandTool,
                        selectedModelName,
                        forceMultimodalReasoning = forceMultimodalReasoning
                    )
                }
            }
            val model = firebaseAi.generativeModel(
                modelName = selectedModelName,
                generationConfig = responseConfig,
                systemInstruction = if (multimodalUserMessage != null && !offersAppCommandTool) {
                    // A video-analysis turn is stateless at the Gemini chat layer:
                    // compact context is placed beside the video, avoiding a long
                    // transcript and the general-purpose app-agent instruction bundle.
                    content { text(VIDEO_ANALYSIS_SYSTEM_INSTRUCTIONS) }
                } else {
                    content {
                        val savedMemories = decodeNgocSiAiMemory(
                            appContext.getSharedPreferences(
                                NGOC_SI_AI_CHAT_PREFERENCES_NAME,
                                Context.MODE_PRIVATE
                            ).getString(NGOC_SI_AI_LONG_TERM_MEMORY_KEY, null)
                        )
                        text(
                            SYSTEM_INSTRUCTIONS +
                                buildNgocSiAiMemorySystemInstruction(savedMemories, newestUserMessage.content) +
                                buildNgocSiAiEntryContextInstruction(entryContext) +
                                mediaContextInstruction.orEmpty().take(1_200) +
                                buildNgocSiAiCreativeMusicAnalysisInstruction(
                                    rawUserText = newestUserMessage.content,
                                    recentConversation = normalized.dropLast(1).takeLast(6).map { it.content },
                                    mediaContextInstruction = mediaContextInstruction
                                ) +
                                if (multimodalUserMessage != null) {
                                    " VIDEO ĐÃ ĐƯỢC ĐÍNH KÈM: hãy phân tích cả hình ảnh và âm thanh thực sự nhận được, " +
                                        "nêu mốc thời gian cho nhận xét quan trọng khi phù hợp. Video được lấy mẫu hình ảnh " +
                                        "xấp xỉ 1 khung hình/giây nên có thể bỏ lỡ cảnh chuyển nhanh; không khẳng định chi tiết " +
                                        "không xuất hiện trong dữ liệu đã xử lý."
                                } else ""
                        )
                    }
                },
                tools = if (offersAppCommandTool) {
                    listOf(Tool.functionDeclarations(listOf(APP_COMMAND_TOOL)))
                } else {
                    emptyList()
                }
            )
            val answer: String?

            if (offersAppCommandTool) {
                val chat = model.startChat(history = previousTurns)
                // Explicit app actions retain structured tool calling and allowlist validation.
                var response = multimodalUserMessage?.let { chat.sendMessage(it) }
                    ?: chat.sendMessage(newestUserMessage.content)
                var toolRound = 0
                var stopSequenceFeedback: String? = null
                var contextualYoutubeAutoPlayDispatched = false
                var contextualYoutubeSearchDispatched = false
                var contextualDriveSearchDispatched = false
                var contextualReferenceSearchDispatched = false
                var contextualYoutubeTransportDispatched = false
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
                        ?: throw NgocSiAiRemoteException(
                            "Mô hình đề xuất thao tác không hợp lệ hoặc không có lệnh được phép. " +
                                "Ứng dụng chưa thực hiện thao tác nào."
                        )
                    var stopSequenceAfterFeedback = false
                    val functionResponses = mutableListOf<FunctionResponsePart>()
                    response.functionCalls.forEachIndexed { index, functionCall ->
                        val action = functionCall.args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val query = functionCall.args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        var effectiveAction = action
                        val result = when {
                            functionCall.name != APP_COMMAND_TOOL_NAME ->
                                NgocSiAiToolResult(
                                    routed = false,
                                    feedback = "Lệnh không nằm trong danh sách chức năng AI được phép điều khiển."
                                )
                            index != selectedCallIndex ->
                                NgocSiAiToolResult(
                                    routed = false,
                                    feedback = ngocSiAiDeferredToolCallFeedback()
                                )
                            else -> {
                                val inferredYoutubeQuery = contextualYoutubeAutoPlayQuery
                                    ?.takeIf { !contextualYoutubeAutoPlayDispatched }
                                val inferredYoutubeSearchQuery = contextualYoutubeSearchQuery
                                    ?.takeIf { !contextualYoutubeSearchDispatched }
                                val inferredDriveQuery = contextualDriveSearchQuery
                                    ?.takeIf { !contextualDriveSearchDispatched }
                                val inferredReferenceSearchAction = contextualReferenceSearchAction
                                    ?.takeIf { !contextualReferenceSearchDispatched }
                                val inferredYoutubeTransportAction = contextualYoutubeTransportAction
                                    ?.takeIf { !contextualYoutubeTransportDispatched }
                                val isTransportAction = action in setOf(
                                    "play", "youtube_play", "pause", "youtube_pause",
                                    "next", "youtube_next", "previous", "youtube_previous"
                                )
                                if (inferredYoutubeQuery != null) {
                                    contextualYoutubeAutoPlayDispatched = true
                                    effectiveAction = "search_and_play_youtube"
                                    executeNgocSiAiToolSafely(executeTool, effectiveAction, inferredYoutubeQuery)
                                } else if (inferredYoutubeSearchQuery != null) {
                                    contextualYoutubeSearchDispatched = true
                                    effectiveAction = "search_youtube"
                                    executeNgocSiAiToolSafely(executeTool, effectiveAction, inferredYoutubeSearchQuery)
                                } else if (inferredDriveQuery != null) {
                                    contextualDriveSearchDispatched = true
                                    effectiveAction = "search_drive"
                                    executeNgocSiAiToolSafely(executeTool, effectiveAction, inferredDriveQuery)
                                } else if (inferredReferenceSearchAction != null) {
                                    contextualReferenceSearchDispatched = true
                                    effectiveAction = inferredReferenceSearchAction
                                    if (!isNgocSiAiContextualSearchQuerySpecificEnough(query)) {
                                        NgocSiAiToolResult(
                                            routed = false,
                                            feedback = "Chưa xác định được tên bài, nghệ sĩ hoặc từ khóa cụ thể. Chưa tìm kiếm; hãy hỏi người dùng để làm rõ."
                                        )
                                    } else {
                                        executeNgocSiAiToolSafely(executeTool, effectiveAction, query)
                                    }
                                } else if (inferredYoutubeTransportAction != null && isTransportAction) {
                                    contextualYoutubeTransportDispatched = true
                                    effectiveAction = inferredYoutubeTransportAction
                                    executeNgocSiAiToolSafely(executeTool, effectiveAction, "")
                                } else if (action.isBlank()) {
                                    NgocSiAiToolResult(
                                        routed = false,
                                        feedback = "Lệnh không nằm trong danh sách chức năng AI được phép điều khiển."
                                    )
                                } else {
                                    executeNgocSiAiToolSafely(executeTool, action, query)
                                }
                            }
                        }
                        if (index == selectedCallIndex) {
                            val feedbackState = classifyNgocSiAiAgentFeedback(
                                result.routed,
                                result.feedback,
                                effectiveAction
                            )
                            stopSequenceFeedback = ngocSiAiAgentStopFeedback(
                                feedbackState,
                                result.feedback
                            )
                            stopSequenceAfterFeedback = stopSequenceFeedback != null
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

                    // A pending or failed app result is already sufficient reason to stop.
                    // Do not spend another Gemini request generating a response that will be
                    // discarded below; this keeps command handling faster and saves quota.
                    if (stopSequenceAfterFeedback) {
                        stopSequenceFeedback?.let { safeStopMessage ->
                            throw NgocSiAiRemoteException(safeStopMessage)
                        }
                        throw NgocSiAiRemoteException(
                            "Ứng dụng chưa xác nhận bước vừa rồi hoàn tất; tôi đã dừng các bước phụ thuộc."
                        )
                    }

                    response = chat.sendMessage(content("user") {
                        functionResponses.forEach { functionResponse -> part(functionResponse) }
                    })
                    toolRound++
                }

                stopSequenceFeedback?.let { safeStopMessage ->
                    // Never trust a generated follow-up to turn pending/failed app feedback into success.
                    throw NgocSiAiRemoteException(safeStopMessage)
                }
                if (response.functionCalls.isNotEmpty()) {
                    throw NgocSiAiRemoteException(
                        "Ứng dụng chưa xác nhận bước vừa rồi thành công; NGỌC SĨ AI đã dừng các bước phụ thuộc để tránh thao tác sai."
                    )
                }
                answer = response.text?.trim()
            } else {
                // Stream ordinary chat. Retry once only when a transient failure happens
                // before any text is emitted; replaying a partially visible answer would duplicate it.
                var attempt = 0
                var completedStream: String? = null
                while (completedStream == null) {
                    val streamedAnswer = StringBuilder()
                    try {
                        val responseStream = if (multimodalUserMessage != null && !offersAppCommandTool) {
                            // Use the documented single-turn multimodal streaming API.
                            // Compact context is included with the video; full chat history is skipped.
                            model.generateContentStream(multimodalUserMessage)
                        } else {
                            val chat = model.startChat(history = previousTurns)
                            multimodalUserMessage?.let { chat.sendMessageStream(it) }
                                ?: chat.sendMessageStream(newestUserMessage.content)
                        }
                        responseStream.collect { chunk ->
                                val textChunk = chunk.text.orEmpty()
                                if (textChunk.isNotEmpty()) {
                                    streamedAnswer.append(textChunk)
                                    onPartialResponse(streamedAnswer.toString())
                                }
                            }
                        completedStream = streamedAnswer.toString().trim()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val errorDetails = generateSequence<Throwable>(error) { it.cause }
                            .take(6)
                            .joinToString(" ") {
                                it.javaClass.simpleName + " " + it.message.orEmpty()
                            }
                        val mayRetry = shouldRetryNgocSiAiRemoteRequest(
                            errorDetails = errorDetails,
                            isToolRequest = offersAppCommandTool,
                            hasEmittedPartialResponse = streamedAnswer.isNotBlank(),
                            attempt = attempt
                        )
                        if (!mayRetry) throw error
                        attempt++
                        delay(NGOC_SI_AI_TRANSIENT_RETRY_DELAY_MILLIS)
                    }
                }
                answer = completedStream
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
            persistQuotaCooldownIfNeeded(safeMessage, selectedModelName)
            throw NgocSiAiRemoteException(safeMessage)
        }
    }


    private fun activeQuotaCooldownMessage(modelName: String): String? {
        migrateLegacyQuotaCooldownToReasoningModel()
        val (untilKey, kindKey) = ngocSiAiQuotaCooldownPreferenceKeys(modelName)
        val until = quotaPreferences.getLong(untilKey, 0L)
        val now = System.currentTimeMillis()
        if (until <= now) {
            quotaPreferences.edit().remove(untilKey).remove(kindKey).apply()
            return null
        }
        val kind = quotaPreferences.getString(kindKey, null)
            ?.let { runCatching { NgocSiAiQuotaCooldownKind.valueOf(it) }.getOrNull() }
            ?: NgocSiAiQuotaCooldownKind.TEMPORARY
        return ngocSiAiQuotaCooldownNotice(kind, until - now)
    }

    private fun persistQuotaCooldownIfNeeded(message: String, modelName: String) {
        val kind = classifyNgocSiAiQuotaCooldown(message) ?: return
        val until = ngocSiAiQuotaCooldownUntilMillis(kind, System.currentTimeMillis())
        val (untilKey, kindKey) = ngocSiAiQuotaCooldownPreferenceKeys(modelName)
        quotaPreferences.edit()
            .putLong(untilKey, until)
            .putString(kindKey, kind.name)
            .apply()
    }

    /** Older versions stored one global cooldown. Attribute it to the old default model only. */
    private fun migrateLegacyQuotaCooldownToReasoningModel() {
        val legacyUntil = quotaPreferences.getLong(KEY_QUOTA_COOLDOWN_UNTIL, 0L)
        val legacyKind = quotaPreferences.getString(KEY_QUOTA_COOLDOWN_KIND, null)
        if (legacyUntil > System.currentTimeMillis() && !legacyKind.isNullOrBlank()) {
            val (reasoningUntilKey, reasoningKindKey) =
                ngocSiAiQuotaCooldownPreferenceKeys(NGOC_SI_AI_REASONING_MODEL_NAME)
            if (!quotaPreferences.contains(reasoningUntilKey)) {
                quotaPreferences.edit()
                    .putLong(reasoningUntilKey, legacyUntil)
                    .putString(
                        reasoningKindKey,
                        runCatching { NgocSiAiQuotaCooldownKind.valueOf(legacyKind) }
                            .getOrDefault(NgocSiAiQuotaCooldownKind.TEMPORARY).name
                    )
                    .apply()
            }
        }
        if (legacyUntil > 0L || legacyKind != null) {
            quotaPreferences.edit()
                .remove(KEY_QUOTA_COOLDOWN_UNTIL)
                .remove(KEY_QUOTA_COOLDOWN_KIND)
                .apply()
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
        // Include exception superclasses as well as causes: some SDK transport wrappers
        // have very short names (for example "S"), while their superclass identifies Firebase AI Logic.
        val exceptionChain = generateSequence<Throwable>(error) { it.cause }
            .take(8)
            .toList()
        val exceptionClassNames = exceptionChain.flatMap { throwable ->
            buildList {
                var current: Class<*>? = throwable.javaClass
                var depth = 0
                while (current != null && depth < 6) {
                    add(current.name)
                    current = current.superclass
                    depth++
                }
            }
        }.distinct()
        val exceptionTypeDetails = exceptionClassNames.joinToString(" ").lowercase(Locale.ROOT)
        val details = (
            exceptionClassNames.joinToString(" ") + " " +
                exceptionChain.joinToString(" ") { it.message.orEmpty() }
            ).lowercase(Locale.ROOT)
        val errorCode = extractNgocSiAiRemoteHttpStatusCode(details)
            ?: Regex("\\b(400|401|403|404|408|413|415|422|429|500|501|502|503|504)\\b")
                .find(details)
                ?.value

        val quotaDiagnosticCode = ngocSiAiQuotaDiagnosticCode(details)
        val diagnosticExceptionType =
            ngocSiAiRemoteDiagnosticExceptionType(exceptionClassNames)

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
            errorCode == "400" ->
                "Gemini từ chối nội dung yêu cầu (HTTP 400). Với phân tích YouTube, hãy kiểm tra video ở chế độ công khai/không công khai và URL còn hợp lệ. Mã chẩn đoán: INVALID_REQUEST_400."
            errorCode == "404" || "model not found" in details || "not found" in details ->
                "Không tìm thấy model hoặc endpoint AI. Mã chẩn đoán: MODEL_OR_ENDPOINT_404."
            errorCode == "413" ->
                "Máy chủ từ chối yêu cầu vì dữ liệu đầu vào quá lớn. Mã chẩn đoán: REQUEST_TOO_LARGE_413."
            errorCode == "415" ->
                "Máy chủ không chấp nhận định dạng media của yêu cầu. Mã chẩn đoán: UNSUPPORTED_MEDIA_TYPE_415."
            errorCode == "422" ->
                "Máy chủ không xử lý được cấu trúc yêu cầu video. Mã chẩn đoán: INVALID_REQUEST_422."
            errorCode == "429" ->
                "Google trả về HTTP 429 nhưng phần thông điệp không nêu rõ cửa sổ quota. Mã chẩn đoán: AI_QUOTA_429_PROJECT_OR_CAPACITY."
            errorCode in setOf("408", "500", "501", "502", "503", "504") ->
                "Máy chủ Gemini đang tạm thời bận hoặc kết nối bị gián đoạn (HTTP $errorCode). " +
                    "Ứng dụng chỉ tự thử lại một lần cho câu chat thông thường; lệnh điều khiển không tự gửi lại."
            "requesttimeoutexception" in exceptionTypeDetails ->
                "Yêu cầu Gemini đã quá thời gian chờ. Mã chẩn đoán: REMOTE_TIMEOUT. Hãy thử lại sau khi mạng ổn định."
            "invalidapikeyexception" in exceptionTypeDetails ->
                "Firebase AI Logic từ chối API key của cấu hình Preview. Mã chẩn đoán: FIREBASE_API_KEY."
            "servicedisabledexception" in exceptionTypeDetails ||
                "apinotconfiguredexception" in exceptionTypeDetails ->
                "Firebase AI Logic chưa được bật hoặc cấu hình đúng cho dự án Preview. Mã chẩn đoán: FIREBASE_CONFIG."
            "serverexception" in exceptionTypeDetails ->
                "Máy chủ Firebase AI Logic trả về lỗi nhưng SDK không cung cấp mã HTTP trong ngoại lệ. Mã chẩn đoán: REMOTE_SERVER_ERROR."
            "contentblockedexception" in exceptionTypeDetails ||
                "promptblockedexception" in exceptionTypeDetails ->
                "Yêu cầu bị bộ lọc an toàn của Gemini chặn. Mã chẩn đoán: CONTENT_BLOCKED."
            "firebaseaiexception" in exceptionTypeDetails ->
                "Firebase AI Logic SDK phát sinh ngoại lệ trong lúc gửi hoặc nhận video. Mã chẩn đoán: FIREBASE_AI_SDK_ERROR."
            "api key" in details || "api has not been used" in details ||
                "service disabled" in details || "not enabled" in details ->
                "Firebase AI Logic chưa được bật hoặc cấu hình đúng. Mã chẩn đoán: FIREBASE_CONFIG."
            "unknownhost" in details || "unable to resolve host" in details ||
                "network" in details || "timeout" in details || "timed out" in details ->
                "Không kết nối được mạng. Mã chẩn đoán: NETWORK. Hãy kiểm tra Internet rồi thử lại."
            else -> {
                "Firebase AI Logic chưa phản hồi thành công. Mã chẩn đoán: REMOTE_ERROR_" +
                    diagnosticExceptionType + (errorCode?.let { "_$it" } ?: "") +
                    ". Loại ngoại lệ đã được chuẩn hóa từ toàn bộ chuỗi lỗi; hãy gửi đúng mã này để kiểm tra tiếp."
            }
        }
    }

    companion object {
        private val FIREBASE_APP_INITIALIZATION_LOCK = Any()
        private val REMOTE_REQUEST_MUTEX = Mutex()
        private var appCheckProviderInstalled = false

        private const val FIREBASE_APP_NAME = "ngocSiAi"
        private const val QUOTA_PREFERENCES = "ngoc_si_ai_quota_cooldown"
        // Legacy global keys are read once and migrated to the previous default model.
        private const val KEY_QUOTA_COOLDOWN_UNTIL = "cooldown_until_millis"
        private const val KEY_QUOTA_COOLDOWN_KIND = "cooldown_kind"
        private const val EXPECTED_FIREBASE_PROJECT_ID = "ngoc-si-music-ai"
        // Primary model routing is deterministic in NgocSiAiRemotePolicy.
        // Quota errors trigger a cooldown; there is no automatic model fallback.
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
            "search_and_play_youtube",
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
                search_online_music, search_drive, open_youtube, search_youtube, search_and_play_youtube,
                youtube_play, youtube_pause,
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
        private const val VIDEO_ANALYSIS_SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý phân tích âm thanh và hình ảnh video bằng tiếng Việt. " +
                "Phân tích trực tiếp video được đính kèm, bao gồm khung hình và âm thanh khi dữ liệu thực sự được cung cấp. " +
                "Tách rõ quan sát hình ảnh, dấu hiệu nghe được và phần diễn giải; không biến tiêu đề, tên kênh hoặc metadata thành bằng chứng đã quan sát. " +
                "Chỉ nêu lời ca, nhạc cụ, nhịp, màu giọng hoặc cách hát khi có dấu hiệu âm thanh đủ rõ. " +
                "Nếu video hoặc âm thanh không tải được hay không thể xác minh, nói chính xác giới hạn đó và không suy đoán. " +
                "Nêu mốc thời gian khi có thể; khung hình có thể được lấy mẫu khoảng 1 lần mỗi giây nên không khẳng định chi tiết của chuyển động nhanh. " +
                "Trả lời ngắn gọn nhưng đủ căn cứ, không tiết lộ chuỗi suy luận nội bộ. "

        private const val SYSTEM_INSTRUCTIONS =
            "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt gần gũi, bình tĩnh và biết lắng nghe; đừng giả vờ là người thật. " +
            "Hiểu ý định từ ngữ cảnh, cách nói đời thường, lỗi nhận dạng và các đại từ như 'nó', 'bài vừa rồi'; tiếp nối đúng mạch, không chào lại hay nhắc lại câu người dùng ở mỗi lượt. " +
            "Giữ nhất quán các lựa chọn và mục tiêu đã nêu; khi người dùng nói 'tiếp tục' hoặc dùng đại từ, hãy dựa vào lịch sử gần nhất; nếu vẫn có nhiều cách hiểu quan trọng, hỏi một câu làm rõ ngắn. " +
            "Với lệnh có đại từ như 'nó', 'bài đó', 'video đó', 'vừa tìm' hoặc 'tương tự', chỉ liên kết với media hoặc nguồn được nhắc trực tiếp trong câu trả lời ngay trước đó; không lấy một nội dung cũ xa hơn để đoán. Nếu câu trả lời ngay trước không xác định được bài, video, đài, tệp hoặc playlist cụ thể, hoặc còn nhiều kết quả có thể được nhắc tới, hãy hỏi ngắn gọn để làm rõ thay vì tự chọn. 'Tìm bài tương tự' phải giữ nguồn gần nhất đã xác nhận và chỉ tìm kiếm, không tự phát; 'phát nó' phải giữ đúng nguồn của media gần nhất, không chuyển YouTube sang Media3 hoặc ngược lại. " +
            "Với yêu cầu nhiều bước, chia thành trình tự tối đa 4 bước; mỗi lượt chỉ thực hiện một hành động được phép. Chỉ tiếp tục bước phụ thuộc khi app_feedback xác nhận thao tác trước đã hoàn tất. Nếu phản hồi chỉ nói đang mở/tìm/nạp/tải/chuyển/phát/thêm/lưu/tua/đặt hoặc đã gửi lệnh/đã yêu cầu, hãy dừng chuỗi, nói đúng rằng thao tác mới được khởi động hoặc gửi đi và chờ xác nhận tiếp theo; không gửi ngay intent thứ hai vì điều hướng có thể chưa hoàn tất. Nếu bước trước thất bại, dừng hoặc hỏi lại thay vì đoán. Không báo đã phát, chuyển bài hoặc lưu thành công nếu chưa có xác nhận tương ứng. " +
            "Trả lời tự nhiên, chân thành và bám sát câu hỏi. Câu hỏi đơn giản thường chỉ cần 1–4 câu; không kéo dài khi không cần thiết. " +
            "Với nhiệm vụ phức tạp, không ép câu trả lời vào 1–4 câu: trình bày đủ sâu theo cấu trúc phù hợp gồm kết luận, lý do, giả định hoặc điểm chưa chắc, các bước ưu tiên, rủi ro và cách kiểm chứng. " +
            "Phân tích cẩn thận nhưng không tiết lộ chuỗi suy luận nội bộ; chỉ trình bày kết luận và phần giải thích có ích, có thể kiểm chứng. Nếu thiếu dữ kiện quan trọng, nêu giả định hoặc hỏi một câu làm rõ thay vì tự bịa. " +
            "Văn bản có thể đọc thành tiếng: dùng câu ngắn, dấu câu tự nhiên; tránh bảng Markdown, dấu sao, code block và ký hiệu trang trí. Với câu trả lời dài, dùng tiêu đề ngắn và các bước đánh số dạng văn bản thuần; không lặp tên 'Ngọc Sĩ' ở mọi câu. " +
            "Hướng dẫn Android theo các bước dễ làm trên điện thoại. Khi người dùng yêu cầu thao tác trong NGỌC SĨ MUSIC mà câu nói chưa được bộ lệnh cục bộ nhận diện, hãy dùng execute_app_command với action trong danh sách cho phép. " +
            "Chỉ dùng công cụ cho yêu cầu hành động rõ ràng, không dùng cho câu hỏi, gợi ý hoặc trò chuyện thông thường. Hỏi lại khi thiếu tên bài, từ khóa, đài, kênh, playlist hoặc tốc độ phát cần thiết. " +
            "Với thao tác thêm một bài cụ thể vào hàng đợi, dùng add_track_to_queue và query là tên bài. " +
            "Với playlist, phân biệt rõ: tạo playlist dùng create_playlist; phát playlist dùng play_playlist; thêm bài đang phát, bài này hoặc bài đang nghe vào playlist dùng add_current_to_playlist; video YouTube đang phát/video YouTube này cũng dùng add_current_to_playlist; thêm tất cả/toàn bộ hàng đợi Media3 hoặc hàng đợi YouTube vào playlist dùng add_queue_to_playlist. Query của các action playlist là tên playlist đích. Không đổi yêu cầu thêm vào playlist thành thêm vào hàng đợi. " +
            "Với tìm kiếm Drive, dùng search_drive và query là từ khóa. " +
            "Khi người dùng muốn tăng/giảm âm lượng hoặc tắt/bật tiếng, dùng volume_up, volume_down, mute hoặc unmute; đây là âm lượng media hệ thống Android và dùng chung cho YouTube, nhạc máy, Drive và Radio. " +
            "Khi người dùng vừa yêu cầu tìm vừa phát YouTube trong cùng một câu, bắt buộc dùng một action search_and_play_youtube với query là từ khóa tìm kiếm; action này tự phát kết quả đầu tiên khi tìm kiếm thành công. Không tách thành search_youtube rồi youtube_play, vì youtube_play chỉ tiếp tục video đã lưu trước đó. Tìm kiếm đơn thuần vẫn dùng search_youtube. " +
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

internal enum class NgocSiAiAgentFeedbackState {
    FAILED,
    PENDING,
    COMPLETED
}

/**
 * Classifies the app's real feedback before the agent is allowed to execute
 * dependent tool calls. A recognized command is not automatically a success.
 */
internal fun classifyNgocSiAiAgentFeedback(
    routed: Boolean,
    feedback: String,
    action: String? = null
): NgocSiAiAgentFeedbackState {
    if (!routed) return NgocSiAiAgentFeedbackState.FAILED
    val normalized = normalizeNgocSiAiVoicePhrase(feedback)
    val failureMarkers = listOf(
        "chua thuc hien", "khong tim thay", "khong tim duoc",
        "hang doi dang trong", "hang doi rong",
        "khong co bai", "chua co ", "khong co kenh", "khong co dai",
        "khong co quyen", "gap loi", "that bai", "khong the", "chua ket noi",
        "chua dang nhap", "can dang nhap", "chua cap quyen", "chua co quyen",
        "khong ho tro", "khong hop le", "thieu thong tin", "hay noi",
        "hay chon", "chua mo duoc", "khong mo duoc", "khong phat duoc",
        "khong gui duoc", "khong ap dung", "hay dung dieu khien rieng",
        "khong nhan dien duoc", "khong hoat dong"
    )
    if (failureMarkers.any(normalized::contains)) {
        return NgocSiAiAgentFeedbackState.FAILED
    }

    // current_track is read-only. Its "Đang phát" status is a completed lookup.
    if (action == "current_track") return NgocSiAiAgentFeedbackState.COMPLETED

    // Opening a destination dispatches a navigation Intent synchronously. A returned
    // "Đang mở…" message therefore confirms that the navigation request was accepted.
    // Keep actual content loading and playback actions pending until they finish.
    val immediateNavigationActions = setOf(
        "open_home", "open_library", "open_favorites", "open_albums", "open_folders",
        "open_online", "open_youtube", "open_drive", "open_radio", "open_tv",
        "open_playlists", "open_queue", "open_player", "open_settings", "open_sleep_timer"
    )
    if (action != null && action in immediateNavigationActions && normalized.startsWith("dang mo ")) {
        return NgocSiAiAgentFeedbackState.COMPLETED
    }

    // Dispatch/ongoing messages are pending, not completion acknowledgements.
    val pendingMarkers = listOf(
        "dang mo ", "dang tim ", "dang nap ", "dang tai ", "dang chuyen ",
        "dang phat ", "dang them ", "dang luu ", "dang tua ", "dang tam dung ",
        "dang kiem tra ", "dang ket noi ", "dang xu ly ", "dang dat ",
        "da gui lenh", "da yeu cau"
    )
    if (pendingMarkers.any(normalized::contains)) {
        return NgocSiAiAgentFeedbackState.PENDING
    }

    // Only explicit completion language may authorize a dependent tool call.
    // Unknown feedback fails closed as pending rather than being assumed successful.
    val completionMarkers = listOf(
        "da mo ", "da bat ", "da tat ", "da tua ", "da dung ", "da tam dung ",
        "da quay lai ", "da dat ", "da tang ", "da giam ", "da doi ",
        "da chuyen ", "da xoa ", "da them ", "da luu ", "da huy ",
        "da cap nhat ", "da chon ", "da phat ", "da tim thay ",
        "da phat ngau nhien", "da bat lap", "da phat lai"
    )
    return if (completionMarkers.any(normalized::contains)) {
        NgocSiAiAgentFeedbackState.COMPLETED
    } else {
        NgocSiAiAgentFeedbackState.PENDING
    }
}

internal fun ngocSiAiAgentStopFeedback(
    state: NgocSiAiAgentFeedbackState,
    feedback: String
): String? {
    val safeFeedback = feedback.take(900).ifBlank {
        "Ứng dụng chưa cung cấp trạng thái thao tác."
    }
    return when (state) {
        NgocSiAiAgentFeedbackState.FAILED ->
            "Không thể tiếp tục chuỗi lệnh vì thao tác chưa thành công: $safeFeedback"
        NgocSiAiAgentFeedbackState.PENDING ->
            "Ứng dụng chưa xác nhận bước vừa rồi hoàn tất; tôi đã dừng các bước phụ thuộc. Trạng thái hiện tại: $safeFeedback"
        NgocSiAiAgentFeedbackState.COMPLETED -> null
    }
}

internal fun shouldContinueNgocSiAiAgentSequence(
    routed: Boolean,
    feedback: String,
    action: String? = null
): Boolean = classifyNgocSiAiAgentFeedback(routed, feedback, action) ==
    NgocSiAiAgentFeedbackState.COMPLETED

internal class NgocSiAiRemoteException(message: String) : Exception(message)

package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel

/**
 * Firebase AI Logic can wrap a meaningful provider exception in a short or obfuscated
 * transport class. Prefer the SDK's documented exception types over one-letter wrappers.
 */
internal fun ngocSiAiRemoteDiagnosticExceptionType(classNames: List<String>): String {
    val simpleNames = classNames.map {
        it.substringAfterLast('.').substringAfterLast(36.toChar())
    }
    val preferredTypes = listOf(
        "QuotaExceededException",
        "ServerException",
        "RequestTimeoutException",
        "InvalidAPIKeyException",
        "ServiceDisabledException",
        "APINotConfiguredException",
        "PermissionMissingException",
        "ContentBlockedException",
        "PromptBlockedException",
        "ServiceConnectionHandshakeFailedException",
        "SerializationException",
        "UnknownException",
        "FirebaseAIException"
    )
    val selected = preferredTypes.firstOrNull { preferred ->
        simpleNames.any { it.equals(preferred, ignoreCase = true) }
    } ?: simpleNames.firstOrNull {
        it.length > 1 && it !in setOf("Exception", "RuntimeException", "Throwable", "Object")
    } ?: "UnknownException"
    return selected.filter { it.isLetterOrDigit() }.take(48).ifBlank { "UnknownException" }
}

/**
 * Recover an HTTP status from a Firebase ServerException message when the SDK's
 * public exception wrapper has preserved the raw Google error payload. The SDK
 * does not expose the HTTP status as a property, so only explicit status fields
 * are accepted; unrelated numbers in prompts or stack traces are ignored.
 */
internal fun extractNgocSiAiRemoteHttpStatusCode(details: String): String? {
    val supportedCodes = "400|401|403|404|408|413|415|422|429|500|501|502|503|504"
    val explicitHttp = Regex(
        """(?i)\bHTTP(?:/\d(?:\.\d)?)?\s+($supportedCodes)\b"""
    ).find(details)?.groupValues?.getOrNull(1)
    if (explicitHttp != null) return explicitHttp

    val explicitStatus = Regex(
        """(?i)\bstatus(?:\s+code)?\s*[:=]\s*($supportedCodes)\b"""
    ).find(details)?.groupValues?.getOrNull(1)
    if (explicitStatus != null) return explicitStatus

    val structuredCode = Regex(
        """(?i)["'](?:code|httpCode|http_status)["']\s*:\s*["']?($supportedCodes)["']?"""
    ).find(details)?.groupValues?.getOrNull(1)
    if (structuredCode != null) return structuredCode

    val grpcStatus = Regex(
        """(?i)["']status["']\s*:\s*["'](INVALID_ARGUMENT|UNAUTHENTICATED|PERMISSION_DENIED|NOT_FOUND|RESOURCE_EXHAUSTED|DEADLINE_EXCEEDED|UNAVAILABLE|INTERNAL|FAILED_PRECONDITION)["']"""
    ).find(details)?.groupValues?.getOrNull(1)?.uppercase()
    return when (grpcStatus) {
        "INVALID_ARGUMENT", "FAILED_PRECONDITION" -> "400"
        "UNAUTHENTICATED" -> "401"
        "PERMISSION_DENIED" -> "403"
        "NOT_FOUND" -> "404"
        "RESOURCE_EXHAUSTED" -> "429"
        "DEADLINE_EXCEEDED" -> "504"
        "UNAVAILABLE" -> "503"
        "INTERNAL" -> "500"
        else -> null
    }
}

/** Ordinary questions should fail fast; longer reasoning receives a separate bounded window. */
internal const val NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 25_000L
internal const val NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 40_000L
internal const val NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 60_000L
internal const val NGOC_SI_AI_VIDEO_ANALYSIS_TIMEOUT_MILLIS = 120_000L

/**
 * Public Google-hosted MP4 used only when the user explicitly asks for a video-path
 * diagnostic. It isolates Firebase/model/media ingestion from the active YouTube URL.
 */
internal const val NGOC_SI_AI_VIDEO_DIAGNOSTIC_SAMPLE_URI =
    "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

// Gemini's YouTube URL input is a provider-resolved video, not a direct .mp4 asset.
// Keep its MIME separate from inline MP4 bytes used by the public sample diagnostic.
internal const val NGOC_SI_AI_YOUTUBE_URL_MIME_TYPE = "video/mp4"

/**
 * Convert the MP4 canary's transport failure into a safe, actionable diagnostic.
 * Do not expose raw URLs, proxy details, certificates, or arbitrary exception text.
 */
internal fun describeNgocSiAiVideoSampleDownloadFailure(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val details = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    val httpStatus = Regex("""(?i)\bHTTP\s+([1-5]\d{2})\b""")
        .find(details)
        ?.groupValues
        ?.getOrNull(1)

    val tooLarge = details.contains("video sample exceeds inline request limit")
    val emptyResponse = details.contains("empty video sample") ||
        details.contains("missing video body")
    val timedOut = chain.any { it is java.net.SocketTimeoutException } ||
        details.contains("timed out") || details.contains("timeout")
    val unknownHost = chain.any { it is java.net.UnknownHostException }
    val tlsFailure = chain.any { it is javax.net.ssl.SSLException }
    val connectionFailure = chain.any { it is java.net.ConnectException }

    val diagnosticCode = when {
        httpStatus != null -> "VIDEO_SAMPLE_HTTP_$httpStatus"
        tooLarge -> "VIDEO_SAMPLE_TOO_LARGE"
        emptyResponse -> "VIDEO_SAMPLE_EMPTY_RESPONSE"
        timedOut -> "VIDEO_SAMPLE_TIMEOUT"
        unknownHost -> "VIDEO_SAMPLE_DNS"
        tlsFailure -> "VIDEO_SAMPLE_TLS"
        connectionFailure -> "VIDEO_SAMPLE_CONNECTION"
        chain.any { it is java.io.IOException } -> "VIDEO_SAMPLE_IO"
        else -> "VIDEO_SAMPLE_DOWNLOAD_ERROR"
    }
    val reason = when {
        httpStatus != null -> when (httpStatus) {
            "403" -> "máy chủ mẫu từ chối quyền đọc (HTTP 403)."
            "404" -> "không tìm thấy tệp mẫu (HTTP 404)."
            "429" -> "máy chủ mẫu đang giới hạn lượt tải (HTTP 429)."
            else -> "máy chủ mẫu trả về HTTP $httpStatus."
        }
        tooLarge -> "tệp mẫu vượt giới hạn 14 MiB của trình kiểm tra."
        emptyResponse -> "máy chủ không trả về dữ liệu video hợp lệ."
        timedOut -> "kết nối tải video đã quá thời gian chờ."
        unknownHost -> "điện thoại không phân giải được tên miền máy chủ mẫu (kiểm tra DNS/Private DNS)."
        tlsFailure -> "kết nối HTTPS gặp lỗi chứng chỉ hoặc TLS."
        connectionFailure -> "điện thoại không mở được kết nối tới máy chủ mẫu."
        chain.any { it is java.io.IOException } -> "có lỗi khi đọc dữ liệu từ máy chủ mẫu."
        else -> "đã xảy ra lỗi tải video chưa xác định."
    }
    return "Không tải được video MP4 mẫu: $reason Mã chẩn đoán: $diagnosticCode."
}

internal fun isNgocSiAiVideoCapabilityDiagnosticRequest(prompt: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val asksForTest = listOf(
        "kiem tra ket noi", "kiem tra kha nang", "kiem tra duong truyen",
        "chan doan video", "thu video mau"
    ).any(normalized::contains)
    val mentionsSample = listOf("video mau", "video thu", "video kiem tra")
        .any(normalized::contains)
    return asksForTest && mentionsSample
}

internal fun buildNgocSiAiVideoCapabilityDiagnosticPrompt(userPrompt: String): String =
    "Đây là phép thử kỹ thuật với một video MP4 mẫu, không phải video YouTube đang phát. " +
        "Hãy xác nhận bạn thực sự đọc được video bằng một câu ngắn mô tả cảnh nhìn thấy. " +
        "Chỉ nêu âm thanh nếu đã thực sự nghe và xác minh; không suy đoán. " +
        "Bắt đầu bằng 'Đã nhận video mẫu' chỉ khi nội dung video đã được đọc. " +
        "Yêu cầu: " + userPrompt.take(160)


/** Retry only a safe, pre-output text request after a transient transport/server failure. */
internal const val NGOC_SI_AI_MAX_TRANSIENT_REMOTE_RETRIES = 1
internal const val NGOC_SI_AI_TRANSIENT_RETRY_DELAY_MILLIS = 450L

internal fun shouldRetryNgocSiAiRemoteRequest(
    errorDetails: String,
    isToolRequest: Boolean,
    hasEmittedPartialResponse: Boolean,
    attempt: Int
): Boolean {
    if (
        isToolRequest ||
        hasEmittedPartialResponse ||
        attempt >= NGOC_SI_AI_MAX_TRANSIENT_REMOTE_RETRIES
    ) return false

    val details = errorDetails.lowercase()
    // Never repeat quota, authentication, permission, model, or malformed-request errors.
    if (Regex("\\b(400|401|403|404|429)\\b").containsMatchIn(details)) return false
    val permanentMarkers = listOf(
        "resource_exhausted", "quota", "rate limit", "permission denied",
        "unauthenticated", "invalid argument", "model not found", "api key"
    )
    if (permanentMarkers.any(details::contains)) return false

    if (Regex("\\b(408|500|502|503|504)\\b").containsMatchIn(details)) return true
    val transientMarkers = listOf(
        // Firebase's ServerException is returned for remote server failures when the
        // SDK wrapper omits the HTTP status from its public exception message.
        "serverexception",
        "sockettimeout", "socket timeout", "read timed out",
        "connection reset", "connection closed", "eofexception",
        "temporarily unavailable", "service unavailable",
        "internal server error", "deadline exceeded"
    )
    return transientMarkers.any(details::contains)
}

/** Model routing keeps quick LOW-intent requests on Flash-Lite and sends MEDIUM/deep work to Flash. */
internal const val NGOC_SI_AI_FAST_CHAT_MODEL_NAME = "gemini-3.5-flash-lite"
internal const val NGOC_SI_AI_REASONING_MODEL_NAME = "gemini-3.8-flash"

/**
 * Flash-Lite handles LOW-intent chat, knowledge questions, short follow-ups, and
 * single app actions. MEDIUM/HIGH reasoning tasks use Gemini Flash.
 * This selects a primary model by intent; it is not a quota/error fallback.
 */

internal fun isNgocSiAiKnowledgeQuestion(prompt: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt).trim()
    if (normalized.isBlank()) return false
    val markers = listOf(
        " la gi", " la ai", " la bao nhieu", " o dau", " khi nao",
        " vi sao", " tai sao", " nhu the nao", " the nao", " lam sao",
        " co nghia gi", " nghia la gi", " bao nhieu", " ai la "
    )
    val padded = " $normalized "
    val containsInterrogativeWord = listOf(" nao ", " gi ").any(padded::contains)
    return markers.any(padded::contains) || containsInterrogativeWord
}

/**
 * Short, low-intent continuations rely on preceding turns even without an explicit
 * question word and stay on Flash-Lite. Analytical follow-ups are classified MEDIUM
 * separately and use Gemini Flash.
 */
internal fun isNgocSiAiContextualFollowUp(prompt: String): Boolean {
    val normalized = stripNgocSiAiDirectIntentWakePrefix(
        normalizeNgocSiAiVoicePhrase(prompt)
    ).removePrefix("hay ").trim()
    if (normalized.isBlank()) return false
    val exactPhrases = setOf(
        "tiep tuc", "tiep nua", "lam tiep", "lam tiep di",
        "tiep tuc phan truoc", "minh tiep tuc phan truoc",
        "noi ro hon", "noi ro hon di", "giai thich them",
        "cu the hon", "vi du cu the", "cho vi du",
        "cho minh vi du", "cho toi vi du",
        "cho toi mot vi du", "cho minh mot vi du",
        "phan tich ky hon"
    )
    val prefixes = listOf(
        "minh tiep tuc phan truoc ", "tiep tuc phan truoc ",
        "noi ro hon ", "giai thich them ", "cu the hon ",
        "phan tich ky ",
        "vi du ", "cho vi du ", "noi them ve ",
        "phan tich them ", "giai thich chi tiet ",
        "mo rong phan ", "y nay ", "y do ", "phan nay ",
        "phan do ", "what about ", "explain "
    )
    return normalized in exactPhrases || prefixes.any(normalized::startsWith)
}

@Suppress("UNUSED_PARAMETER")
internal fun selectNgocSiAiModelName(
    prompt: String,
    offersAppCommandTool: Boolean = false,
    forceMultimodalReasoning: Boolean = false
): String = if (isNgocSiAiVideoCapabilityDiagnosticRequest(prompt)) {
    // A connectivity canary is not a reasoning task: use the supported multimodal
    // Flash-Lite path so the diagnostic does not wait for unnecessary MEDIUM thinking.
    NGOC_SI_AI_FAST_CHAT_MODEL_NAME
} else if (forceMultimodalReasoning && !isNgocSiAiQuickVideoOverviewRequest(prompt)) {
    NGOC_SI_AI_REASONING_MODEL_NAME
} else if (forceMultimodalReasoning) {
    // The broad first-pass overview uses multimodal Flash-Lite for lower latency.
    // Explicit evidence, vocal, comparison and deep requests stay on Gemini Flash.
    NGOC_SI_AI_FAST_CHAT_MODEL_NAME
} else {
    when (selectNgocSiAiThinkingLevel(prompt)) {
        ThinkingLevel.MEDIUM, ThinkingLevel.HIGH -> NGOC_SI_AI_REASONING_MODEL_NAME
        else -> NGOC_SI_AI_FAST_CHAT_MODEL_NAME
    }
}

/**
 * Flash-Lite accepts only MINIMAL thinking. Gemini Flash accepts LOW/MEDIUM/HIGH;
 * medium tasks use MEDIUM, while high-intent tasks default to MEDIUM unless the
 * prompt explicitly requests maximum reasoning effort.
 */
internal fun selectNgocSiAiGenerationThinkingLevel(
    prompt: String,
    offersAppCommandTool: Boolean = false,
    modelName: String = selectNgocSiAiModelName(prompt, offersAppCommandTool),
    forceMultimodalReasoning: Boolean = false
): ThinkingLevel {
    val requestedLevel = selectNgocSiAiThinkingLevel(prompt)
    return when {
        modelName == NGOC_SI_AI_FAST_CHAT_MODEL_NAME -> ThinkingLevel.MINIMAL
        modelName == NGOC_SI_AI_REASONING_MODEL_NAME &&
            forceMultimodalReasoning &&
            requestedLevel != ThinkingLevel.HIGH -> ThinkingLevel.MEDIUM
        modelName == NGOC_SI_AI_REASONING_MODEL_NAME &&
            requestedLevel == ThinkingLevel.HIGH &&
            !requiresMaximumNgocSiAiThinking(prompt) -> ThinkingLevel.MEDIUM
        else -> requestedLevel
    }
}

/** Maximum thinking is opt-in; generic "deep" or "finish the task" wording uses MEDIUM. */
internal fun requiresMaximumNgocSiAiThinking(prompt: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val explicitMaximumMarkers = listOf(
        "suy nghi that sau",
        "suy nghi toi da",
        "suy luan toi da",
        "muc suy luan cao nhat",
        "phan tich o muc toi da",
        "bai toan rat phuc tap",
        "chung minh toan hoc phuc tap",
        "maximum reasoning",
        "deepest reasoning"
    )
    return explicitMaximumMarkers.any(normalized::contains)
}

internal const val NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS = 384
internal const val NGOC_SI_AI_VIDEO_DIAGNOSTIC_MAX_OUTPUT_TOKENS = 128
internal const val NGOC_SI_AI_VIDEO_OVERVIEW_MAX_OUTPUT_TOKENS = 768
internal const val NGOC_SI_AI_KNOWLEDGE_REPLY_MAX_OUTPUT_TOKENS = 768
internal const val NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS = 1_024
internal const val NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS = 2_048

internal fun isNgocSiAiMultimodalVideoAnalysisRequest(prompt: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val analysisIntent = listOf(
        "phan tich", "mo ta", "so sanh", "cach hat", "chat giong",
        "giong hat", "loi bai hat", "cam xuc", "canh quay", "noi dung video",
        "dau hieu nghe duoc", "nghe duoc trong ban thu", "nhung gi nghe duoc",
        "dieu gi nghe duoc", "phan biet dieu gi chua the xac minh",
        "chua the xac minh"
    ).any(normalized::contains)
    val mediaContext = listOf(
        "video", "youtube", "mv", "bai hat", "ca khuc", "ca si",
        "giong hat", "ban thu", "loi bai hat"
    ).any(normalized::contains)
    return analysisIntent && mediaContext
}

/**
 * A broad first-pass request can use multimodal Flash-Lite. Requests that ask for
 * audible evidence, vocal technique, comparisons, timestamps or deeper analysis
 * remain on Gemini Flash to preserve the stronger reasoning path.
 */
internal fun isNgocSiAiQuickVideoOverviewRequest(prompt: String): Boolean {
    if (!isNgocSiAiMultimodalVideoAnalysisRequest(prompt)) return false
    val normalized = stripNgocSiAiDirectIntentWakePrefix(
        normalizeNgocSiAiVoicePhrase(prompt)
    )
    val detailedEvidenceMarkers = listOf(
        "phan tich ky", "phan tich sau", "phan tich chuyen sau",
        "suy nghi that sau", "suy nghi that ky", "suy nghi toi da",
        "suy luan toi da", "muc suy luan cao nhat", "phan tich o muc toi da",
        "maximum reasoning", "deepest reasoning", "kiem tra toan dien",
        "dau hieu nghe duoc", "nghe duoc", "ban thu",
        "cach hat", "chat giong", "giong hat", "loi bai hat",
        "so sanh", "cam xuc", "canh quay", "hinh anh",
        "moc thoi gian", "timestamp", "chi tiet", "chi ra"
    )
    return detailedEvidenceMarkers.none(normalized::contains)
}

/** Video ingestion may take longer; extend only its deadline, not its reasoning tier. */
internal fun selectNgocSiAiRemoteTimeoutMillis(
    prompt: String,
    forceMultimodalReasoning: Boolean = false
): Long {
    val normalBudget = when (selectNgocSiAiThinkingLevel(prompt)) {
        ThinkingLevel.HIGH -> NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS
        ThinkingLevel.MEDIUM -> NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS
        else -> NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS
    }
    return if (forceMultimodalReasoning || isNgocSiAiMultimodalVideoAnalysisRequest(prompt)) {
        maxOf(normalBudget, NGOC_SI_AI_VIDEO_ANALYSIS_TIMEOUT_MILLIS)
    } else {
        normalBudget
    }
}

internal fun ngocSiAiRemoteTimeoutMessage(
    prompt: String,
    forceMultimodalReasoning: Boolean = false,
    hasEmittedPartialResponse: Boolean = false
): String {
    val isVideoAnalysis =
        forceMultimodalReasoning || isNgocSiAiMultimodalVideoAnalysisRequest(prompt)
    val seconds = selectNgocSiAiRemoteTimeoutMillis(
        prompt,
        forceMultimodalReasoning = forceMultimodalReasoning
    ) / 1_000L
    return if (isVideoAnalysis) {
        val stageMessage = if (hasEmittedPartialResponse) {
            "Gemini đã gửi một phần câu trả lời nhưng chưa hoàn tất."
        } else {
            "Ứng dụng chưa nhận được token trả lời đầu tiên; chưa xác minh được Gemini đã đọc xong video hay chưa."
        }
        "Gemini chưa hoàn tất phân tích video sau $seconds giây. Mã: VIDEO_ANALYSIS_TIMEOUT. " +
            "$stageMessage Chưa đủ bằng chứng để kết luận lỗi quota hay mạng; video YouTube hiện tại vẫn có thể tiếp tục phát."
    } else {
        "Máy chủ Gemini chưa hoàn tất câu trả lời sau $seconds giây. " +
            "Hãy thử câu hỏi ngắn hơn hoặc kiểm tra Internet; các lệnh điều khiển trên điện thoại vẫn hoạt động."
    }
}

internal const val NGOC_SI_AI_VIDEO_HISTORY_TURNS = 3
internal const val NGOC_SI_AI_VIDEO_HISTORY_MESSAGE_MAX_CHARS = 420
internal const val NGOC_SI_AI_VIDEO_USER_PROMPT_MAX_CHARS = 1_200
internal const val NGOC_SI_AI_VIDEO_METADATA_MAX_CHARS = 400

/**
 * Keeps video analysis requests compact: use only a few relevant turns, discard
 * previous failure notices, and keep player metadata clearly separate from evidence.
 */
internal fun buildNgocSiAiVideoAnalysisRequestText(
    userPrompt: String,
    recentMessages: List<NgocSiAiMessage>,
    mediaContextInstruction: String? = null
): String {
    val compactHistory = recentMessages
        .filterNot { message ->
            message.content.contains("VIDEO_ANALYSIS_TIMEOUT", ignoreCase = true) ||
                (
                    message.role == "assistant" && (
                        message.content.contains("Gemini trực tuyến chưa phân tích được video", ignoreCase = true) ||
                            message.content.contains("Qwen3-0.6B", ignoreCase = true)
                        )
                    )
        }
        .takeLast(NGOC_SI_AI_VIDEO_HISTORY_TURNS)
        .joinToString("\n") { message ->
            val speaker = if (message.role == "assistant") "NGỌC SĨ AI" else "Người dùng"
            "${speaker}: ${message.content.trim().takeLast(NGOC_SI_AI_VIDEO_HISTORY_MESSAGE_MAX_CHARS)}"
        }

    return buildString {
        if (compactHistory.isNotBlank()) {
            append("Ngữ cảnh hội thoại gần đây (chỉ để hiểu câu tiếp diễn, không phải bằng chứng về nội dung video):\n")
            append(compactHistory)
            append("\n\n")
        }
        mediaContextInstruction.orEmpty().trim()
            .take(NGOC_SI_AI_VIDEO_METADATA_MAX_CHARS)
            .takeIf { it.isNotBlank() }
            ?.let {
                append("Metadata từ trình phát (chỉ tham khảo, không dùng làm bằng chứng quan sát): ")
                append(it)
                append("\n\n")
            }
        append("Yêu cầu hiện tại: ")
        append(userPrompt.trim().take(NGOC_SI_AI_VIDEO_USER_PROMPT_MAX_CHARS))
    }
}

/**
 * The newest request, not history length, determines the reasoning budget.
 * Deep markers opt into HIGH only for deliberate, demanding analysis; normal chat
 * and ordinary app commands retain their fast path.
 */
internal fun selectNgocSiAiThinkingLevel(prompt: String): ThinkingLevel {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val highIntentMarkers = listOf(
        "suy nghi that ky", "suy nghi that sau", "phan tich chuyen sau", "phan tich sau",
        "kiem tra toan dien", "kiem tra toan bo", "ra soat toan bo", "danh gia toan dien",
        "tim nguyen nhan goc", "thiet ke kien truc", "lap trinh phuc tap", "ma nguon",
        "kiem thu toan dien", "toi uu chuyen sau", "phan tich rui ro",
        "den khi hoan thanh", "hoan thien den khi"
    )
    if (normalized.length >= 420 || highIntentMarkers.any(normalized::contains)) {
        return ThinkingLevel.HIGH
    }

    val mediumIntentMarkers = listOf(
        "phan tich", "phan tich them", "phan tich ky hon",
        "giai thich chi tiet", "so sanh",
        "lap ke hoach", "chien luoc", "danh gia", "kiem tra ky",
        "tim nguyen nhan", "sua loi", "huong dan tung buoc",
        "bao cao", "tong hop", "lo trinh", "de xuat", "tu van"
    )
    // Multi-action requests need planning, even when the sentence is short.
    // Count distinct app-action categories so a single command such as "mở YouTube"
    // keeps the fast path while "tìm bài rồi phát và hẹn giờ" gets deeper reasoning.
    val appActionCategories = listOf(
        listOf("mo youtube", "mo radio", "mo drive", "mo thu vien", "mo tv", "mo playlist", "mo hang doi"),
        listOf("tim bai", "tim kiem", "tim youtube", "tim drive", "tim trong drive"),
        listOf("phat nhac", "phat bai", "phat video", "tam dung", "tiep theo", "chuyen bai", "tua "),
        listOf("hen gio", "dat hen gio", "huy hen gio")
    )
    val distinctAppActionCount = appActionCategories.count { category ->
        category.any { marker -> normalized.contains(marker) }
    }
    // Evidence-based video/audio questions need the multimodal reasoning path even
    // when the Vietnamese wording does not contain the generic word "phân tích".
    val needsMediumReasoning =
        normalized.length >= 180 ||
            mediumIntentMarkers.any { marker -> normalized.contains(marker) } ||
            isNgocSiAiMultimodalVideoAnalysisRequest(prompt) ||
            distinctAppActionCount >= 2
    return if (needsMediumReasoning) ThinkingLevel.MEDIUM else ThinkingLevel.LOW
}

/** Use a compact token budget for everyday chat and retain capacity for complex requests. */
internal fun selectNgocSiAiMaxOutputTokens(
    prompt: String,
    forceMultimodalReasoning: Boolean = false
): Int = when {
    isNgocSiAiVideoCapabilityDiagnosticRequest(prompt) ->
        NGOC_SI_AI_VIDEO_DIAGNOSTIC_MAX_OUTPUT_TOKENS
    forceMultimodalReasoning && isNgocSiAiQuickVideoOverviewRequest(prompt) ->
        NGOC_SI_AI_VIDEO_OVERVIEW_MAX_OUTPUT_TOKENS
    selectNgocSiAiThinkingLevel(prompt) == ThinkingLevel.HIGH -> NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS
    selectNgocSiAiThinkingLevel(prompt) == ThinkingLevel.MEDIUM || forceMultimodalReasoning ->
        NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS
    isNgocSiAiKnowledgeQuestion(prompt) || isNgocSiAiContextualFollowUp(prompt) ->
        NGOC_SI_AI_KNOWLEDGE_REPLY_MAX_OUTPUT_TOKENS
    else -> NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS
}

/**
 * Attach the large app-command tool schema only when the newest message appears to request
 * an app action. Ordinary knowledge questions can use streamed text generation directly.
 */
private fun isContextualNgocSiAiSearchOnlyFollowUp(normalizedPrompt: String): Boolean {
    var phrase = stripNgocSiAiDirectIntentWakePrefix(normalizedPrompt).removePrefix("hay ").trim()
    val prefix = listOf("tim kiem ", "tim ", "kiem ").firstOrNull(phrase::startsWith) ?: return false
    phrase = phrase.removePrefix(prefix).trim()
    val nonSearchSuffix = Regex(
        """(?:^|\s)(?:roi|va|sau do|then)\s+(?:phat|play)(?:\s|$)|(?:^|\s)(?:phat|play)\s+(?:luon|di)(?:$|\s)"""
    )
    if (nonSearchSuffix.containsMatchIn(normalizedPrompt)) return false
    return phrase in setOf(
        "tuong tu", "giong vay", "giong nhu vay",
        "bai tuong tu", "bai hat tuong tu", "nhac tuong tu", "video tuong tu",
        "bai do", "bai hat do", "nhac do", "video do",
        "bai vua tim", "bai vua roi", "video vua tim", "video vua roi",
        "ket qua vua tim", "ket qua vua roi"
    ) || Regex(
        """^(?:bai hat|bai|nhac|video)\s+(?:tuong tu|giong vay|giong nhu vay)(?:\s+(?:voi|nhu)\s+(?:bai hat|bai|nhac|video)\s+(?:do|ay|vua roi|vua tim))?$"""
    ).matches(phrase)
}

internal fun shouldOfferNgocSiAiAppCommandTool(
    prompt: String,
    previousMessages: List<NgocSiAiMessage> = emptyList()
): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val intentText = stripNgocSiAiDirectIntentWakePrefix(normalized)

    // Accent removal makes "đừng" and "dừng" identical. Reject clear negative
    // navigation/search verbs here, while preserving "đừng phát nhạc nữa" as
    // a possible explicit stop request handled by the command router.
    val negativeImperativePrefixes = listOf(
        "dung mo ", "dung vao ", "dung bat ", "dung tim ",
        "dung chuyen ", "dung tua ", "dung lui ", "dung choi ", "dung tat "
    )
    if (negativeImperativePrefixes.any(intentText::startsWith)) return false

    // Negative preferences are not consent to control the player or navigate.
    val negativeStatementPrefixes = listOf(
        "toi khong muon ", "minh khong muon ", "khong muon ",
        "toi khong can ", "minh khong can ", "khong can ",
        "toi khong thich ", "minh khong thich ",
        "toi khong nghe ", "minh khong nghe "
    )
    if (negativeStatementPrefixes.any(intentText::startsWith)) return false

    // Declarative listening preferences are not app commands. A separate imperative
    // clause ("... rồi tìm bài ...") still exposes the tool for that explicit action.
    val preferenceStatementPrefixes = listOf(
        "toi thich ", "minh thich ", "toi rat thich ", "minh rat thich ",
        "toi hay nghe ", "minh hay nghe ", "toi thuong nghe ", "minh thuong nghe ",
        "toi dang nghe ", "minh dang nghe ", "toi nghe ", "minh nghe "
    )
    if (preferenceStatementPrefixes.any(intentText::startsWith)) {
        val actionClauses = intentText.split(
            Regex("""\s+(?:va|roi|sau do|nhung|hay|then|and)\s+|[,;]\s*""")
        ).drop(1)
        return actionClauses.any(::hasDirectNgocSiAiAppActionIntent)
    }
    // Questions asking for an explanation should never become app actions.
    val informationalPrefixes = listOf(
        "vi sao ", "tai sao ", "lam sao ", "lam the nao ",
        "cach nao ", "ke cho toi nghe ", "cho toi biet ", "la gi ",
        "huong dan ", "chi toi cach ", "noi cho toi cach ",
        "giai thich cach ", "toi muon biet cach ", "cach de "
    )
    // Check the wake-word-stripped phrase. Using the original text here allowed
    // "AI, làm thế nào để mở YouTube?" to expose the action tool accidentally.
    if (informationalPrefixes.any(intentText::startsWith)) return false
    val howToQuestionMarkers = listOf(" nhu the nao", " bang cach nao")
    if (howToQuestionMarkers.any(intentText::contains)) return false



    if (isContextualNgocSiAiSearchOnlyFollowUp(intentText)) {
        return resolveNgocSiAiContextualSearchOnlyAction(intentText, previousMessages) != null
    }
    // A confirmed active YouTube item makes short transport aliases actionable.
    // Resolve this before the generic direct-intent matcher, which needs a longer verb.
    if (resolveNgocSiAiContextualYoutubeTransportAction(prompt, previousMessages) != null) {
        return true
    }
    // A bare "tiếp tục" is a conversational continuation unless the immediately
    // preceding assistant offered one explicit app action or an active YouTube item
    // resolved above. Do not attach the large app-tool schema to an ambiguous follow-up.
    val continuationOnlyPhrases = setOf(
        "tiep tuc", "tiep tuc di", "tiep tuc nhe",
        "tiep nua", "lam tiep", "lam tiep di", "lam tiep nhe",
        "noi tiep", "noi tiep di", "noi tiep nhe"
    )
    if (intentText in continuationOnlyPhrases) {
        return isConfirmedNgocSiAiContextualAction(normalized, previousMessages)
    }
    if (hasDirectNgocSiAiAppActionIntent(intentText)) return true
    if (isConfirmedNgocSiAiContextualAction(normalized, previousMessages)) return true
    return hasContextualNgocSiAiAppActionFollowUp(intentText, previousMessages)
}

/**
 * Resolves a "find a similar / that one" follow-up to a source-specific SEARCH action.
 * This intentionally returns only search actions: a search-only utterance can never
 * be upgraded into autoplay just because the model selected a playback action.
 *
 * The latest assistant answer must name one supported source and one positive media
 * result. Multi-result, cross-source, negative-result, and unsupported-source contexts
 * remain conversational so the assistant can ask for clarification.
 */
internal fun resolveNgocSiAiContextualSearchOnlyAction(
    prompt: String,
    previousMessages: List<NgocSiAiMessage>
): String? {
    if (previousMessages.lastOrNull()?.role != "assistant") return null

    var intent = stripNgocSiAiDirectIntentWakePrefix(normalizeNgocSiAiVoicePhrase(prompt))
    intent = intent.removePrefix("hay ").trim()
    val searchPrefix = listOf("tim kiem ", "tim ", "kiem ")
        .firstOrNull(intent::startsWith) ?: return null
    val target = intent.removePrefix(searchPrefix).trim()
    if (target.isBlank()) return null

    // Explicit "then play" wording is not search-only and must use the dedicated
    // search-and-play flow instead of this source-preserving override.
    val autoplaySuffix = Regex(
        """(?:^|\s)(?:roi|va|sau do|then)\s+(?:phat|play)(?:\s|$)|(?:^|\s)(?:phat|play)\s+(?:luon|di)(?:$|\s)"""
    )
    if (autoplaySuffix.containsMatchIn(intent)) return null

    val referenceTargets = setOf(
        "tuong tu", "giong vay", "giong nhu vay",
        "bai tuong tu", "bai hat tuong tu", "nhac tuong tu", "video tuong tu",
        "bai giong vay", "bai hat giong vay", "nhac giong vay", "video giong vay",
        "bai do", "bai hat do", "nhac do", "video do",
        "bai ay", "bai hat ay", "video ay",
        "bai vua tim", "bai vua roi", "video vua tim", "video vua roi",
        "ket qua vua tim", "ket qua vua roi"
    )
    val matchesReference = target in referenceTargets || Regex(
        """^(?:bai hat|bai|nhac|video)\s+(?:tuong tu|giong vay|giong nhu vay)(?:\s+(?:voi|nhu)\s+(?:bai hat|bai|nhac|video)\s+(?:do|ay|vua roi|vua tim))?$"""
    ).matches(target)
    if (!matchesReference) return null

    val assistant = normalizeNgocSiAiVoicePhrase(previousMessages.last().content)
    val recentContext = previousMessages.takeLast(4)
        .joinToString(" ") { normalizeNgocSiAiVoicePhrase(it.content) }

    val ambiguousMarkers = listOf(
        "nhieu ket qua", "nhieu bai", "nhieu video", "nhieu lua chon",
        "mot so bai", "mot vai bai", "mot so video", "mot vai video",
        "cac bai hat", "cac video", "cac lua chon", "nhung bai hat",
        "nhung video", "danh sach bai", "danh sach video", "ket qua gom"
    )
    val multipleCount = Regex(
        """(?:^|\s)(?:[2-9]|[1-9]\d+|hai|ba|bon|tu|nam|sau|bay|tam|chin|muoi)\s+(?:bai|video|ket qua|lua chon)"""
    )
    if (ambiguousMarkers.any(assistant::contains) || multipleCount.containsMatchIn(assistant)) return null

    val negativeResultMarkers = listOf(
        "khong tim thay", "khong co ket qua", "chua tim thay",
        "khong co bai", "khong co video", "tim kiem that bai", "gap loi khi tim"
    )
    if (negativeResultMarkers.any(assistant::contains)) return null

    val positiveResultMarkers = listOf("tim thay", "da tim", "dang phat", "dang mo", "ket qua la")
    val mediaMarkers = listOf("video", "bai hat", "bai nhac", "nhac", "tep", "file", "playlist")
    if (positiveResultMarkers.none(assistant::contains) ||
        mediaMarkers.none(assistant::contains)
    ) return null

    val families = listOf(
        "youtube" to listOf("youtube"),
        "drive" to listOf("google drive", "drive"),
        "online" to listOf("audius", "jamendo", "online", "truc tuyen", "tren mang")
    )
    val matches = families.filter { (_, markers) ->
        markers.any { marker ->
            Regex("""(?:^|\s)${Regex.escape(marker)}(?:$|\s|\d)""")
                .containsMatchIn(assistant)
        }
    }
    if (matches.size != 1) return null

    // The recent transcript must also be app/media-related; do not bind old topics.
    val recentAppMarkers = listOf("youtube", "drive", "online", "audius", "jamendo", "video", "bai hat", "nhac")
    if (recentAppMarkers.none(recentContext::contains)) return null

    return when (matches.single().first) {
        "youtube" -> "search_youtube"
        "drive" -> "search_drive"
        "online" -> "search_online_music"
        else -> null
    }
}

/**
 * Reject generic reference words as a search query. If Gemini cannot turn the recent
 * item into a concrete title, artist, station or keyword, do not search for "bài tương tự".
 */
internal fun isNgocSiAiContextualSearchQuerySpecificEnough(query: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (normalized.length < 3) return false
    return normalized !in setOf(
        "tuong tu", "giong vay", "giong nhu vay",
        "bai", "bai hat", "bai nhac", "bai tuong tu",
        "bai hat tuong tu", "nhac", "nhac tuong tu", "video",
        "video tuong tu", "bai vua tim", "bai vua roi",
        "video vua tim", "video vua roi", "ket qua vua tim",
        "ket qua vua roi", "tim bai tuong tu", "tim bai hat tuong tu",
        "tim nhac tuong tu", "tim video tuong tu",
        "similar song", "similar songs", "similar music", "similar video",
        "similar videos", "find similar", "find similar songs",
        "same song", "like this"
    )
}

/**
 * Preserve YouTube as the transport source for short follow-ups only when the
 * immediately preceding assistant message confirms a single, currently playing
 * or paused YouTube video. Search results and explanatory mentions are not enough.
 */
internal fun resolveNgocSiAiContextualYoutubeTransportAction(
    prompt: String,
    previousMessages: List<NgocSiAiMessage>
): String? {
    if (previousMessages.lastOrNull()?.role != "assistant") return null

    val intent = stripNgocSiAiDirectIntentWakePrefix(
        normalizeNgocSiAiVoicePhrase(prompt)
    ).removePrefix("hay ").trim()
    if (intent.isBlank() || intent.length > 100) return null
    // Negative phrasing is not permission to issue a playback command. Accent
    // normalization makes "đừng" and "dừng" identical, so reject negative verb
    // combinations rather than every phrase beginning with "dừng".
    // Vietnamese ASR can collapse "dừng" (stop) and "đừng" (do not)
    // into the same unaccented token. Inspect the original transcript first:
    // explicit "đừng ..." is always negative; explicit "dừng phát/chơi" is a
    // valid pause command; unaccented "dung ..." remains ambiguous and is rejected.
    val rawPrompt = prompt.lowercase()
    val explicitNegativePhrase = Regex(
        """(?:^|[\s,])đừng\s+(?:phát|chơi|chuyển)(?:\s|$)"""
    ).containsMatchIn(rawPrompt)
    if (explicitNegativePhrase) return null
    val explicitStopPhrase = Regex(
        """(?:^|[\s,])dừng\s+(?:phát|chơi)(?:\s|$)"""
    ).containsMatchIn(rawPrompt)
    val ambiguousStopPrefixes = listOf("dung phat", "dung choi", "dung chuyen")
    if (
        ambiguousStopPrefixes.any(intent::startsWith) &&
        !explicitStopPhrase
    ) return null

    val negativeImperativePrefixes = listOf(
        "khong ", "dung mo", "dung tim", "dung bat", "dung tua", "dung lui"
    )
    if (negativeImperativePrefixes.any { intent == it.trim() || intent.startsWith(it) }) return null

    val explicitOtherSources = listOf(
        "google drive", "drive", "radio", "vov", "voh", "tv", "vtv",
        "htv", "thvl", "audius", "jamendo", "online", "truc tuyen",
        "trong dien thoai", "tren dien thoai", "thu vien", "media3", "mp3"
    )
    if (explicitOtherSources.any { marker ->
        Regex("""(?:^|\s)${Regex.escape(marker)}(?:$|\s|\d)""")
            .containsMatchIn(intent)
    }) return null

    val assistant = normalizeNgocSiAiVoicePhrase(previousMessages.last().content)
    fun hasToken(marker: String): Boolean =
        Regex("""(?:^|\s)${Regex.escape(marker)}(?:$|\s|\d)""")
            .containsMatchIn(assistant)

    if (!hasToken("youtube") || !hasToken("video")) return null

    // Do not inherit YouTube when the same answer also points to another source.
    val otherSources = listOf(
        "google drive", "drive", "radio", "vov", "voh", "tv", "vtv",
        "htv", "thvl", "audius", "jamendo", "online", "truc tuyen",
        "trong dien thoai", "tren dien thoai", "thu vien", "media3", "mp3"
    )
    if (otherSources.any(::hasToken)) return null

    // A search result is not necessarily the active player item. Only a confirmed
    // playback/pause status authorizes transport-source inheritance.
    val isPlaying = assistant.contains("dang phat")
    val isPaused = assistant.contains("dang tam dung") || assistant.contains("da tam dung")
    if (!isPlaying && !isPaused) return null
    if (assistant.contains("chua phat") || assistant.contains("chua co video")) return null

    val multipleResultMarkers = listOf(
        "nhieu video", "cac video", "mot so video", "mot vai video",
        "danh sach video", "nhung video", "nhieu ket qua"
    )
    val explicitMultipleCount = Regex(
        """(?:^|\s)(?:[2-9]|[1-9]\d+|hai|ba|bon|tu|nam|sau|bay|tam|chin|muoi)\s+video"""
    ).containsMatchIn(assistant)
    if (multipleResultMarkers.any(assistant::contains) || explicitMultipleCount) return null

    val pauseIntent = listOf(
        "tam dung", "tam ngung", "pause", "ngung lai", "ngung video",
        "ngung no", "dung lai", "dung video", "dung phat", "dung choi",
        "dung youtube", "dung no", "stop"
    ).any(intent::contains)
    if (pauseIntent) return "youtube_pause"

    val previousIntent = listOf(
        "bai truoc", "video truoc", "quay lai video", "quay lai bai",
        "tro ve bai truoc", "previous"
    ).any(intent::contains)
    if (previousIntent) return "youtube_previous"

    val nextIntent = listOf(
        "tiep theo", "ke tiep", "chuyen bai", "chuyen video",
        "qua bai", "qua video", "bo qua video", "next", "skip"
    ).any(intent::contains)
    if (nextIntent) return "youtube_next"

    val playIntent = intent in setOf("phat", "choi", "tiep tuc", "resume", "play") ||
        listOf(
            "phat ", "choi ", "nghe tiep", "tiep tuc phat", "tiep tuc", "resume", "play"
        ).any(intent::contains)
    if (playIntent && (isPaused || isPlaying)) return "youtube_play"

    return null
}

private fun hasContextualNgocSiAiAppActionFollowUp(
    normalizedPrompt: String,
    previousMessages: List<NgocSiAiMessage>
): Boolean {
    if (previousMessages.lastOrNull()?.role != "assistant") return false
    val prompt = normalizedPrompt.trim()
    if (prompt.isBlank() || prompt.length > 120) return false

    // Questions about a referenced item remain conversation, not permission to act.
    val informationalPrefixes = listOf(
        "vi sao ", "tai sao ", "lam sao ", "lam the nao ",
        "giai thich ", "tim hieu ", "co nen ", "co phai ",
        "no la gi", "cai do la gi", "bai do la gi"
    )
    if (informationalPrefixes.any(prompt::startsWith) || prompt.endsWith(" thi sao")) {
        return false
    }
    if (
        prompt.endsWith(" khong") &&
        !prompt.startsWith("ban co the ") &&
        !prompt.startsWith("co the ") &&
        !prompt.startsWith("toi muon ") &&
        !prompt.startsWith("minh muon ")
    ) return false

    // Only short, explicit references are resolved from the immediately recent transcript.
    val references = listOf(
        "no", "cai do", "cai nay", "bai do", "bai nay", "bai vua roi",
        "video do", "video nay", "video vua roi", "noi dung do",
        "ket qua do", "lua chon do", "kenh do", "dai do",
        "playlist do", "bai ay", "video ay",
        "bai vua tim", "video vua tim", "ket qua vua tim",
        "ket qua vua roi", "bai tuong tu", "video tuong tu",
        "nhac tuong tu", "giong bai do", "giong vay"
    )
    val paddedPrompt = " $prompt "
    if (references.none { paddedPrompt.contains(" $it ") }) return false

    val actionVerbs = listOf(
        "phat", "choi", "mo", "tim", "them", "luu", "dua",
        "chuyen", "tua", "tam dung", "dung lai", "bat", "tat",
        "nghe", "tiep theo"
    )
    if (actionVerbs.none { paddedPrompt.contains(" $it ") }) return false

    val recentContext = previousMessages.takeLast(4)
        .joinToString(" ") { normalizeNgocSiAiVoicePhrase(it.content) }
    val latestAssistantContext = normalizeNgocSiAiVoicePhrase(
        previousMessages.last().content
    )
    val appContextMarkers = listOf(
        "youtube", "video", "bai hat", "bai nhac", "radio",
        "google drive", "drive", "playlist", "hang doi",
        "kenh", "vov", "voh", "vtv", "htv", "thvl"
    )
    // A follow-up may inherit a source only when the immediately preceding
    // assistant answer identifies exactly one source family. Generic words
    // such as "bài hát" or "video" describe media, not its playback source.
    val sourceFamilies = listOf(
        listOf("youtube"),
        listOf("google drive", "drive"),
        listOf("radio", "vov", "voh"),
        listOf("tv", "vtv", "htv", "thvl", "vtvgo", "htvm"),
        listOf("audius", "jamendo", "online", "truc tuyen"),
        listOf("trong dien thoai", "tren dien thoai", "trong may", "thu vien", "media3", "local", "mp3")
    )
    val identifiedSourceFamilies = sourceFamilies.count { family ->
        family.any { marker ->
            Regex("""(?:^|\s)${Regex.escape(marker)}(?:$|\s|\d)""")
                .containsMatchIn(latestAssistantContext)
        }
    }
    // A short reference is unsafe when the last answer lists multiple candidates,
    // even if every candidate comes from the same source. Require clarification first.
    val ambiguousResultMarkers = listOf(
        "nhieu ket qua", "nhieu bai", "nhieu video", "nhieu lua chon",
        "mot so bai", "mot vai bai", "mot so video", "mot vai video",
        "cac bai hat", "cac video", "cac lua chon", "nhung bai hat",
        "nhung video", "danh sach bai", "danh sach video", "ket qua gom"
    )
    val hasExplicitMultipleCount = Regex(
        """(?:^|\s)(?:[2-9]|[1-9]\d+|hai|ba|bon|tu|nam|sau|bay|tam|chin|muoi)\s+(?:bai|video|ket qua|lua chon)"""
    ).containsMatchIn(latestAssistantContext)
    if (ambiguousResultMarkers.any(latestAssistantContext::contains) || hasExplicitMultipleCount) {
        return false
    }

    // An old user request mentioning YouTube is not enough: the immediately
    // preceding assistant answer must identify one source and one clear candidate.
    return appContextMarkers.any(recentContext::contains) &&
        identifiedSourceFamilies == 1
}

/**
 * Identifies a short confirmation for an immediately preceding, explicit app-action offer.
 * The UI uses this before local small-talk fallbacks so consent is not swallowed offline.
 */
internal fun isNgocSiAiContextualActionConfirmation(
    prompt: String,
    previousMessages: List<NgocSiAiMessage>
): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val informationalPrefixes = listOf(
        "vi sao ", "tai sao ", "lam sao ", "lam the nao ",
        "cach nao ", "ke cho toi nghe ", "cho toi biet ", "la gi "
    )
    if (informationalPrefixes.any { prefix -> normalized.startsWith(prefix) }) return false
    // The confirmation allowlist below is deliberately exact. Do not run the broad
    // direct-intent matcher first: generic confirmations such as "tiếp tục" overlap
    // command markers but are safe only when the prior assistant explicitly asked.
    return isConfirmedNgocSiAiContextualAction(normalized, previousMessages)
}

/**
 * Removes a leading wake word before distinguishing a spoken preference from a command.
 */
internal fun stripNgocSiAiDirectIntentWakePrefix(normalized: String): String {
    val prefixes = listOf(
        "hey ngoc si ai ", "hey ai ", "ok ai ", "alo ai ",
        "ngoc si ai ", "ngoc si al ", "ai oi ", "ai ", "al ", "a l "
    )
    val prefix = prefixes.firstOrNull(normalized::startsWith) ?: return normalized
    return normalized.removePrefix(prefix).trim()
}

/** Deterministic intent gate for explicit app actions; does not use fuzzy matching. */
private fun hasDirectNgocSiAiAppActionIntent(normalized: String): Boolean {
    val explicitActionMarkers = listOf(
        "mo youtube", "mo radio", "mo drive", "mo thu vien", "mo tv", "mo muc",
        "vao youtube", "vao radio", "vao drive", "vao thu vien", "vao tv",
        "chuyen sang youtube", "chuyen sang radio", "chuyen sang drive",
        "chuyen sang tv", "chuyen qua youtube", "chuyen qua radio",
        "bat radio", "bat nhac", "bat video", "bat tieng", "tat tieng",
        "phat nhac", "phat bai", "phat video", "cho phat", "choi nhac", "choi bai",
        "muon nghe", "nghe bai", "nghe nhac", "nghe radio", "nghe youtube",
        "tam dung", "tam ngung", "dung lai", "dung video", "dung nhac",
        "ngung lai", "ngung video", "ngung nhac",
        "tiep theo", "tiep di", "tiep nhe", "tiep tuc",
        "chuyen bai", "qua bai", "bai tiep", "bai truoc", "tua ", "tua lui",
        "lui ", "quay lai", "nhay toi", "tang am luong", "giam am luong",
        "tang tieng", "giam tieng", "to hon", "nho hon", "am luong",
        "tim bai hat", "tim kiem bai hat", "tim youtube", "tim tren youtube",
        "tim drive", "tim google drive", "tim trong drive", "search drive",
        "them bai hat", "them bai", "vao hang doi",
        "them tat ca bai trong hang doi vao playlist",
        "them toan bo bai trong hang doi vao playlist",
        "luu tat ca bai trong hang doi vao playlist",
        "luu toan bo hang doi vao playlist",
        "luu bai nay vao playlist", "them bai nay vao playlist",
        "them bai dang nghe vao playlist", "luu bai dang nghe vao playlist",
        "dat hen gio", "hen gio tat", "huy hen gio", "them vao yeu thich",
        "luu vao yeu thich", "them bai dang phat vao yeu thich",
        "them bai vao yeu thich", "luu bai", "luu video", "phat radio", "mo kenh",
        "mo playlist", "phat playlist", "mo hang doi", "xem hang doi",
        "chon bai", "chon playlist", "youtube play", "youtube pause", "youtube next",
        "youtube previous", "play ", "pause", "stop ", "skip ", "seek ",
        "next", "previous", "resume", "volume", "mute", "unmute", "shuffle", "repeat"
    )
    if (explicitActionMarkers.any { marker -> normalized.contains(marker) }) return true

    // Natural Vietnamese commands often put the verb and target in different positions.
    val actionVerbs = listOf(
        "mo ", "vao ", "chuyen ", "bam ", "bat ", "tat ", "phat ", "choi ",
        "nghe ", "tim ", "chon ", "tang ", "giam ", "them vao", "them bai", "luu ",
        "hen gio", "dieu khien", "kiem soat", "di den", "dua toi", "truy cap"
    )
    val appTargets = listOf(
        "youtube", "video", "drive", "radio", "tv", "thu vien", "playlist",
        "hang doi", "nhac", "bai hat", "trinh phat", "kenh", "am luong", "tieng", "yeu thich"
    )
    return actionVerbs.any { verb -> normalized.contains(verb) } &&
        appTargets.any { target -> normalized.contains(target) }
}

/**
 * Allows a short confirmation to continue only the immediately preceding, explicit
 * yes/no offer to perform an app action. General acknowledgements remain ordinary chat.
 */
private fun isConfirmedNgocSiAiContextualAction(
    normalizedPrompt: String,
    previousMessages: List<NgocSiAiMessage>
): Boolean {
    val wakePrefixes = listOf(
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si ",
        "hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai "
    )
    var confirmation = normalizedPrompt
    val wakePrefix = wakePrefixes.firstOrNull { confirmation.startsWith(it) }
    if (wakePrefix != null) confirmation = confirmation.removePrefix(wakePrefix).trim()

    val confirmations = setOf(
        "lam di", "lam luon di", "thuc hien di", "tien hanh di",
        "dong y", "ok", "oke", "duoc", "duoc roi", "vang", "uh", "u",
        "dung vay", "chinh xac", "tiep tuc",
        "ok lam di", "duoc lam di", "dong y lam di",
        "duoc tien hanh di", "ok tien hanh di"
    )
    if (confirmation !in confirmations) return false

    val previousAssistant = previousMessages.lastOrNull()
        ?.takeIf { it.role == "assistant" }
        ?: return false
    val assistantText = previousAssistant.content
    val assistantNormalized = normalizeNgocSiAiVoicePhrase(assistantText)
    val isQuestion = assistantText.contains("?") ||
        assistantNormalized.endsWith(" khong") ||
        assistantNormalized.endsWith(" chu") ||
        assistantNormalized.endsWith(" nhe")
    if (!isQuestion) return false

    val asksForConfirmation = listOf(
        "ban muon minh", "ban co muon minh", "minh co the",
        "hay de minh", "ban xac nhan", "ban dong y"
    ).any { assistantNormalized.contains(it) }
    if (!asksForConfirmation) return false

    // Do not treat consent to receive an explanation as consent to change app state.
    val explanationOfferMarkers = listOf(
        "huong dan", "giai thich", "chi cach", "noi ro", "cach mo",
        "cach bat", "cach dung", "cach tim"
    )
    if (explanationOfferMarkers.any { marker -> assistantNormalized.contains(marker) }) return false

    val assistantOffersAction = hasDirectNgocSiAiAppActionIntent(assistantNormalized)
    val previousUserRequestedAction = previousMessages.dropLast(1)
        .lastOrNull { it.role == "user" }
        ?.content
        ?.let { hasDirectNgocSiAiAppActionIntent(normalizeNgocSiAiVoicePhrase(it)) }
        ?: false
    return assistantOffersAction || previousUserRequestedAction
}

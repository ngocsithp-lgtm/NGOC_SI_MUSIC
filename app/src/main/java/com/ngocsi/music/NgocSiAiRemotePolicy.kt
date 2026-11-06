package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel

/** Ordinary questions should fail fast; longer reasoning receives a separate bounded window. */
internal const val NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 25_000L
internal const val NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 40_000L
internal const val NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 60_000L


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
        "sockettimeout", "socket timeout", "read timed out",
        "connection reset", "connection closed", "eofexception",
        "temporarily unavailable", "service unavailable",
        "internal server error", "deadline exceeded"
    )
    return transientMarkers.any(details::contains)
}

/** Model routing keeps everyday chat on the faster, no-billing Flash-Lite tier. */
internal const val NGOC_SI_AI_FAST_CHAT_MODEL_NAME = "gemini-3.5-flash-lite"
internal const val NGOC_SI_AI_REASONING_MODEL_NAME = "gemini-3.8-flash"

/**
 * Use Flash-Lite for ordinary text chat. Keep reasoning and tool-calling requests
 * on Flash so multi-step app actions retain the stronger planning path.
 * This selects a primary model by intent; it is not a quota/error fallback.
 */
internal fun selectNgocSiAiModelName(
    prompt: String,
    offersAppCommandTool: Boolean = false
): String = if (
    offersAppCommandTool || selectNgocSiAiThinkingLevel(prompt) != ThinkingLevel.LOW
) {
    NGOC_SI_AI_REASONING_MODEL_NAME
} else {
    NGOC_SI_AI_FAST_CHAT_MODEL_NAME
}

/**
 * Flash-Lite supports MINIMAL thinking and uses it by default. Keep the 3.8 Flash
 * path at LOW or MEDIUM because that model does not accept MINIMAL.
 */
internal fun selectNgocSiAiGenerationThinkingLevel(
    prompt: String,
    offersAppCommandTool: Boolean = false
): ThinkingLevel {
    val requestedLevel = selectNgocSiAiThinkingLevel(prompt)
    return if (
        !offersAppCommandTool &&
        requestedLevel == ThinkingLevel.LOW
    ) {
        ThinkingLevel.MINIMAL
    } else {
        requestedLevel
    }
}

internal const val NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS = 384
internal const val NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS = 1_024
internal const val NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS = 2_048

internal fun selectNgocSiAiRemoteTimeoutMillis(prompt: String): Long = when (selectNgocSiAiThinkingLevel(prompt)) {
    ThinkingLevel.HIGH -> NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS
    ThinkingLevel.MEDIUM -> NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS
    else -> NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS
}

internal fun ngocSiAiRemoteTimeoutMessage(prompt: String): String {
    val seconds = selectNgocSiAiRemoteTimeoutMillis(prompt) / 1_000L
    return "Máy chủ Gemini chưa hoàn tất câu trả lời sau $seconds giây. " +
        "Hãy thử câu hỏi ngắn hơn hoặc kiểm tra Internet; các lệnh điều khiển trên điện thoại vẫn hoạt động."
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
        "phan tich", "so sanh", "lap ke hoach", "chien luoc", "danh gia",
        "kiem tra ky", "tim nguyen nhan", "sua loi", "huong dan tung buoc",
        "bao cao", "tong hop", "lo trinh", "de xuat", "tu van", "kiem tra ky"
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
    val needsMediumReasoning =
        normalized.length >= 180 ||
            mediumIntentMarkers.any { marker -> normalized.contains(marker) } ||
            distinctAppActionCount >= 2
    return if (needsMediumReasoning) ThinkingLevel.MEDIUM else ThinkingLevel.LOW
}

/** Use a compact token budget for everyday chat and retain capacity for complex requests. */
internal fun selectNgocSiAiMaxOutputTokens(prompt: String): Int = when (selectNgocSiAiThinkingLevel(prompt)) {
    ThinkingLevel.HIGH -> NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS
    ThinkingLevel.MEDIUM -> NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS
    else -> NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS
}

/**
 * Attach the large app-command tool schema only when the newest message appears to request
 * an app action. Ordinary knowledge questions can use streamed text generation directly.
 */
internal fun shouldOfferNgocSiAiAppCommandTool(
    prompt: String,
    previousMessages: List<NgocSiAiMessage> = emptyList()
): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val intentText = stripNgocSiAiDirectIntentWakePrefix(normalized)

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

    if (hasDirectNgocSiAiAppActionIntent(intentText)) return true
    if (isConfirmedNgocSiAiContextualAction(normalized, previousMessages)) return true
    return hasContextualNgocSiAiAppActionFollowUp(intentText, previousMessages)
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
        "playlist do", "bai ay", "video ay"
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
    val appContextMarkers = listOf(
        "youtube", "video", "bai hat", "bai nhac", "nhac",
        "radio", "google drive", "drive", "playlist", "hang doi",
        "kenh", "dai", "trinh phat", "am luong", "hen gio"
    )
    return appContextMarkers.any(recentContext::contains)
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
private fun stripNgocSiAiDirectIntentWakePrefix(normalized: String): String {
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
        "tam dung", "dung nhac", "tiep theo", "tiep di", "tiep nhe", "tiep tuc",
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

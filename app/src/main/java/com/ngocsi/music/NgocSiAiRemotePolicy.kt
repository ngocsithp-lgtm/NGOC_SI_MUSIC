package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel

/** Ordinary questions should fail fast; longer reasoning receives a separate bounded window. */
internal const val NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 25_000L
internal const val NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 40_000L
internal const val NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS = 384
internal const val NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS = 1_024

internal fun selectNgocSiAiRemoteTimeoutMillis(prompt: String): Long =
    if (selectNgocSiAiThinkingLevel(prompt) == ThinkingLevel.MEDIUM) {
        NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS
    } else {
        NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS
    }

internal fun ngocSiAiRemoteTimeoutMessage(prompt: String): String {
    val seconds = selectNgocSiAiRemoteTimeoutMillis(prompt) / 1_000L
    return "Máy chủ Gemini chưa hoàn tất câu trả lời sau $seconds giây. " +
        "Hãy thử câu hỏi ngắn hơn hoặc kiểm tra Internet; các lệnh điều khiển trên điện thoại vẫn hoạt động."
}

/**
 * Keep ordinary follow-up turns at LOW thinking even when history is long.
 * The newest request, not the number of prior messages, determines reasoning depth.
 */
internal fun selectNgocSiAiThinkingLevel(prompt: String): ThinkingLevel {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val mediumIntentMarkers = listOf(
        "phan tich", "so sanh", "lap ke hoach", "chien luoc", "danh gia",
        "kiem tra ky", "tim nguyen nhan", "sua loi", "nang cap", "de xuat",
        "toi uu", "huong dan tung buoc", "bao cao", "tong hop", "phan tich sau"
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
internal fun selectNgocSiAiMaxOutputTokens(prompt: String): Int =
    if (selectNgocSiAiThinkingLevel(prompt) == ThinkingLevel.MEDIUM) {
        NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS
    } else {
        NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS
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
    // Questions asking for an explanation should never become app actions.
    val informationalPrefixes = listOf(
        "vi sao ", "tai sao ", "lam sao ", "lam the nao ",
        "cach nao ", "ke cho toi nghe ", "cho toi biet ", "la gi "
    )
    if (informationalPrefixes.any { prefix -> normalized.startsWith(prefix) }) return false

    if (hasDirectNgocSiAiAppActionIntent(normalized)) return true
    return isConfirmedNgocSiAiContextualAction(normalized, previousMessages)
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

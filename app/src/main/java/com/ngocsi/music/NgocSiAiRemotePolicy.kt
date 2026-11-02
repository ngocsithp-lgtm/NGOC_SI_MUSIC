package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel

/** Shared deadline for voice and screen chat. Streaming surfaces partial text before this deadline. */
internal const val NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 90_000L
internal const val NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS = 1_024
internal const val NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS = 2_048

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
    val needsMediumReasoning =
        normalized.length >= 180 ||
            mediumIntentMarkers.any { marker -> normalized.contains(marker) }
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
internal fun shouldOfferNgocSiAiAppCommandTool(prompt: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    // Questions asking for an explanation should not be routed as commands, even if they
    // contain words such as "YouTube phát nhạc" or "âm lượng".
    val informationalPrefixes = listOf(
        "vi sao ", "tai sao ", "lam sao ", "lam the nao ",
        "cach nao ", "ke cho toi nghe ", "cho toi biet ", "la gi "
    )
    if (informationalPrefixes.any { prefix -> normalized.startsWith(prefix) }) return false

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

    // Natural Vietnamese commands often put the verb and target in different positions:
    // "chuyển qua Radio", "bấm mở mục Drive", "đi đến thư viện", "điều khiển video".
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

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
    val actionMarkers = listOf(
        "mo youtube", "mo radio", "mo drive", "mo thu vien", "mo tv", "mo muc",
        "bat radio", "bat nhac", "phat nhac", "phat bai", "cho phat", "muon nghe",
        "nghe bai", "nghe nhac", "tam dung", "dung nhac", "tiep theo", "chuyen bai",
        "bai tiep", "bai truoc", "tua ", "tang am luong", "giam am luong",
        "tim bai hat", "tim kiem bai hat", "tim youtube", "tim tren youtube",
        "dat hen gio", "hen gio tat", "them vao yeu thich", "luu vao yeu thich",
        "phat radio", "mo kenh", "youtube play", "youtube pause", "youtube next",
        "youtube previous", "play ", "pause", "stop ", "skip "
    )
    return actionMarkers.any { marker -> normalized.contains(marker) }
}

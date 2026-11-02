package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel

/**
 * The voice conversation used to cancel Gemini requests after 25 seconds, before Firebase
 * AI Logic's own default request timeout. Allow a bounded 90 seconds for cold starts,
 * slower mobile networks, and function-call round trips while keeping the foreground loop finite.
 */
internal const val NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS = 90_000L

/**
 * Select a lower-latency thinking level for short everyday requests, but retain MEDIUM for
 * complex instructions and extended conversations so answer quality is not reduced globally.
 */
internal fun selectNgocSiAiThinkingLevel(
    prompt: String,
    historyMessageCount: Int
): ThinkingLevel {
    val normalized = normalizeNgocSiAiVoicePhrase(prompt)
    val mediumIntentMarkers = listOf(
        "phan tich",
        "so sanh",
        "lap ke hoach",
        "chien luoc",
        "danh gia",
        "kiem tra ky",
        "tim nguyen nhan",
        "sua loi",
        "nang cap",
        "de xuat",
        "toi uu",
        "huong dan tung buoc",
        "bao cao",
        "tong hop",
        "phan tich sau"
    )
    val needsMediumReasoning =
        normalized.length >= 180 ||
            historyMessageCount >= 8 ||
            mediumIntentMarkers.any { marker -> normalized.contains(marker) }

    return if (needsMediumReasoning) ThinkingLevel.MEDIUM else ThinkingLevel.LOW
}

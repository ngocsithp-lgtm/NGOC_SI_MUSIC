package com.ngocsi.music

/**
 * Pure rules for parsing YouTube video references and mapping IFrame API errors.
 *
 * These helpers deliberately do not extract media URLs. YouTube playback remains
 * in the visible embedded player; Media3 is reserved for sources that permit
 * independent background playback.
 */
internal object YouTubePlaybackRules {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val videoUrlPattern = Regex(
        """(?:(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/watch\\?(?:[^#\\s&]+&)*v=|(?:https?://)?youtu\\.be/|(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/(?:embed|shorts|live)/)([A-Za-z0-9_-]{11})""",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeVideoId(value: String): String {
        val input = value.trim()
        if (videoIdPattern.matches(input)) return input

        val match = videoUrlPattern.find(input) ?: return ""
        return match.groupValues.getOrNull(1)
            .orEmpty()
            .takeIf(videoIdPattern::matches)
            .orEmpty()
    }

    fun errorMessage(code: Int): String = when (code) {
        153 -> "YouTube không xác thực được trình phát (Error 153). Hãy bấm THỬ LẠI."
        152 -> "YouTube báo lỗi xác thực/trình phát (Error 152). Hãy bấm THỬ LẠI."
        101, 150 -> "Video này không cho phép phát trong trình phát nhúng."
        100 -> "Video không tồn tại hoặc đã bị gỡ."
        else -> "YouTube báo lỗi trình phát ($code). Hãy bấm THỬ LẠI."
    }
}

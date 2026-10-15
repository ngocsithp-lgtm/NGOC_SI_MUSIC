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
        """(?:(?:https?://)?(?:www\.|m\.)?youtube\.com/watch\?(?:[^#\s&]+&)*v=|(?:https?://)?youtu\.be/|(?:https?://)?(?:www\.|m\.)?youtube\.com/(?:embed|shorts|live)/)([A-Za-z0-9_-]{11})""",
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

    /**
     * Accept a player event if it has no video ID yet or is for the active video.
     * Events with a different, known video ID are stale and must be ignored.
     */
    fun eventBelongsToActiveVideo(activeVideoId: String, eventVideoId: String?): Boolean {
        val active = sanitizeVideoId(activeVideoId)
        if (active.isBlank()) return false
        val reported = sanitizeVideoId(eventVideoId.orEmpty())
        return reported.isBlank() || reported == active
    }

    /** Only advance the queue when the player explicitly reports the active video ended. */
    fun isEndedEventForActiveVideo(activeVideoId: String, eventVideoId: String?): Boolean {
        val active = sanitizeVideoId(activeVideoId)
        val reported = sanitizeVideoId(eventVideoId.orEmpty())
        return active.isNotBlank() && reported == active
    }

    fun errorMessage(code: Int): String = when (code) {
        153 -> "YouTube không xác thực được trình phát (Error 153). Hãy bấm THỬ LẠI."
        152 -> "YouTube báo lỗi xác thực/trình phát (Error 152). Hãy bấm THỬ LẠI."
        101, 150 -> "Video này không cho phép phát trong trình phát nhúng."
        100 -> "Video không tồn tại hoặc đã bị gỡ."
        else -> "YouTube báo lỗi trình phát ($code). Hãy bấm THỬ LẠI."
    }
}

package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackRulesTest {
    @Test
    fun acceptsAValidVideoIdWithoutChangingIt() {
        assertEquals("dQw4w9WgXcQ", YouTubePlaybackRules.sanitizeVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun extractsIdsFromCommonYoutubeLinkFormats() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubePlaybackRules.sanitizeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubePlaybackRules.sanitizeVideoId("https://youtu.be/dQw4w9WgXcQ?t=12")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubePlaybackRules.sanitizeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubePlaybackRules.sanitizeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")
        )
    }

    @Test
    fun rejectsMalformedIdsInsteadOfPassingThemToTheIframePlayer() {
        assertTrue(YouTubePlaybackRules.sanitizeVideoId("short").isEmpty())
        assertTrue(YouTubePlaybackRules.sanitizeVideoId("dQw4w9WgXcQ!").isEmpty())
        assertTrue(YouTubePlaybackRules.sanitizeVideoId("https://example.com/watch?v=dQw4w9WgXcQ").isEmpty())
        assertTrue(YouTubePlaybackRules.sanitizeVideoId("").isEmpty())
    }

    @Test
    fun mapsCommonIframeErrorsToActionableVietnameseMessages() {
        assertTrue(YouTubePlaybackRules.errorMessage(153).contains("Error 153"))
        assertTrue(YouTubePlaybackRules.errorMessage(152).contains("Error 152"))
        assertTrue(YouTubePlaybackRules.errorMessage(101).contains("không cho phép"))
        assertTrue(YouTubePlaybackRules.errorMessage(100).contains("không tồn tại"))
        assertTrue(YouTubePlaybackRules.errorMessage(999).contains("999"))
    }
}

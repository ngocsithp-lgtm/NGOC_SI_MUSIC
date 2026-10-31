package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLandscapePaneRulesTest {
    @Test
    fun compactLandscapeGivesTheQueueMoreRoom() {
        assertEquals(0.52f, YouTubeLandscapePaneRules.leftPaneWeight(719), 0.001f)
    }

    @Test
    fun mediumLandscapeBalancesVideoAndQueue() {
        assertEquals(0.54f, YouTubeLandscapePaneRules.leftPaneWeight(720), 0.001f)
        assertEquals(0.54f, YouTubeLandscapePaneRules.leftPaneWeight(899), 0.001f)
    }

    @Test
    fun wideLandscapePreservesRoomForTheSixteenByNinePlayer() {
        assertEquals(0.56f, YouTubeLandscapePaneRules.leftPaneWeight(900), 0.001f)
        assertEquals(0.56f, YouTubeLandscapePaneRules.leftPaneWeight(1440), 0.001f)
    }

    @Test
    fun unavailableWidthUsesTheSafeWideDefault() {
        assertEquals(0.56f, YouTubeLandscapePaneRules.leftPaneWeight(0), 0.001f)
    }


    @Test
    fun compactLandscapeUsesReadableQueueThumbnailsAndText() {
        assertEquals(80, YouTubeLandscapePaneRules.thumbnailWidthDp(719))
        assertEquals(45, YouTubeLandscapePaneRules.thumbnailHeightDp(719))
        assertEquals(11, YouTubeLandscapePaneRules.titleFontSizeSp(719))
    }

    @Test
    fun wideLandscapeUsesLargerQueueArtworkAndTypography() {
        assertEquals(88, YouTubeLandscapePaneRules.thumbnailWidthDp(900))
        assertEquals(50, YouTubeLandscapePaneRules.thumbnailHeightDp(900))
        assertEquals(12, YouTubeLandscapePaneRules.titleFontSizeSp(900))
    }

    @Test
    fun rightPaneAlwaysReceivesTheRemainingWidth() {
        listOf(360, 719, 720, 899, 900, 1440).forEach { width ->
            val left = YouTubeLandscapePaneRules.leftPaneWeight(width)
            val right = 1f - left
            assertEquals(1f, left + right, 0.001f)
            assertTrue(right >= 0.44f)
        }
    }
}

package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeBackgroundAudioMatchRulesTest {
    @Test
    fun stripsYouTubeMetadataAndAccentsForTitleMatch() {
        assertEquals(
            1.0,
            BackgroundAudioMatchRules.titleScore(
                "Bích Phương - Đi Đu Đưa Đi (Official Music Video)",
                "Đi Đu Đưa Đi"
            ),
            0.0001
        )
    }

    @Test
    fun transliteratesVietnameseDStrokeBeforeRemovingNonAsciiCharacters() {
        assertEquals("duong dem", BackgroundAudioMatchRules.normalize("Đường Đêm"))
        assertEquals(
            1.0,
            BackgroundAudioMatchRules.titleScore("Đường Đêm", "Duong Dem"),
            0.0001
        )
    }

    @Test
    fun ranksSameTitleHigherWhenArtistMatches() {
        val expectedTitle = "Ngọc Sĩ - Bài hát"
        val expectedArtist = "Ngọc Sĩ"
        val exactArtistScore = BackgroundAudioMatchRules.rankingScore(
            expectedTitle, expectedArtist, "Bài hát", "Ngọc Sĩ"
        )
        val nearMatchArtistScore = BackgroundAudioMatchRules.rankingScore(
            expectedTitle, expectedArtist, "Bài hát", "Ngọc Sĩ Live"
        )

        assertTrue(exactArtistScore > nearMatchArtistScore)
    }

    @Test
    fun normalizesTopicSuffixForArtistMatch() {
        assertEquals(
            1.0,
            BackgroundAudioMatchRules.artistScore(
                "Ngọc Sĩ - Bài hát",
                "Ngọc Sĩ - Topic",
                "Ngọc Sĩ"
            ),
            0.0001
        )
    }

    @Test
    fun rejectsClearlyDifferentSong() {
        assertFalse(
            BackgroundAudioMatchRules.isStrongMatch(
                "Bích Phương - Đi Đu Đưa Đi (Official MV)",
                "Bích Phương",
                "Một bài hoàn toàn khác",
                "Bích Phương",
                0.91
            )
        )
    }

    @Test
    fun rejectsSameTitleFromClearlyDifferentArtist() {
        assertFalse(
            BackgroundAudioMatchRules.isStrongMatch(
                "Ngọc Sĩ - Bài hát",
                "Ngọc Sĩ - Topic",
                "Bài hát",
                "Một nghệ sĩ khác",
                0.90
            )
        )
    }

    @Test
    fun acceptsExactTitleAndArtistFromIndependentSource() {
        assertTrue(
            BackgroundAudioMatchRules.isStrongMatch(
                "Ngọc Sĩ - Bài hát (Official Audio)",
                "Ngọc Sĩ - Topic",
                "Bài hát",
                "Ngọc Sĩ",
                0.90
            )
        )
    }
}

package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiPlaybackQuestionRulesTest {
    @Test
    fun recognizesCurrentTrackQuestionWithVietnameseDiacritics() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "AI, bài hát đang nghe là gì?"
            )
        )
    }

    @Test
    fun recognizesAlternativeCurrentTrackWording() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Tên bài nhạc hiện tại là gì"
            )
        )
    }

    @Test
    fun doesNotTreatGeneralMusicQuestionAsCurrentTrackLookup() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Bạn giới thiệu cho tôi một bài Bolero hay"
            )
        )
    }

    @Test
    fun routesYoutubeSingerQuestionToMultimodalPath() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "AI, ai đang hát bài này?",
                "YouTube"
            )
        )
    }

    @Test
    fun doesNotClassifySingerQuestionAsYoutubeOutsideYoutubeContext() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "Ai đang hát bài này?",
                "Radio"
            )
        )
    }

    @Test
    fun recognizesCurrentArtistMetadataQuestion() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentArtistMetadataQuestion(
                "Nghệ sĩ đang hát bài này là ai?"
            )
        )
    }
}

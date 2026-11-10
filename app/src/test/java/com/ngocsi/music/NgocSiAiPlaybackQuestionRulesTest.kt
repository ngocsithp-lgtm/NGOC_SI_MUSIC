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
    fun recognizesShortCurrentSongQuestion() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Đang nghe bài gì vậy?"
            )
        )
    }

    @Test
    fun recognizesShortTrackTitleQuestion() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Tên bài đang nghe?"
            )
        )
    }

    @Test
    fun recognizesQuestionAboutWhichSongIsPlaying() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Bài hát nào đang phát?"
            )
        )
    }

    @Test
    fun recognizesCurrentTrackTitleUsingCaKhuc() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Tên ca khúc này là gì?"
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
    fun recognizesSingerQuestionWithCaSiWording() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "Ca sĩ đang hát bài này là ai?",
                "YouTube"
            )
        )
    }

    @Test
    fun recognizesWhoPerformsCurrentYoutubeSong() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "Ai là ca sĩ của bài này?",
                "YouTube"
            )
        )
    }

    @Test
    fun recognizesWhoIsTheSingerWording() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "Ai là người hát bài này?",
                "YouTube"
            )
        )
    }

    @Test
    fun doesNotTreatGeneralArtistQuestionAsCurrentYoutubeSingerRequest() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "Hãy giới thiệu ca sĩ Tuấn Vũ",
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

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
    fun recognizesCanonicalCurrentSongMetadataPhrasesBeforeLocalChatRouting() {
        listOf(
            "AI, bài đang nghe là gì?",
            "AI, bài đang phát là gì?",
            "AI, tên bài đang nghe là gì?",
            "AI, tên bài hát đang phát là gì?",
            "AI, tên ca khúc đang nghe là gì?",
            "AI, bài hát đang phát tên gì?",
            "AI, bài đang phát tên gì?",
            "AI, bài đang nghe tên gì?",
            "AI, tên bài hiện tại là gì?",
            "AI, tên bài nhạc đang nghe là gì?",
            "AI, tên bài nhạc đang phát là gì?",
            "AI, bài này là bài gì?",
            "AI, bài hát này là bài gì?",
            "AI, tên của bài đang phát là gì?",
            "AI, tên của bài đang nghe là gì?",
            "AI, cho tôi biết tên bài đang phát.",
            "AI, cho tôi biết tên bài đang nghe."
        ).forEach { question ->
            assertTrue(
                "Expected current-track metadata route for: $question",
                NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(question)
            )
        }
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
    fun recognizesNaturalSingerIdentificationVariantsForCurrentYoutubeVideo() {
        listOf(
            "AI, ca sĩ của bài hát này là ai?",
            "AI, ai là ca sĩ đang trình bày bài hát này?",
            "AI, ai trình bày ca khúc đang phát?",
            "AI, tên ca sĩ của video này là gì?",
            "AI, giọng hát trong video này là của ai?",
            "AI, người thể hiện bài đang phát là ai?",
            "AI, bài đang phát do ai trình bày?",
            "AI, ai trình bày bài hát này?",
            "AI, ai đang hát ca khúc này?",
            "AI, người hát ca khúc đang phát là ai?",
            "AI, người hát trong video này là ai?",
            "AI, giọng ca trong video này là ai?",
            "AI, ca sĩ của ca khúc này là ai?",
            "AI, ca sĩ nào trình bày bài hát đang phát?",
            "AI, bài hát đang phát do ca sĩ nào thể hiện?",
            "AI, ai đang thể hiện bài hát này?",
            "AI, ai là người thể hiện bài hát này?",
            "AI, người thể hiện bài hát này là ai?",
            "AI, người trình bày ca khúc này là ai?",
            "AI, người biểu diễn bài hát này là ai?",
            "AI, ai biểu diễn bài hát này?",
            "AI, nghệ sĩ thể hiện ca khúc đang phát là ai?",
            "AI, ca sĩ nào hát ca khúc đang phát?"
        ).forEach { question ->
            assertTrue(
                "Expected YouTube audio-evidence route for: $question",
                NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                    question,
                    "YouTube"
                )
            )
        }
    }

    @Test
    fun doesNotRouteSingerIdentificationToYoutubeOutsideYoutubeContext() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isYoutubeSingerIdentificationQuestion(
                "AI, ca sĩ của bài hát này là ai?",
                "Radio"
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
    fun recognizesCurrentTrackGenreQuestion() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentGenreQuestion(
                "Bài này thuộc thể loại gì?"
            )
        )
    }

    @Test
    fun recognizesCurrentSongDongNhacQuestion() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentGenreQuestion(
                "Dòng nhạc của bài đang nghe là gì?"
            )
        )
    }

    @Test
    fun recognizesBoleroVerificationQuestionForCurrentTrack() {
        assertTrue(
            NgocSiAiPlaybackQuestionRules.isCurrentGenreQuestion(
                "Bài này có phải Bolero không?"
            )
        )
    }

    @Test
    fun doesNotTreatGeneralGenreQuestionAsCurrentTrackLookup() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isCurrentGenreQuestion(
                "Bạn thích thể loại nhạc nào?"
            )
        )
    }

    @Test
    fun doesNotMisrouteGenreQuestionAsTrackTitleLookup() {
        assertFalse(
            NgocSiAiPlaybackQuestionRules.isCurrentTrackQuestion(
                "Bài này thuộc thể loại gì?"
            )
        )
    }

    @Test
    fun recognizesNaturalCurrentArtistMetadataQuestionsWithoutAssumingAudioRecognition() {
        listOf(
            "AI, ai trình bày bài hát này?",
            "AI, ai đang hát ca khúc này?",
            "AI, người hát ca khúc đang phát là ai?",
            "AI, người hát trong video này là ai?",
            "AI, giọng ca trong video này là ai?",
            "AI, ca sĩ của ca khúc này là ai?",
            "AI, ca sĩ nào trình bày bài hát đang phát?",
            "AI, bài hát đang phát do ca sĩ nào thể hiện?",
            "AI, ai đang thể hiện bài hát này?",
            "AI, ai là người thể hiện bài hát này?",
            "AI, người thể hiện bài hát này là ai?",
            "AI, người trình bày ca khúc này là ai?",
            "AI, người biểu diễn bài hát này là ai?",
            "AI, ai biểu diễn bài hát này?",
            "AI, nghệ sĩ thể hiện ca khúc đang phát là ai?",
            "AI, ca sĩ nào hát ca khúc đang phát?"
        ).forEach { question ->
            assertTrue(
                "Expected current-player artist metadata lookup for: $question",
                NgocSiAiPlaybackQuestionRules.isCurrentArtistMetadataQuestion(question)
            )
        }
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

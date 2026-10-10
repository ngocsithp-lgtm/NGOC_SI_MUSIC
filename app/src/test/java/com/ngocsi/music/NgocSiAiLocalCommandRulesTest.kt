package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiLocalCommandRulesTest {
    @Test
    fun recognizesVietnameseCommandsWithAccentsAndPolitePrefixes() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Phát nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Ngọc Sĩ, tạm dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Vui lòng chuyển bài"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Hãy quay lại bài trước"))
    }

    @Test
    fun recognizesShortEnglishPlaybackCommands() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Resume"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Pause music"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Next song"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Previous"))
    }

    @Test
    fun recognizesCurrentTrackStatusQuestions() {
        assertEquals(NgocSiAiLocalCommand.CURRENT_TRACK, classifyNgocSiAiLocalCommand("Đang phát bài gì?"))
        assertEquals(NgocSiAiLocalCommand.CURRENT_TRACK, classifyNgocSiAiLocalCommand("Ngọc Sĩ, bài gì đang phát"))
        assertEquals(NgocSiAiLocalCommand.CURRENT_TRACK, classifyNgocSiAiLocalCommand("What is playing"))
    }

    @Test
    fun doesNotTreatGeneralQuestionsOrUnrelatedTextAsCommands() {
        assertNull(classifyNgocSiAiLocalCommand("Làm thế nào để phát nhạc?"))
        assertNull(classifyNgocSiAiLocalCommand("Giúp tôi tìm nhạc vui"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi thích bài tiếp theo trong danh sách"))
        assertNull(classifyNgocSiAiLocalCommand(""))
        assertNull(classifyNgocSiAiLocalCommand("   "))
    }

    @Test
    fun wakePhraseRequiresNgocSiAndRecognizesExplicitCommands() {
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("Ngọc Sĩ, chuyển bài"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("Ngọc Sĩ, chuyển sang bài tiếp theo nhé"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("Ngọc Sĩ, bài tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiWakePhrase("NGỌC SĨ tạm dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.SHUFFLE_ON, classifyNgocSiAiWakePhrase("Ngọc Sĩ, bật phát ngẫu nhiên"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_ONE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, lặp một bài"))
        assertNull(classifyNgocSiAiWakePhrase("Chuyển bài"))
        assertNull(classifyNgocSiAiWakePhrase("Ngọc Sĩ"))
        assertNull(classifyNgocSiAiWakePhrase("Tôi nghe Ngọc Sĩ chuyển bài"))
        assertNull(classifyNgocSiAiWakePhrase("Ngọc Sĩ, làm thế nào để chuyển bài?"))
    }

    @Test
    fun recognizesExplicitShuffleAndRepeatCommands() {
        assertEquals(NgocSiAiLocalCommand.SHUFFLE_ON, classifyNgocSiAiLocalCommand("Bật phát ngẫu nhiên"))
        assertEquals(NgocSiAiLocalCommand.SHUFFLE_OFF, classifyNgocSiAiLocalCommand("Ngọc Sĩ, tắt ngẫu nhiên"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_OFF, classifyNgocSiAiLocalCommand("Tắt chế độ lặp"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_ALL, classifyNgocSiAiLocalCommand("Lặp hàng đợi"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_ONE, classifyNgocSiAiLocalCommand("Lặp một bài hát"))
        assertEquals(NgocSiAiLocalCommand.SHUFFLE_ON, classifyNgocSiAiLocalCommand("Turn shuffle on"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_ALL, classifyNgocSiAiLocalCommand("Repeat all"))
    }

    @Test
    fun doesNotTurnQuestionsOrVaguePhrasesIntoPlaybackActions() {
        assertNull(classifyNgocSiAiLocalCommand("Làm thế nào để bật phát ngẫu nhiên?"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi thích chế độ lặp"))
        assertNull(classifyNgocSiAiLocalCommand("Có thể phát ngẫu nhiên không?"))
    }

    @Test
    fun repeatedlyStripsOnlyKnownPolitePrefixes() {
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, hãy chuyển bài!")
        )
        assertEquals(
            NgocSiAiLocalCommand.SHUFFLE_OFF,
            classifyNgocSiAiLocalCommand("Vui lòng tắt phát ngẫu nhiên")
        )
        assertNull(classifyNgocSiAiLocalCommand("Hãy chỉ tôi cách chuyển bài"))
    }

    @Test
    fun recognizesAdditionalCommonVietnameseSpokenCommands() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Phát bài hát đi"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Cho tôi nghe nhạc"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Mở bài hát lên"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Dừng phát đi"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tạm dừng bài hát"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Chuyển qua bài sau"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Nhạc tiếp"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Lùi lại bài trước"))
    }

    @Test
    fun recognizesNaturalVoiceCommandVariants() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Mở nhạc lên"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Bật nhạc lên"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Dừng phát nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tắt nhạc đi"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Qua bài"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("Ngọc Sĩ, chuyển sang bài kế tiếp"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Bài trước đó"))
    }


    @Test
    fun prefersARecognizedPlaybackCommandAmongSpeechHypotheses() {
        assertEquals(
            "phát nhạc",
            selectNgocSiAiLocalCommandCandidate(listOf("pháp nhạc", "phát nhạc", "phát nhanh"))
        )
        assertEquals("câu không rõ", selectNgocSiAiLocalCommandCandidate(listOf("câu không rõ", "lời khác")))
        assertEquals(null, selectNgocSiAiLocalCommandCandidate(listOf("", "   ")))
    }

    @Test
    fun wakeWordCandidateSelectionStillRequiresTheWakePhrase() {
        assertEquals(
            "Ngọc Sĩ, chuyển bài",
            selectNgocSiAiWakePhraseCandidate(listOf("chuyển bài", "Ngọc Sĩ, chuyển bài", "Ngọc Sĩ"))
        )
        assertEquals("chuyển bài", selectNgocSiAiWakePhraseCandidate(listOf("chuyển bài", "qua bài")))
    }

}

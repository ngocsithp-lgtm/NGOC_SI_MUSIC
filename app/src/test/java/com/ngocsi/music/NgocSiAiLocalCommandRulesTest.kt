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
}

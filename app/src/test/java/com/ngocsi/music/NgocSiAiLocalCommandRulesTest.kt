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
    fun doesNotTreatGeneralQuestionsOrUnrelatedTextAsCommands() {
        assertNull(classifyNgocSiAiLocalCommand("Làm thế nào để phát nhạc?"))
        assertNull(classifyNgocSiAiLocalCommand("Giúp tôi tìm nhạc vui"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi thích bài tiếp theo trong danh sách"))
        assertNull(classifyNgocSiAiLocalCommand(""))
        assertNull(classifyNgocSiAiLocalCommand("   "))
    }

    @Test
    fun repeatedlyStripsOnlyKnownPolitePrefixes() {
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, hãy chuyển bài!")
        )
        assertNull(classifyNgocSiAiLocalCommand("Hãy chỉ tôi cách chuyển bài"))
    }
}

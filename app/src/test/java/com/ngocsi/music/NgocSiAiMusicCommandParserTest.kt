package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiMusicCommandParserTest {
    @Test
    fun parsesVietnamesePlaybackCommands() {
        assertEquals(NgocSiAiMusicCommand.PLAY, NgocSiAiMusicCommandParser.parse("Hãy phát nhạc"))
        assertEquals(NgocSiAiMusicCommand.PAUSE, NgocSiAiMusicCommandParser.parse("Tạm dừng nhạc"))
        assertEquals(NgocSiAiMusicCommand.NEXT, NgocSiAiMusicCommandParser.parse("Chuyển bài tiếp theo"))
        assertEquals(NgocSiAiMusicCommand.PREVIOUS, NgocSiAiMusicCommandParser.parse("Vui lòng quay lại bài trước"))
    }

    @Test
    fun parsesSimpleEnglishPlaybackCommands() {
        assertEquals(NgocSiAiMusicCommand.PLAY, NgocSiAiMusicCommandParser.parse("resume music"))
        assertEquals(NgocSiAiMusicCommand.PAUSE, NgocSiAiMusicCommandParser.parse("pause music"))
        assertEquals(NgocSiAiMusicCommand.NEXT, NgocSiAiMusicCommandParser.parse("next song"))
        assertEquals(NgocSiAiMusicCommand.PREVIOUS, NgocSiAiMusicCommandParser.parse("previous song"))
    }

    @Test
    fun doesNotExecuteCommandsMentionedAsQuestionsOrInMiddleOfText() {
        assertNull(NgocSiAiMusicCommandParser.parse("Làm sao để tạm dừng nhạc?"))
        assertNull(NgocSiAiMusicCommandParser.parse("Tôi đang nghe nhạc, hãy tạm dừng"))
        assertNull(NgocSiAiMusicCommandParser.parse("Hãy tìm cho tôi một bài nhạc vui"))
    }
}

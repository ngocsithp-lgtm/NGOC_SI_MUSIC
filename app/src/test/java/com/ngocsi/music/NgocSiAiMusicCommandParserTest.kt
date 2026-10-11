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
    fun parsesAllowlistedAppNavigationCommands() {
        assertEquals(NgocSiAiMusicCommand.OPEN_LIBRARY, NgocSiAiMusicCommandParser.parse("Mở thư viện nhạc"))
        assertEquals(NgocSiAiMusicCommand.OPEN_YOUTUBE, NgocSiAiMusicCommandParser.parse("Hãy mở YouTube"))
        assertEquals(NgocSiAiMusicCommand.OPEN_RADIO, NgocSiAiMusicCommandParser.parse("Mở Radio"))
        assertEquals(NgocSiAiMusicCommand.OPEN_QUEUE, NgocSiAiMusicCommandParser.parse("Xem hàng đợi"))
        assertEquals(NgocSiAiMusicCommand.OPEN_PLAYER, NgocSiAiMusicCommandParser.parse("Mở trình phát"))
        assertEquals(NgocSiAiMusicCommand.OPEN_TV, NgocSiAiMusicCommandParser.parse("Mở TV"))
        assertEquals(NgocSiAiMusicCommand.OPEN_DRIVE, NgocSiAiMusicCommandParser.parse("Mở Google Drive"))
        assertEquals(NgocSiAiMusicCommand.OPEN_SETTINGS, NgocSiAiMusicCommandParser.parse("Mở cài đặt"))
        assertEquals(NgocSiAiMusicCommand.NEXT, NgocSiAiMusicCommandParser.parse("Phát nhạc tiếp theo"))
    }

    @Test
    fun parsesShuffleAndRepeatCommands() {
        assertEquals(NgocSiAiMusicCommand.SHUFFLE_ON, NgocSiAiMusicCommandParser.parse("Bật phát ngẫu nhiên"))
        assertEquals(NgocSiAiMusicCommand.SHUFFLE_OFF, NgocSiAiMusicCommandParser.parse("Tắt xáo trộn"))
        assertEquals(NgocSiAiMusicCommand.REPEAT_ONE, NgocSiAiMusicCommandParser.parse("Lặp lại bài này"))
        assertEquals(NgocSiAiMusicCommand.REPEAT_ALL, NgocSiAiMusicCommandParser.parse("Lặp lại toàn bộ"))
        assertEquals(NgocSiAiMusicCommand.REPEAT_OFF, NgocSiAiMusicCommandParser.parse("Tắt lặp lại"))
        assertEquals(NgocSiAiMusicCommand.SHUFFLE_ON, NgocSiAiMusicCommandParser.parse("shuffle"))
        assertEquals(NgocSiAiMusicCommand.REPEAT_ONE, NgocSiAiMusicCommandParser.parse("repeat one"))
    }

    @Test
    fun parsesSleepTimerPresetsAndCancelCommand() {
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_5, NgocSiAiMusicCommandParser.parse("Hẹn giờ tắt nhạc sau 5 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_10, NgocSiAiMusicCommandParser.parse("Hẹn giờ tắt nhạc 10 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_15, NgocSiAiMusicCommandParser.parse("Hẹn giờ sau 15 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_20, NgocSiAiMusicCommandParser.parse("Tắt nhạc sau 20 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_30, NgocSiAiMusicCommandParser.parse("Hẹn giờ tắt nhạc trong 30 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_45, NgocSiAiMusicCommandParser.parse("sleep timer 45"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_60, NgocSiAiMusicCommandParser.parse("Hẹn giờ tắt nhạc 60 phút"))
        assertEquals(NgocSiAiMusicCommand.SET_SLEEP_TIMER_90, NgocSiAiMusicCommandParser.parse("sleep timer 90"))
        assertEquals(NgocSiAiMusicCommand.CANCEL_SLEEP_TIMER, NgocSiAiMusicCommandParser.parse("Hủy hẹn giờ"))
        assertEquals(NgocSiAiMusicCommand.OPEN_SLEEP_TIMER, NgocSiAiMusicCommandParser.parse("Mở hẹn giờ tắt nhạc"))
    }

    @Test
    fun doesNotExecuteCommandsMentionedAsQuestionsOrInMiddleOfText() {
        assertNull(NgocSiAiMusicCommandParser.parse("Làm sao để tạm dừng nhạc?"))
        assertNull(NgocSiAiMusicCommandParser.parse("Tôi đang nghe nhạc, hãy tạm dừng"))
        assertNull(NgocSiAiMusicCommandParser.parse("Hãy tìm cho tôi một bài nhạc vui"))
    }
}

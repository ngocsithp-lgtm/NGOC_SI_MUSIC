package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiLocalCommandRulesTest {
    @Test
    fun recognizesExplicitAppNavigationCommands() {
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Mở trang chủ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ONLINE, classifyNgocSiAiLocalCommand("Mở Online"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Mở hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Tắt hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Hủy hẹn giờ tắt nhạc"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Hẹn giờ tắt nhạc 30 phút"))
        assertEquals(NgocSiAiLocalCommand.OPEN_LIBRARY, classifyNgocSiAiLocalCommand("Mở thư viện"))
        assertEquals(NgocSiAiLocalCommand.OPEN_YOUTUBE, classifyNgocSiAiLocalCommand("Ngọc Sĩ, mở YouTube"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiLocalCommand("Mở Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Mở Radio"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Mở TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_QUEUE, classifyNgocSiAiLocalCommand("Mở hàng đợi"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYLISTS, classifyNgocSiAiLocalCommand("Mở playlist"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYER, classifyNgocSiAiLocalCommand("Mở trình phát"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SETTINGS, classifyNgocSiAiLocalCommand("Mở cài đặt"))
    }

    @Test
    fun parsesOnlySupportedSleepTimerMinutesFromExplicitTimerRequests() {
        assertEquals(15, parseNgocSiAiSleepTimerMinutes("Hẹn giờ tắt nhạc 15 phút"))
        assertEquals(30, parseNgocSiAiSleepTimerMinutes("Ngọc Sĩ, hẹn giờ 30 phút"))
        assertEquals(45, parseNgocSiAiSleepTimerMinutes("Hẹn giờ tắt nhạc bốn mươi lăm phút"))
        assertEquals(120, parseNgocSiAiSleepTimerMinutes("Set sleep timer for 120 minutes"))
        assertNull(parseNgocSiAiSleepTimerMinutes("Mở YouTube trong 30 phút"))
        assertNull(parseNgocSiAiSleepTimerMinutes("Hẹn giờ tắt nhạc 10 phút"))
    }

    @Test
    fun backgroundWakePhraseDoesNotLaunchNavigationScreens() {
        assertEquals(null, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở thư viện"))
        assertEquals(null, classifyNgocSiAiWakePhrase("Ngọc Sĩ, hẹn giờ 30 phút"))
        assertEquals(null, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tắt hẹn giờ"))
        assertEquals(true, hasNgocSiAiWakePhrase("Ngọc Sĩ, mở YouTube"))
        assertEquals(false, hasNgocSiAiWakePhrase("Mở YouTube"))
    }

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
    fun recognizesNaturalPoliteVoiceCommandsWithoutTreatingQuestionsAsActions() {
        assertEquals(
            NgocSiAiLocalCommand.PLAY,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, phát nhạc lên đi")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Bạn hãy dừng phát nhạc lại")
        )
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiLocalCommand("Cho tôi chuyển sang bài tiếp theo nhé")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Mở YouTube giúp tôi")
        )
        assertEquals(
            NgocSiAiLocalCommand.CURRENT_TRACK,
            classifyNgocSiAiLocalCommand("Đang phát bài gì vậy?")
        )
        assertNull(classifyNgocSiAiLocalCommand("Bạn có thể phát nhạc không?"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi muốn biết cách chuyển bài"))
    }
}

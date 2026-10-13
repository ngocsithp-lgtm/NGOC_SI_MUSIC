package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiLocalCommandRulesTest {
    @Test
    fun recognizesVietnameseCommandsWithAccentsAndPolitePrefixes() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Phát nhạc"))
        assertEquals(NgocSiAiLocalCommand.PLAY_SECOND, classifyNgocSiAiLocalCommand("Hát bài thứ hai trong danh sách"))
        assertEquals(NgocSiAiLocalCommand.PLAY_SECOND, classifyNgocSiAiLocalCommand("Phát bài thứ hai"))
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
    fun recognizesHomeNavigationAndYouTubePlaybackRequests() {
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Quay lại trang chủ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiWakePhrase("Ngọc Sĩ, trở về màn hình chính"))
        assertEquals(NgocSiAiLocalCommand.RESUME_YOUTUBE, classifyNgocSiAiLocalCommand("Phát YouTube"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Phát YouTube nhạc Trịnh Công Sơn"))
        assertEquals("nhạc Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("Phát YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, phát YouTube nhạc Trịnh Công Sơn"))
    }

    @Test
    fun recognizesGeneralOnlineMusicSearchAndQueueSaveCommands() {
        assertEquals(NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC, classifyNgocSiAiLocalCommand("Tìm nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tìm bài hát Bolero"))
        assertEquals("Trịnh Công Sơn", extractNgocSiAiOnlineQuery("Tìm nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST, classifyNgocSiAiLocalCommand("Thêm hàng đợi vào playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST, classifyNgocSiAiWakePhrase("Ngọc Sĩ, lưu hàng đợi vào playlist Bolero"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiQueueToPlaylistQuery("Thêm hàng đợi vào playlist Nhạc Trịnh"))
        assertEquals("Bolero", extractNgocSiAiQueueToPlaylistQuery("Ngọc Sĩ, lưu hàng đợi vào playlist Bolero"))
    }

    @Test
    fun recognizesAddingCurrentTrackToPlaylist() {
        assertEquals(NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST, classifyNgocSiAiLocalCommand("Thêm bài đang phát vào playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST, classifyNgocSiAiWakePhrase("Ngọc Sĩ, thêm bài đang phát vào playlist Nhạc Trịnh"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiAddToPlaylistQuery("Thêm bài đang phát vào playlist Nhạc Trịnh"))
    }

    @Test
    fun recognizesExplicitYouTubePlaybackControlsWithoutConfusingLocalMusic() {
        assertEquals(NgocSiAiLocalCommand.PAUSE_YOUTUBE, classifyNgocSiAiLocalCommand("Tạm dừng YouTube"))
        assertEquals(NgocSiAiLocalCommand.RESUME_YOUTUBE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tiếp tục YouTube"))
        assertEquals(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, classifyNgocSiAiWakePhrase("Ngọc Sĩ, video YouTube tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO, classifyNgocSiAiLocalCommand("Video YouTube trước"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON, classifyNgocSiAiLocalCommand("Bật phát ngẫu nhiên YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tắt phát ngẫu nhiên YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF, classifyNgocSiAiLocalCommand("Tắt lặp YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL, classifyNgocSiAiWakePhrase("Ngọc Sĩ, lặp hàng đợi YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE, classifyNgocSiAiLocalCommand("Lặp video YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_FAVORITE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, thích video YouTube này"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER, classifyNgocSiAiLocalCommand("Lưu video YouTube xem sau"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tạm dừng nhạc"))
    }

    @Test
    fun recognizesNamedPlaylistCreateAndPlayCommandsWithAndWithoutAccents() {
        assertEquals(NgocSiAiLocalCommand.CREATE_PLAYLIST, classifyNgocSiAiLocalCommand("Tạo playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST, classifyNgocSiAiWakePhrase("Ngọc Sĩ, phát playlist Nhạc Trịnh"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiPlaylistQuery("Tạo playlist Nhạc Trịnh", create = true))
        assertEquals("Nhạc Trịnh", extractNgocSiAiPlaylistQuery("Ngọc Sĩ, phát playlist Nhạc Trịnh", create = false))
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở bài Yesterday"))
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiWakePhrase("Ngọc Sĩ, phát Radio VOV3"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở VTV3"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals("nhạc Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("Ngọc Sĩ, tìm YouTube nhạc Trịnh Công Sơn"))
    }

    @Test
    fun recognizesNamedRadioStationsAndTvChannels() {
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiLocalCommand("Phát Radio VOV3"))
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Radio VOH FM 95.6"))
        assertEquals("VOV3", extractNgocSiAiRadioStationQuery("Phát Radio VOV3"))
        assertEquals("VOH FM 95.6", extractNgocSiAiRadioStationQuery("Ngọc Sĩ, mở Radio VOH FM 95.6"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiLocalCommand("Mở VTV3"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở kênh HTV7"))
        assertEquals("VTV3", extractNgocSiAiTvChannelQuery("Mở VTV3"))
        assertEquals("HTV7", extractNgocSiAiTvChannelQuery("Ngọc Sĩ, mở kênh HTV7"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Mở TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Mở Radio"))
        assertNull(extractNgocSiAiRadioStationQuery("Mở Radio"))
        assertNull(extractNgocSiAiTvChannelQuery("Mở TV"))
    }

    @Test
    fun recognizesNamedSongPlaybackCommands() {
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiLocalCommand("Phát bài hát Diễm xưa"))
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở bài Yesterday"))
        assertEquals("Diễm xưa", extractNgocSiAiTrackQuery("Phát bài hát Diễm xưa"))
        assertEquals("Yesterday", extractNgocSiAiTrackQuery("Ngọc Sĩ, mở bài Yesterday"))
        assertNull(extractNgocSiAiTrackQuery("Mở bài hát lên"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Mở bài hát lên"))
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


    @Test
    fun recognizesExplicitAppNavigationCommands() {
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Về trang chủ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_LIBRARY, classifyNgocSiAiLocalCommand("Mở thư viện"))
        assertEquals(NgocSiAiLocalCommand.OPEN_YOUTUBE, classifyNgocSiAiLocalCommand("Mở YouTube"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiLocalCommand("Mở Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Vào Radio"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Mở truyền hình"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYLISTS, classifyNgocSiAiLocalCommand("Mở danh sách phát"))
        assertEquals(NgocSiAiLocalCommand.OPEN_QUEUE, classifyNgocSiAiLocalCommand("Mở hàng đợi"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SETTINGS, classifyNgocSiAiLocalCommand("Mở cài đặt"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYER, classifyNgocSiAiLocalCommand("Mở trình phát"))
    }

    @Test
    fun recognizesSleepTimerDurationsAndCancellation() {
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_15, classifyNgocSiAiLocalCommand("Hẹn giờ tắt nhạc sau 15 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_30, classifyNgocSiAiLocalCommand("Hẹn giờ 30 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_45, classifyNgocSiAiLocalCommand("Đặt hẹn giờ 45 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_60, classifyNgocSiAiLocalCommand("Tắt nhạc sau 60 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_90, classifyNgocSiAiLocalCommand("Hẹn giờ sau 90 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_120, classifyNgocSiAiLocalCommand("Hẹn giờ 120 phút"))
        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Hủy hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Mở hẹn giờ"))
    }

    @Test
    fun helpIntentDoesNotConfuseQuestionsWithActions() {
        assertEquals(NgocSiAiLocalCommand.HELP, classifyNgocSiAiLocalCommand("Bạn làm được gì?"))
        assertEquals(NgocSiAiLocalCommand.HELP, classifyNgocSiAiLocalCommand("Bạn có thể giúp gì?"))
        assertEquals(NgocSiAiLocalCommand.HELP, classifyNgocSiAiLocalCommand("Bạn có thể giúp tôi những gì?"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi muốn biết cách mở YouTube"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi thích Radio"))
    }


    @Test
    fun recognizesYouTubeSearchRequestsWithoutTreatingThemAsNavigationOnly() {
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Tìm nhạc Trịnh Công Sơn trên YouTube"))
        assertEquals("nhạc Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("Tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals("Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("Tìm nhạc Trịnh Công Sơn trên YouTube"))
        assertNull(extractNgocSiAiYoutubeQuery("Tìm YouTube"))
    }


    @Test
    fun wakeWordCanActivateListeningForOneSubsequentPlaybackCommand() {
        assertEquals(true, isNgocSiAiWakeWordOnly("Ngọc Sĩ"))
        assertEquals(true, isNgocSiAiWakeWordOnly("Ngọc Sĩ ơi"))
        assertEquals(true, isNgocSiAiWakeWordOnly("OK Ngọc Sĩ"))
        assertEquals(false, isNgocSiAiWakeWordOnly("Ngọc Sĩ, chuyển bài"))

        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiActivatedCommand("Chuyển bài")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiActivatedCommand("Tạm dừng nhạc")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiActivatedCommand("Mở YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SLEEP_TIMER_30,
            classifyNgocSiAiActivatedCommand("Hẹn giờ 30 phút")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_DRIVE,
            classifyNgocSiAiActivatedCommand("Mở Google Drive")
        )
    }

    @Test
    fun wakeWordCandidateSelectionPrefersWakeOnlyPhraseOverUnrelatedHypothesis() {
        assertEquals(
            "Ngọc Sĩ",
            selectNgocSiAiWakePhraseCandidate(listOf("tóc sĩ", "Ngọc Sĩ"))
        )
    }

    @Test
    fun wakeWordCanRouteAcrossAppFeaturesAndTimers() {
        assertEquals(NgocSiAiLocalCommand.OPEN_YOUTUBE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở YouTube"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Radio"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FAVORITES, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Yêu thích"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ALBUMS, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Album"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FOLDERS, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Thư mục"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ONLINE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, mở Online"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("Ngọc Sĩ, tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_30, classifyNgocSiAiWakePhrase("Ngọc Sĩ, hẹn giờ 30 phút"))
        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiWakePhrase("Ngọc Sĩ, hủy hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("Ngọc Sĩ, chuyển bài"))
        // Unrelated speech must not trigger an app action without the wake phrase.
        assertNull(classifyNgocSiAiWakePhrase("Mở YouTube"))
    }


    @Test
    fun recognizesNaturalPauseAndShuffleOffPhrasesFromSpeechRecognition() {
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Tạm dừng phát nhạc")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, tạm dừng phát nhạc đi")
        )
        assertEquals(
            NgocSiAiLocalCommand.SHUFFLE_OFF,
            classifyNgocSiAiLocalCommand("Tắt phát ngẫu nhiên")
        )
    }

    @Test
    fun recognizesAdditionalLibraryDestinationsForChatAndVoice() {
        assertEquals(NgocSiAiLocalCommand.OPEN_FAVORITES, classifyNgocSiAiLocalCommand("Mở nhạc yêu thích"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FAVORITES, classifyNgocSiAiLocalCommand("Mở thư viện yêu thích"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ALBUMS, classifyNgocSiAiLocalCommand("Xem Album"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FOLDERS, classifyNgocSiAiLocalCommand("Vào Thư mục"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ONLINE, classifyNgocSiAiLocalCommand("Mở các nguồn Online"))
    }

    @Test
    fun recognizesNaturalNavigationVariantsWithoutOnlineAI() {
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Quay lại trang chủ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Mở màn hình chính"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Quay lại"))

        assertEquals(NgocSiAiLocalCommand.OPEN_LIBRARY, classifyNgocSiAiLocalCommand("Chuyển đến thư viện"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiLocalCommand("Chuyển đến Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiLocalCommand("Mở ứng dụng Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Chuyển sang Radio"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Chuyển đến đài phát thanh"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Vào mục truyền hình"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Chuyển sang TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYLISTS, classifyNgocSiAiLocalCommand("Chuyển đến danh sách phát"))
        assertEquals(NgocSiAiLocalCommand.OPEN_QUEUE, classifyNgocSiAiLocalCommand("Vào hàng đợi"))
        assertEquals(NgocSiAiLocalCommand.OPEN_PLAYER, classifyNgocSiAiLocalCommand("Mở màn hình phát nhạc"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SETTINGS, classifyNgocSiAiLocalCommand("Chuyển sang cài đặt"))

        assertNull(classifyNgocSiAiLocalCommand("Tôi đang nghe Radio"))
        assertNull(classifyNgocSiAiLocalCommand("Tôi thích xem TV"))
    }

    @Test
    fun recognizesNaturalPhrasesToNavigateToYouTubeWithoutOnlineAI() {
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Chuyển đến YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Chuyển đến một YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Vào mục YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, chuyển sang YouTube")
        )
    }

}

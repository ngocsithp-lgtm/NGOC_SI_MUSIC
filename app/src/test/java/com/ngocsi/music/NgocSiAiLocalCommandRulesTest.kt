package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiLocalCommandRulesTest {

    @Test
    fun recognizesNaturalVietnamesePlaybackFollowUps() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Nghe tiếp bài đang phát"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Cho bài đang phát phát tiếp"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tạm ngưng nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Ngưng phát nhạc tạm thời"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Chuyển sang bài kế"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Kế tiếp bài hát"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Quay về bài vừa rồi"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Trở lại bài vừa rồi"))
    }

    @Test
    fun recognizesSafeMediaVolumeCommandsInWakeWordSessions() {
        assertEquals(NgocSiAiLocalCommand.VOLUME_UP, classifyNgocSiAiLocalCommand("Tăng âm lượng"))
        assertEquals(NgocSiAiLocalCommand.VOLUME_UP, classifyNgocSiAiWakePhrase("AI, tăng tiếng lên"))
        assertEquals(NgocSiAiLocalCommand.VOLUME_DOWN, classifyNgocSiAiWakePhrase("AI, giảm âm lượng"))
        assertEquals(NgocSiAiLocalCommand.MUTE, classifyNgocSiAiWakePhrase("AI, tắt tiếng"))
        assertEquals(NgocSiAiLocalCommand.UNMUTE, classifyNgocSiAiActivatedCommand("AI, bật tiếng"))
        assertNull(classifyNgocSiAiWakePhrase("Tăng âm lượng"))
        assertEquals(false, ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.VOLUME_UP))
        assertEquals(false, ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.MUTE))
    }

    @Test
    fun geminiVolumeActionsMapToTheSameAllowlistedLocalCommands() {
        val actions = listOf(
            "volume_up" to NgocSiAiLocalCommand.VOLUME_UP,
            "volume_down" to NgocSiAiLocalCommand.VOLUME_DOWN,
            "mute" to NgocSiAiLocalCommand.MUTE,
            "unmute" to NgocSiAiLocalCommand.UNMUTE
        )
        actions.forEach { (action, expected) ->
            val phrase = buildNgocSiAiToolCommandPhrase(action)
            assertEquals(expected, classifyNgocSiAiActivatedCommand("AI, $phrase"))
        }
        assertNull(buildNgocSiAiToolCommandPhrase("volume_set"))
    }

    @Test
    fun waitsForTheLivePlayerOnlyForDirectPlaybackControls() {
        assertEquals(
            true,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.STOP)
        )
        assertEquals(
            true,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.PAUSE)
        )
        assertEquals(
            true,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.NEXT)
        )
        assertEquals(
            true,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.SHUFFLE_ON)
        )
        assertEquals(
            false,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.OPEN_HOME)
        )
        assertEquals(
            false,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.OPEN_YOUTUBE)
        )
        assertEquals(
            false,
            ngocSiAiCommandRequiresPlayerConnection(NgocSiAiLocalCommand.SEARCH_YOUTUBE)
        )
    }

    @Test
    fun selectsTheMediaSessionThatIsActuallyPlaying() {
        assertEquals(
            NgocSiAiPlaybackTarget.PRODUCTION,
            selectNgocSiAiPlaybackTarget(
                productionConnected = true,
                productionActive = true,
                productionHasItems = true,
                previewConnected = true,
                previewActive = false,
                previewHasItems = true
            )
        )
        assertEquals(
            NgocSiAiPlaybackTarget.PREVIEW,
            selectNgocSiAiPlaybackTarget(
                productionConnected = true,
                productionActive = false,
                productionHasItems = true,
                previewConnected = true,
                previewActive = true,
                previewHasItems = true
            )
        )
    }

    @Test
    fun prefersExistingProductionQueueButUsesPreviewForAnEmptyFirstRun() {
        assertEquals(
            NgocSiAiPlaybackTarget.PRODUCTION,
            selectNgocSiAiPlaybackTarget(
                productionConnected = true,
                productionActive = false,
                productionHasItems = true,
                previewConnected = true,
                previewActive = false,
                previewHasItems = true
            )
        )
        assertEquals(
            NgocSiAiPlaybackTarget.PREVIEW,
            selectNgocSiAiPlaybackTarget(
                productionConnected = true,
                productionActive = false,
                productionHasItems = false,
                previewConnected = true,
                previewActive = false,
                previewHasItems = false
            )
        )
        assertEquals(
            null,
            selectNgocSiAiPlaybackTarget(
                productionConnected = false,
                productionActive = false,
                productionHasItems = false,
                previewConnected = false,
                previewActive = false,
                previewHasItems = false
            )
        )
    }

    @Test
    fun hardStopNeverTargetsAnIdlePreviewWhenProductionSessionIsMissing() {
        assertEquals(
            null,
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = false,
                productionActive = false,
                previewConnected = true,
                previewActive = false
            )
        )
        assertEquals(
            NgocSiAiPlaybackTarget.PRODUCTION,
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = true,
                productionActive = true,
                previewConnected = true,
                previewActive = false
            )
        )
        assertEquals(
            NgocSiAiPlaybackTarget.PREVIEW,
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = false,
                productionActive = false,
                previewConnected = true,
                previewActive = true
            )
        )
        assertEquals(
            NgocSiAiPlaybackTarget.PRODUCTION,
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = true,
                productionActive = false,
                previewConnected = true,
                previewActive = false
            )
        )
    }

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
    fun recognizesNaturalHomeAndYouTubeNavigationVariants() {
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiLocalCommand("Quay về trang chủ giúp tôi")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiLocalCommand("Trở lại trang chủ")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiWakePhrase("AI, về giao diện chính")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Mở YouTube lên")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, bật YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.RESUME_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, phát YouTube")
        )
    }

    @Test
    fun aiWakePhraseRecognizesImmediateHardStopWithoutOnlineAi() {
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiWakePhrase("AI, dừng hẳn nhạc")
        )
        assertEquals(true, isNgocSiAiExplicitStopPhrase("AI, dừng hẳn nhạc"))
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiWakePhrase("AI, đừng phát nhạc nữa")
        )
        assertEquals(
            false,
            isNgocSiAiExplicitStopPhrase("AI, dừng nhạc sau 15 phút")
        )
    }

    @Test
    fun directAiPrefixRoutesAppCommandsLocally() {
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("AI, mở YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_DRIVE,
            classifyNgocSiAiLocalCommand("AI, mở Google Drive")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_RADIO,
            classifyNgocSiAiLocalCommand("AI, vào Radio")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiLocalCommand("AI, đưa tôi về trang chủ")
        )
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiLocalCommand("AI, dừng hẳn nhạc")
        )
        assertNull(
            classifyNgocSiAiLocalCommand("AI, bạn có thể hướng dẫn tôi cách mở YouTube không?")
        )
    }

    @Test
    fun recognizesNaturalConversationalAliasesWithoutOnlineAi() {
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiLocalCommand("AI, đừng phát nhạc nữa")
        )
        assertEquals(true, isNgocSiAiExplicitStopPhrase("AI, đừng phát nhạc nữa"))
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiLocalCommand("Tắt nhạc luôn")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Tạm dừng bài hát một chút")
        )
        assertEquals(
            NgocSiAiLocalCommand.PLAY,
            classifyNgocSiAiLocalCommand("Phát nhạc tiếp đi")
        )
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiLocalCommand("Bỏ bài này đi")
        )
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiLocalCommand("Chuyển sang bài khác")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiLocalCommand("Về màn hình đầu")
        )
        assertEquals(
            NgocSiAiLocalCommand.HELP,
            classifyNgocSiAiLocalCommand("Bạn có thể làm gì vậy?")
        )
    }

    @Test
    fun recognizesShortEnglishPlaybackCommands() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiLocalCommand("Resume"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Pause music"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiLocalCommand("Next song"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiLocalCommand("Previous"))
    }

    @Test
    fun distinguishesYouTubeNavigationFromActualSearchTerms() {
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Mở YouTube lên")
        )
        assertNull(extractNgocSiAiYoutubeQuery("Mở YouTube lên"))
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, mở YouTube lên")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiLocalCommand("Mở YouTube nhạc Trịnh Công Sơn")
        )
        assertEquals(
            "nhạc Trịnh Công Sơn",
            extractNgocSiAiYoutubeQuery("Mở YouTube nhạc Trịnh Công Sơn")
        )
    }

    @Test
    fun recognizesHomeNavigationAndYouTubePlaybackRequests() {
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiLocalCommand("Quay lại trang chủ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_HOME, classifyNgocSiAiWakePhrase("AI, trở về màn hình chính"))
        assertEquals(NgocSiAiLocalCommand.RESUME_YOUTUBE, classifyNgocSiAiLocalCommand("Phát YouTube"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Phát YouTube nhạc Trịnh Công Sơn"))
        assertEquals("nhạc Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("Phát YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("AI, phát YouTube nhạc Trịnh Công Sơn"))
    }

    @Test
    fun recognizesGeneralOnlineMusicSearchAndQueueSaveCommands() {
        assertEquals(NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC, classifyNgocSiAiLocalCommand("Tìm nhạc Trịnh Công Sơn"))
        assertNull(extractNgocSiAiYoutubeQuery("Tìm nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC, classifyNgocSiAiWakePhrase("AI, tìm bài hát Bolero"))
        assertEquals("Trịnh Công Sơn", extractNgocSiAiOnlineQuery("Tìm nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiLocalCommand("Tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST, classifyNgocSiAiLocalCommand("Thêm hàng đợi vào playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST, classifyNgocSiAiWakePhrase("AI, lưu hàng đợi vào playlist Bolero"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiQueueToPlaylistQuery("Thêm hàng đợi vào playlist Nhạc Trịnh"))
        assertEquals("Bolero", extractNgocSiAiQueueToPlaylistQuery("AI, lưu hàng đợi vào playlist Bolero"))
    }

    @Test
    fun recognizesNaturalCurrentTrackAndAllQueuePlaylistCommands() {
        val currentTrackExamples = listOf(
            "AI, lưu bài này vào playlist Bolero" to "Bolero",
            "AI, thêm bài đang nghe vào playlist Nhạc Trịnh" to "Nhạc Trịnh",
            "Thêm bài hiện tại vào playlist Nhạc xưa" to "Nhạc xưa"
        )
        currentTrackExamples.forEach { (phrase, playlist) ->
            assertEquals(
                NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST,
                classifyNgocSiAiLocalCommand(phrase)
            )
            assertEquals(playlist, extractNgocSiAiAddToPlaylistQuery(phrase))
            assertEquals(
                if (phrase.startsWith("AI,")) NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST else null,
                classifyNgocSiAiWakePhrase(phrase)
            )
        }

        val queueExamples = listOf(
            "AI, thêm tất cả bài trong hàng đợi vào playlist Bolero" to "Bolero",
            "AI, lưu toàn bộ hàng đợi vào playlist Nhạc Trịnh" to "Nhạc Trịnh",
            "Thêm tất cả hàng đợi vào playlist Nhạc xưa" to "Nhạc xưa"
        )
        queueExamples.forEach { (phrase, playlist) ->
            assertEquals(
                NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST,
                classifyNgocSiAiLocalCommand(phrase)
            )
            assertEquals(playlist, extractNgocSiAiQueueToPlaylistQuery(phrase))
            assertEquals(
                if (phrase.startsWith("AI,")) NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST else null,
                classifyNgocSiAiWakePhrase(phrase)
            )
        }
    }

    @Test
    fun recognizesAddingCurrentTrackToPlaylist() {
        assertEquals(NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST, classifyNgocSiAiLocalCommand("Thêm bài đang phát vào playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST, classifyNgocSiAiWakePhrase("AI, thêm bài đang phát vào playlist Nhạc Trịnh"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiAddToPlaylistQuery("Thêm bài đang phát vào playlist Nhạc Trịnh"))
    }

    @Test
    fun recognizesExplicitYouTubePlaybackControlsWithoutConfusingLocalMusic() {
        assertEquals(NgocSiAiLocalCommand.PAUSE_YOUTUBE, classifyNgocSiAiLocalCommand("Tạm dừng YouTube"))
        assertEquals(NgocSiAiLocalCommand.RESUME_YOUTUBE, classifyNgocSiAiWakePhrase("AI, tiếp tục YouTube"))
        assertEquals(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, classifyNgocSiAiWakePhrase("AI, video YouTube tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, classifyNgocSiAiWakePhrase("AI, chuyển bài YouTube"))
        assertEquals(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, classifyNgocSiAiLocalCommand("Chuyển video trên YouTube"))
        assertEquals(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, classifyNgocSiAiLocalCommand("Bài tiếp theo YouTube"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO, classifyNgocSiAiLocalCommand("Video YouTube trước"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO, classifyNgocSiAiLocalCommand("Quay lại video YouTube"))
        assertEquals(NgocSiAiLocalCommand.RESUME_YOUTUBE, classifyNgocSiAiLocalCommand("Phát lại video YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON, classifyNgocSiAiLocalCommand("Bật phát ngẫu nhiên YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF, classifyNgocSiAiWakePhrase("AI, tắt phát ngẫu nhiên YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF, classifyNgocSiAiLocalCommand("Tắt lặp YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL, classifyNgocSiAiWakePhrase("AI, lặp hàng đợi YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE, classifyNgocSiAiLocalCommand("Lặp video YouTube"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_FAVORITE, classifyNgocSiAiWakePhrase("AI, thích video YouTube này"))
        assertEquals(NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER, classifyNgocSiAiLocalCommand("Lưu video YouTube xem sau"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tạm dừng nhạc"))
    }

    @Test
    fun genericTransportCommandsFollowTheSelectedYouTubeSource() {
        assertEquals("play", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PLAY, true))
        assertEquals("pause", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PAUSE, true))
        assertEquals("stop", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.STOP, true))
        assertEquals("next", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.NEXT, true))
        assertEquals("previous", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PREVIOUS, true))
        assertNull(ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.SHUFFLE_ON, true))
        assertNull(ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.SHUFFLE_OFF, true))
        assertNull(ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.REPEAT_OFF, true))
        assertNull(ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.REPEAT_ALL, true))
        assertNull(ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.REPEAT_ONE, true))
    }

    @Test
    fun genericTransportCommandsRemainOnNativePlayerWhenYouTubeIsNotSelected() {
        listOf(
            NgocSiAiLocalCommand.PLAY,
            NgocSiAiLocalCommand.PAUSE,
            NgocSiAiLocalCommand.STOP,
            NgocSiAiLocalCommand.NEXT,
            NgocSiAiLocalCommand.PREVIOUS,
            NgocSiAiLocalCommand.SHUFFLE_ON,
            NgocSiAiLocalCommand.SHUFFLE_OFF,
            NgocSiAiLocalCommand.REPEAT_OFF,
            NgocSiAiLocalCommand.REPEAT_ALL,
            NgocSiAiLocalCommand.REPEAT_ONE
        ).forEach { command ->
            assertNull(ngocSiAiYouTubeActionForCommand(command, false))
        }
    }

    @Test
    fun explicitYouTubeCommandsAlwaysTargetYouTube() {
        assertEquals("play", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.RESUME_YOUTUBE, false))
        assertEquals("pause", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PAUSE_YOUTUBE, false))
        assertEquals("next", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO, false))
        assertEquals("previous", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO, false))
        assertEquals("favorite", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.YOUTUBE_FAVORITE, false))
        assertEquals("watch_later", ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER, false))
    }

    @Test
    fun recognizesNamedPlaylistCreateAndPlayCommandsWithAndWithoutAccents() {
        assertEquals(NgocSiAiLocalCommand.CREATE_PLAYLIST, classifyNgocSiAiLocalCommand("Tạo playlist Nhạc Trịnh"))
        assertEquals(NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST, classifyNgocSiAiWakePhrase("AI, phát playlist Nhạc Trịnh"))
        assertEquals("Nhạc Trịnh", extractNgocSiAiPlaylistQuery("Tạo playlist Nhạc Trịnh", create = true))
        assertEquals("Nhạc Trịnh", extractNgocSiAiPlaylistQuery("AI, phát playlist Nhạc Trịnh", create = false))
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiWakePhrase("AI, mở bài Yesterday"))
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiWakePhrase("AI, phát Radio VOV3"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiWakePhrase("AI, mở VTV3"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("AI, tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals("nhạc Trịnh Công Sơn", extractNgocSiAiYoutubeQuery("AI, tìm YouTube nhạc Trịnh Công Sơn"))
    }

    @Test
    fun recognizesNamedRadioStationsAndTvChannels() {
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiLocalCommand("Phát Radio VOV3"))
        assertEquals(NgocSiAiLocalCommand.PLAY_RADIO_STATION, classifyNgocSiAiWakePhrase("AI, mở Radio VOH FM 95.6"))
        assertEquals("VOV3", extractNgocSiAiRadioStationQuery("Phát Radio VOV3"))
        assertEquals("VOH FM 95.6", extractNgocSiAiRadioStationQuery("AI, mở Radio VOH FM 95.6"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiLocalCommand("Mở VTV3"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV_CHANNEL, classifyNgocSiAiWakePhrase("AI, mở kênh HTV7"))
        assertEquals("VTV3", extractNgocSiAiTvChannelQuery("Mở VTV3"))
        assertEquals("HTV7", extractNgocSiAiTvChannelQuery("AI, mở kênh HTV7"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiLocalCommand("Mở TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiLocalCommand("Mở Radio"))
        assertNull(extractNgocSiAiRadioStationQuery("Mở Radio"))
        assertNull(extractNgocSiAiTvChannelQuery("Mở TV"))
    }

    @Test
    fun recognizesNamedSongPlaybackCommands() {
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiLocalCommand("Phát bài hát Diễm xưa"))
        assertEquals(NgocSiAiLocalCommand.PLAY_TRACK, classifyNgocSiAiWakePhrase("AI, mở bài Yesterday"))
        assertEquals("Diễm xưa", extractNgocSiAiTrackQuery("Phát bài hát Diễm xưa"))
        assertEquals("Yesterday", extractNgocSiAiTrackQuery("AI, mở bài Yesterday"))
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
    fun wakePhraseRequiresAiAndRecognizesExplicitCommands() {
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, chuyển bài"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, chuyển sang bài tiếp theo nhé"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, bài tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiWakePhrase("AI, mở nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiWakePhrase("AI, dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiWakePhrase("AI tạm dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.SHUFFLE_ON, classifyNgocSiAiWakePhrase("AI, bật phát ngẫu nhiên"))
        assertEquals(NgocSiAiLocalCommand.REPEAT_ONE, classifyNgocSiAiWakePhrase("AI, lặp một bài"))
        assertNull(classifyNgocSiAiWakePhrase("Chuyển bài"))
        assertNull(classifyNgocSiAiWakePhrase("AI"))
        assertNull(classifyNgocSiAiWakePhrase("Tôi nghe AI chuyển bài"))
        assertNull(classifyNgocSiAiWakePhrase("AI, làm thế nào để chuyển bài?"))
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
    fun distinguishesHardStopFromPauseAndRequiresWakePhraseInWakeMode() {
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiLocalCommand("Dừng hẳn nhạc"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiLocalCommand("Ngọc Sĩ, dừng hẳn nhạc"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiLocalCommand("Dừng nhạc hoàn toàn"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiLocalCommand("Ngừng hẳn nhạc"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiLocalCommand("Dừng hoàn toàn"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiLocalCommand("Tạm dừng nhạc"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiWakePhrase("AI, dừng hẳn nhạc"))
        assertNull(classifyNgocSiAiWakePhrase("Dừng hẳn nhạc"))
    }

    @Test
    fun prioritizesHardStopIntentWithoutCapturingTimedStopRequests() {
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Ngọc Sĩ, dừng hẳn nhạc"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Ngọc Sĩ ơi, dừng hẳn nhạc đang phát"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Dừng nhạc hoàn toàn"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Ngọc Sĩ, tắt nhạc đi"))

        assertEquals(false, isNgocSiAiExplicitStopPhrase("Tạm dừng nhạc"))
        assertEquals(false, isNgocSiAiExplicitStopPhrase("Tắt nhạc sau 15 phút"))
        assertEquals(false, isNgocSiAiExplicitStopPhrase("Dừng nhạc sau 30 phút"))
        assertEquals(
            NgocSiAiLocalCommand.SLEEP_TIMER_15,
            classifyNgocSiAiLocalCommand("Tắt nhạc sau 15 phút")
        )
        assertEquals(
            NgocSiAiLocalCommand.SLEEP_TIMER_30,
            classifyNgocSiAiLocalCommand("Dừng nhạc sau 30 phút")
        )
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
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, chuyển sang bài kế tiếp"))
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
            "AI, chuyển bài",
            selectNgocSiAiWakePhraseCandidate(listOf("chuyển bài", "AI, chuyển bài", "AI"))
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

        // Spoken numbers and hour units must resolve to the same preset timers.
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_15, classifyNgocSiAiLocalCommand("AI, hẹn giờ mười lăm phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_15, classifyNgocSiAiLocalCommand("AI, tắt nhạc sau mười lăm phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_60, classifyNgocSiAiLocalCommand("AI, hẹn giờ một giờ"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_120, classifyNgocSiAiLocalCommand("AI, hẹn giờ hai tiếng"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_60, classifyNgocSiAiActivatedCommand("AI, hẹn giờ một giờ"))

        // Durations without a preset remain available through the custom timer route.
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM, classifyNgocSiAiLocalCommand("AI, hẹn giờ 7 phút"))
        assertEquals(7, extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ 7 phút"))
        assertEquals(60, extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ một giờ"))
        assertEquals(120, extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ hai tiếng"))

        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Hủy hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.OPEN_SLEEP_TIMER, classifyNgocSiAiLocalCommand("Mở hẹn giờ"))
    }

    @Test
    fun recognizesNaturalHelpQuestionsWithoutSendingThemToGemini() {
        assertEquals(
            NgocSiAiLocalCommand.HELP,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ, hôm nay bạn có thể giúp tôi việc gì?")
        )
        assertEquals(
            NgocSiAiLocalCommand.HELP,
            classifyNgocSiAiWakePhrase("AI, hôm nay bạn có thể giúp tôi việc gì?")
        )
        assertEquals(
            NgocSiAiLocalCommand.HELP,
            classifyNgocSiAiLocalCommand("Hôm nay bạn giúp được gì?")
        )
        assertEquals(
            NgocSiAiLocalCommand.HELP,
            classifyNgocSiAiLocalCommand("Các chức năng của bạn")
        )
        assertNull(
            classifyNgocSiAiLocalCommand("Bạn có thể giúp tôi kiểm tra app bị lỗi không?")
        )
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
    fun recognizesCompoundYouTubeSearchAndPlayAsOneCommand() {
        val examples = listOf(
            "AI, mở YouTube, tìm nhạc Bolero rồi phát." to "Bolero",
            "Mở YouTube tìm nhạc Bolero rồi phát" to "Bolero",
            "Tìm nhạc Bolero trên YouTube rồi phát" to "Bolero",
            "Tìm YouTube nhạc Bolero rồi phát" to "Bolero"
        )
        examples.forEach { (phrase, query) ->
            assertEquals(
                NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
                classifyNgocSiAiLocalCommand(phrase)
            )
            assertEquals(query, extractNgocSiAiYoutubeAutoPlayQuery(phrase))
        }
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, mở YouTube, tìm nhạc Bolero rồi phát.")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, tìm YouTube nhạc Bolero rồi phát")
        )

        // A generic music search without an explicit YouTube target must not autoplay.
        assertNull(extractNgocSiAiYoutubeAutoPlayQuery("Tìm nhạc Bolero rồi phát"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiLocalCommand("Tìm YouTube nhạc Bolero")
        )
    }

    @Test
    fun searchAndPlayWithoutSourceUsesYouTubeOnlyWhenAiOpenedFromYouTube() {
        val phrase = "AI, tìm nhạc Bolero rồi phát"

        assertEquals(
            "Bolero",
            extractNgocSiAiContextualYoutubeAutoPlayQuery(phrase, "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiLocalCommand(phrase, "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiWakePhrase(phrase, "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiActivatedCommand("tìm nhạc Bolero rồi phát", "YouTube")
        )

        // On another page the source-neutral request keeps its general Online meaning.
        assertNull(extractNgocSiAiContextualYoutubeAutoPlayQuery(phrase, "Radio"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand(phrase, "Radio")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand("Tìm nhạc Bolero rồi phát")
        )

        // A search without an explicit play suffix stays on YouTube but never autoplays.
        val searchOnly = "Tìm nhạc Bolero"
        assertNull(extractNgocSiAiContextualYoutubeAutoPlayQuery(searchOnly, "YouTube"))
        assertEquals("Bolero", extractNgocSiAiContextualYoutubeQuery(searchOnly, "YouTube"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiLocalCommand(searchOnly, "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiWakePhrase("AI, tìm nhạc Bolero", "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiActivatedCommand("tìm nhạc Bolero", "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand(searchOnly, "Radio")
        )
        assertNull(extractNgocSiAiContextualYoutubeQuery(searchOnly, "Radio"))
        assertNull(extractNgocSiAiContextualYoutubeQuery(searchOnly, "Google Drive"))
        assertNull(
            extractNgocSiAiContextualYoutubeQuery(
                "Tìm nhạc Bolero trên Online",
                "YouTube"
            )
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand("Tìm nhạc Bolero trên Online", "YouTube")
        )
        assertNull(
            extractNgocSiAiContextualYoutubeQuery(
                "“AI, tìm nhạc Bolero”",
                "Radio"
            )
        )
    }

    @Test
    fun contextualYoutubeSearchOnlyDoesNotAutoplayOrHijackOtherSources() {
        assertEquals(
            "Bolero",
            extractNgocSiAiContextualYoutubeQuery("“AI, tìm nhạc Bolero”", "YouTube")
        )
        assertEquals(
            "Bolero",
            extractNgocSiAiContextualYoutubeQuery("Tìm kiếm bài hát Bolero", "YouTube")
        )
        assertNull(extractNgocSiAiContextualYoutubeQuery("Tìm nhạc Bolero rồi phát", "YouTube"))
        assertNull(extractNgocSiAiContextualYoutubeQuery("Tìm nhạc Bolero trong Drive", "YouTube"))
        assertNull(extractNgocSiAiContextualYoutubeQuery("Tìm nhạc Bolero trên Audius", "YouTube"))
    }

    @Test
    fun sourceNeutralDriveSearchUsesDriveOnlyOnTheDrivePage() {
        val phrase = "AI, tìm nhạc Bolero"

        assertEquals(
            "Bolero",
            extractNgocSiAiContextualDriveQuery(phrase, "Google Drive")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_DRIVE,
            classifyNgocSiAiLocalCommand(phrase, "Google Drive")
        )

        assertNull(extractNgocSiAiContextualDriveQuery(phrase, "Radio"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand(phrase, "Radio")
        )

        assertNull(
            extractNgocSiAiContextualDriveQuery(
                "AI, tìm nhạc Bolero trên YouTube",
                "Google Drive"
            )
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiLocalCommand("AI, tìm nhạc Bolero trên YouTube", "Google Drive")
        )
    }

    @Test
    fun quotedSearchAndPlayCommandIsStillParsedOnTheYouTubePage() {
        val quoted = "“AI, tìm nhạc Bolero rồi phát.”"
        assertEquals(
            "Bolero",
            extractNgocSiAiContextualYoutubeAutoPlayQuery(quoted, "YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
            classifyNgocSiAiLocalCommand(quoted, "YouTube")
        )
        assertEquals(
            "Bolero",
            extractNgocSiAiOnlineQuery("“AI, tìm nhạc Bolero”")
        )
        assertNull(extractNgocSiAiContextualYoutubeAutoPlayQuery(quoted, "Radio"))
    }

    @Test
    fun handsFreeYouTubeSearchAndPlayUsesItsOwnDestination() {
        assertEquals(
            "youtube_search_play",
            ngocSiAiWakeWordDestination(NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE)
        )
        assertEquals(
            "youtube",
            ngocSiAiWakeWordDestination(NgocSiAiLocalCommand.SEARCH_YOUTUBE)
        )
        assertEquals(
            "youtube",
            ngocSiAiWakeWordDestination(NgocSiAiLocalCommand.OPEN_YOUTUBE)
        )
        assertEquals(
            "youtube_command",
            ngocSiAiWakeWordDestination(
                NgocSiAiLocalCommand.RESUME_YOUTUBE,
                hasSavedYouTubeVideo = true
            )
        )
        assertEquals(
            "youtube",
            ngocSiAiWakeWordDestination(
                NgocSiAiLocalCommand.RESUME_YOUTUBE,
                hasSavedYouTubeVideo = false
            )
        )
    }

    @Test
    fun wakeWordCanActivateListeningForOneSubsequentPlaybackCommand() {
        assertEquals(true, isNgocSiAiWakeWordOnly("AI"))
        assertEquals(true, isNgocSiAiWakeWordOnly("AI ơi"))
        assertEquals(true, isNgocSiAiWakeWordOnly("OK AI"))
        assertEquals(false, isNgocSiAiWakeWordOnly("AI, chuyển bài"))

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
            "AI",
            selectNgocSiAiWakePhraseCandidate(listOf("tóc sĩ", "AI"))
        )
    }

    @Test
    fun wakeWordCanRouteAcrossAppFeaturesAndTimers() {
        assertEquals(NgocSiAiLocalCommand.OPEN_YOUTUBE, classifyNgocSiAiWakePhrase("AI, mở YouTube"))
        assertEquals(NgocSiAiLocalCommand.OPEN_DRIVE, classifyNgocSiAiWakePhrase("AI, mở Google Drive"))
        assertEquals(NgocSiAiLocalCommand.OPEN_RADIO, classifyNgocSiAiWakePhrase("AI, mở Radio"))
        assertEquals(NgocSiAiLocalCommand.OPEN_TV, classifyNgocSiAiWakePhrase("AI, mở TV"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FAVORITES, classifyNgocSiAiWakePhrase("AI, mở Yêu thích"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ALBUMS, classifyNgocSiAiWakePhrase("AI, mở Album"))
        assertEquals(NgocSiAiLocalCommand.OPEN_FOLDERS, classifyNgocSiAiWakePhrase("AI, mở Thư mục"))
        assertEquals(NgocSiAiLocalCommand.OPEN_ONLINE, classifyNgocSiAiWakePhrase("AI, mở Online"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_YOUTUBE, classifyNgocSiAiWakePhrase("AI, tìm YouTube nhạc Trịnh Công Sơn"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_30, classifyNgocSiAiWakePhrase("AI, hẹn giờ 30 phút"))
        assertEquals(NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER, classifyNgocSiAiWakePhrase("AI, hủy hẹn giờ"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, chuyển bài"))
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


    @Test
    fun activatedWakeWordCommandSelectionPrefersActionableSpeechHypothesis() {
        assertEquals(
            "chuyển bài",
            selectNgocSiAiActivatedCommandCandidate(listOf("chuyển bây", "chuyển bài", "chuyển về"))
        )
        assertEquals(
            "Ngọc Sĩ, mở YouTube lên",
            selectNgocSiAiActivatedCommandCandidate(listOf("một chuyện bài", "Ngọc Sĩ, mở YouTube lên"))
        )
        assertEquals(
            "câu không rõ",
            selectNgocSiAiActivatedCommandCandidate(listOf("câu không rõ", "lời khác"))
        )
        assertNull(selectNgocSiAiActivatedCommandCandidate(listOf("", "   ")))
    }


    @Test
    fun stopVoiceCandidateDoesNotOverrideAnEarlierPlaybackCommand() {
        assertNull(
            selectNgocSiAiStopVoiceControlCandidate(
                listOf("tạm dừng nhạc", "tạm dừng AI")
            )
        )
        assertNull(
            selectNgocSiAiStopVoiceControlCandidate(
                listOf("mở YouTube", "tắt điều khiển giọng nói")
            )
        )
    }

    @Test
    fun explicitStopVoiceCandidateStillWinsWhenItRanksFirst() {
        assertEquals(
            "tạm dừng AI",
            selectNgocSiAiStopVoiceControlCandidate(
                listOf("tạm dừng AI", "tạm dừng nhạc")
            )
        )
        assertNull(selectNgocSiAiStopVoiceControlCandidate(listOf("tạm dừng nhạc")))
        assertNull(selectNgocSiAiStopVoiceControlCandidate(emptyList()))
    }

    @Test
    fun acceptsPoliteVoiceFillersWithoutLosingNamedQueries() {
        assertEquals(
            NgocSiAiLocalCommand.PLAY,
            classifyNgocSiAiLocalCommand("Phát nhạc giúp tôi nhé")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Tạm dừng nhạc giúp tôi")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Mở YouTube lên nhé")
        )
        assertEquals(
            NgocSiAiLocalCommand.RESUME_YOUTUBE,
            classifyNgocSiAiLocalCommand("Phát YouTube nhé")
        )
        assertEquals(
            NgocSiAiLocalCommand.PLAY_TRACK,
            classifyNgocSiAiLocalCommand("Phát bài hát Diễm xưa đi")
        )
        assertEquals("Diễm xưa", extractNgocSiAiTrackQuery("Phát bài hát Diễm xưa đi"))
        assertEquals(
            NgocSiAiLocalCommand.PLAY_RADIO_STATION,
            classifyNgocSiAiLocalCommand("Phát Radio VOV3 giúp tôi")
        )
        assertEquals("VOV3", extractNgocSiAiRadioStationQuery("Phát Radio VOV3 giúp tôi"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_YOUTUBE,
            classifyNgocSiAiLocalCommand("Tìm YouTube nhạc Trịnh Công Sơn giúp tôi")
        )
        assertEquals(
            "nhạc Trịnh Công Sơn",
            extractNgocSiAiYoutubeQuery("Tìm YouTube nhạc Trịnh Công Sơn giúp tôi")
        )
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiWakePhrase("AI, tạm dừng nhạc giúp tôi")
        )
    }


    @Test
    fun understandsConversationalCommandsWithoutTreatingCapabilityQuestionsAsActions() {
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("Ngọc Sĩ ơi, bạn dừng nhạc giúp tôi nhé")
        )
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiLocalCommand("Tôi muốn bạn dừng hẳn nhạc")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiLocalCommand("Bạn mở YouTube giúp tôi được không")
        )
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiWakePhrase("AI ơi, bạn chuyển bài tiếp theo giúp mình")
        )
        assertEquals(
            true,
            isNgocSiAiExplicitStopPhrase("Tôi muốn bạn dừng hẳn nhạc")
        )
        assertNull(classifyNgocSiAiLocalCommand("Bạn có thể phát ngẫu nhiên không?"))
    }

    @Test
    fun understandsBroaderNaturalVietnamesePlaybackCommandsOffline() {
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiWakePhrase("AI, cho nhạc chạy tiếp"))
        assertEquals(NgocSiAiLocalCommand.PLAY, classifyNgocSiAiActivatedCommand("Nghe tiếp nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiWakePhrase("AI, ngừng phát nhạc"))
        assertEquals(NgocSiAiLocalCommand.PAUSE, classifyNgocSiAiActivatedCommand("Nghỉ một chút"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, đổi bài"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiActivatedCommand("Phát bài tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiWakePhrase("AI, tắt hẳn nhạc"))
        assertEquals(NgocSiAiLocalCommand.STOP, classifyNgocSiAiActivatedCommand("Kết thúc phát nhạc"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("AI, tắt hẳn nhạc"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Kết thúc phát nhạc"))
        assertEquals(true, isNgocSiAiExplicitStopPhrase("Dừng phát hoàn toàn"))
        assertEquals(false, isNgocSiAiExplicitStopPhrase("Tắt nhạc sau 30 phút"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_30, classifyNgocSiAiWakePhrase("AI, tắt nhạc sau 30 phút"))
        assertNull(classifyNgocSiAiWakePhrase("Nghe nhạc thật hay"))
    }

    
    @Test
    fun recognizesTheExplicitVoiceControlStopPhraseWithoutStoppingMusicCommands() {
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("tạm dừng AI"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, tạm dừng"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, tạm dừng đi"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("Ngọc Sĩ, tạm dừng"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, tạm dừng AI đi"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("dừng AI"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, ngừng lắng nghe"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("tắt micro"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, ngừng nhận lệnh giọng nói"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("AI, tạm dừng giọng nói"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("tạm dừng nghe"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("Ngọc Sĩ, tắt giọng nói"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("tắt điều khiển giọng nói"))
        assertEquals(true, isNgocSiAiStopVoiceControlPhrase("Ngừng nghe từ khóa"))

        // Voice-control exit phrases must not be reclassified as music playback pause.
        assertNull(classifyNgocSiAiLocalCommand("AI, tạm dừng giọng nói"))
        assertNull(classifyNgocSiAiLocalCommand("tạm dừng nghe"))
        assertEquals(
            NgocSiAiLocalCommand.PAUSE,
            classifyNgocSiAiLocalCommand("tạm dừng nhạc")
        )
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("nghe giọng nói"))
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("AI"))
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("tạm dừng"))
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("tạm dừng nhạc"))
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("dừng nghe nhạc"))
        assertEquals(false, isNgocSiAiStopVoiceControlPhrase("tắt nhạc sau 15 phút"))
    }

    @Test
    fun continuousWakeSessionRoutesCommonCommandsLocally() {
        assertEquals(
            NgocSiAiLocalCommand.NEXT,
            classifyNgocSiAiActivatedCommand("Bài tiếp theo")
        )
        assertEquals(
            NgocSiAiLocalCommand.STOP,
            classifyNgocSiAiActivatedCommand("Dừng hẳn nhạc")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            classifyNgocSiAiActivatedCommand("Mở YouTube")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_RADIO,
            classifyNgocSiAiActivatedCommand("Mở Radio")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_LIBRARY,
            classifyNgocSiAiActivatedCommand("Mở thư viện")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_HOME,
            classifyNgocSiAiActivatedCommand("Quay về trang chủ")
        )
    }

    @Test
    fun mapsOnlyAllowlistedGeminiActionsToExistingLocalCommands() {
        assertEquals("phát nhạc", buildNgocSiAiToolCommandPhrase("play"))
        assertEquals("tìm youtube nhạc Trịnh", buildNgocSiAiToolCommandPhrase("search_youtube", "nhạc Trịnh"))
        assertEquals("tua youtube tới 10 giây", buildNgocSiAiToolCommandPhrase("youtube_seek_forward_10"))
        assertEquals("tua youtube lùi 10 giây", buildNgocSiAiToolCommandPhrase("youtube_seek_back_10"))
        assertEquals("phát radio VOV3", buildNgocSiAiToolCommandPhrase("play_radio_station", "VOV3"))
        assertEquals("mở kênh VTV3", buildNgocSiAiToolCommandPhrase("open_tv_channel", "VTV3"))
        assertEquals(
            "thêm bài đang phát vào playlist Nhạc Trịnh",
            buildNgocSiAiToolCommandPhrase("add_current_to_playlist", "Nhạc Trịnh")
        )
        assertEquals(null, buildNgocSiAiToolCommandPhrase("delete_all_library"))
        assertEquals(null, buildNgocSiAiToolCommandPhrase("open_drive_and_clear"))
        assertEquals(null, buildNgocSiAiToolCommandPhrase("search_youtube"))
        assertEquals(null, buildNgocSiAiToolCommandPhrase("play_track", ""))
    }

    @Test
    fun mappedGeminiActionsStillPassTheDeterministicLocalCommandClassifier() {
        val cases = listOf(
            "play" to "",
            "play_second" to "",
            "play_track" to "Diễm xưa",
            "pause" to "",
            "stop" to "",
            "next" to "",
            "previous" to "",
            "current_track" to "",
            "set_playback_speed" to "1.5",
            "seek_forward_10" to "",
            "seek_back_10" to "",
            "favorite_current_track" to "",
            "shuffle_on" to "",
            "shuffle_off" to "",
            "repeat_off" to "",
            "repeat_all" to "",
            "repeat_one" to "",
            "open_home" to "",
            "open_library" to "",
            "open_favorites" to "",
            "open_albums" to "",
            "open_folders" to "",
            "open_online" to "",
            "search_online_music" to "nhạc Trịnh",
            "open_youtube" to "",
            "search_youtube" to "nhạc Trịnh",
            "youtube_play" to "",
            "youtube_pause" to "",
            "youtube_next" to "",
            "youtube_previous" to "",
            "youtube_seek_forward_10" to "",
            "youtube_seek_back_10" to "",
            "youtube_shuffle_on" to "",
            "youtube_shuffle_off" to "",
            "youtube_repeat_off" to "",
            "youtube_repeat_all" to "",
            "youtube_repeat_one" to "",
            "youtube_favorite" to "",
            "youtube_watch_later" to "",
            "open_drive" to "",
            "open_radio" to "",
            "play_radio_station" to "VOV3",
            "open_tv" to "",
            "open_tv_channel" to "VTV3",
            "create_playlist" to "Nhạc Trịnh",
            "play_playlist" to "Nhạc Trịnh",
            "add_current_to_playlist" to "Nhạc Trịnh",
            "add_queue_to_playlist" to "Nhạc Trịnh",
            "open_playlists" to "",
            "open_queue" to "",
            "open_player" to "",
            "open_settings" to "",
            "open_sleep_timer" to "",
            "sleep_timer_15" to "",
            "sleep_timer_30" to "",
            "sleep_timer_45" to "",
            "sleep_timer_60" to "",
            "sleep_timer_90" to "",
            "sleep_timer_120" to "",
            "cancel_sleep_timer" to "",
            "help" to ""
        )
        cases.forEach { (action, query) ->
            val phrase = buildNgocSiAiToolCommandPhrase(action, query)
            assertEquals("Mapped action $action should classify", true, phrase != null)
            assertEquals("Mapped action $action should use a known command", true,
                classifyNgocSiAiLocalCommand(phrase!!) != null)
        }
    }


    @Test
    fun mapsPlaybackSpeedSeekAndCurrentFavoriteActionsSafely() {
        assertEquals(
            "đặt tốc độ phát 1.5x",
            buildNgocSiAiToolCommandPhrase("set_playback_speed", "1.5")
        )
        assertEquals("tua tới 10 giây", buildNgocSiAiToolCommandPhrase("seek_forward_10"))
        assertEquals("lùi 10 giây", buildNgocSiAiToolCommandPhrase("seek_back_10"))
        assertEquals(
            "thêm bài đang phát vào yêu thích",
            buildNgocSiAiToolCommandPhrase("favorite_current_track")
        )
        assertEquals(null, buildNgocSiAiToolCommandPhrase("set_playback_speed", "3.0"))
        assertEquals(null, buildNgocSiAiToolCommandPhrase("set_playback_speed", ""))

        assertEquals(
            1.5f,
            extractNgocSiAiPlaybackSpeed("Đặt tốc độ phát 1.5x") ?: Float.NaN,
            0.001f
        )
        assertEquals(
            0.75f,
            extractNgocSiAiPlaybackSpeed("Tốc độ phát 0.75x") ?: Float.NaN,
            0.001f
        )
        assertEquals(null, extractNgocSiAiPlaybackSpeed("Đặt tốc độ phát 3.0x"))
        assertEquals(
            NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
            classifyNgocSiAiLocalCommand("Đặt tốc độ phát 1.5x")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiLocalCommand("Tua tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_10,
            classifyNgocSiAiLocalCommand("Lùi 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK,
            classifyNgocSiAiLocalCommand("Thêm bài đang phát vào yêu thích")
        )
    }

    @Test
    fun wakeWordAllowsOnlyExplicitPlaybackSpeedSeekAndFavoriteCommands() {
        assertEquals(
            NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
            classifyNgocSiAiActivatedCommand("AI, đặt tốc độ phát 1.5x")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiActivatedCommand("AI, tua tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_10,
            classifyNgocSiAiActivatedCommand("AI, lùi 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK,
            classifyNgocSiAiActivatedCommand("AI, thêm bài đang phát vào yêu thích")
        )
        assertNull(classifyNgocSiAiActivatedCommand("đặt tốc độ phát 1.5x"))
    }

    @Test
    fun recognizesVariableSeekDurationsForLocalAndYouTubePlayback() {
        assertEquals(
            NgocSiAiSeekRequest(30_000L, false),
            extractNgocSiAiSeekRequest("AI, tua tới 30 giây")
        )
        assertEquals(
            NgocSiAiSeekRequest(30_000L, false),
            extractNgocSiAiSeekRequest("AI, tua tới 30 giây nhé")
        )
        assertEquals(
            NgocSiAiSeekRequest(-15_000L, false),
            extractNgocSiAiSeekRequest("AI, lùi 15 giây")
        )
        assertEquals(
            NgocSiAiSeekRequest(60_000L, true),
            extractNgocSiAiSeekRequest("AI, tua YouTube tới một phút")
        )
        assertEquals(
            NgocSiAiSeekRequest(-120_000L, true),
            extractNgocSiAiSeekRequest("AI, tua YouTube lùi 2 phút")
        )
        assertNull(extractNgocSiAiSeekRequest("AI, tua tới 3 phút"))
        assertNull(extractNgocSiAiSeekRequest("AI, tua tới 0 giây"))
        assertNull(extractNgocSiAiSeekRequest("AI, tua tới 30"))

        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
            classifyNgocSiAiWakePhrase("AI, tua tới 30 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_CUSTOM,
            classifyNgocSiAiWakePhrase("AI, lùi 15 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM,
            classifyNgocSiAiWakePhrase("AI, tua YouTube tới 30 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM,
            classifyNgocSiAiWakePhrase("AI, tua YouTube lùi một phút")
        )
        assertNull(classifyNgocSiAiActivatedCommand("tua tới 30 giây"))
    }

    @Test
    fun variableSeekToolActionsAreBoundedAndRouteToTheExpectedPlayer() {
        assertEquals(
            "tua tới 30 giây",
            buildNgocSiAiToolCommandPhrase("seek_by_seconds", "30")
        )
        assertEquals(
            "lùi 15 giây",
            buildNgocSiAiToolCommandPhrase("seek_by_seconds", "-15")
        )
        assertEquals(
            "tua youtube tới 60 giây",
            buildNgocSiAiToolCommandPhrase("youtube_seek_by_seconds", "60")
        )
        assertNull(buildNgocSiAiToolCommandPhrase("seek_by_seconds", "0"))
        assertNull(buildNgocSiAiToolCommandPhrase("seek_by_seconds", "121"))
        assertNull(buildNgocSiAiToolCommandPhrase("youtube_seek_by_seconds", "-999"))

        assertEquals("seek_by_ms:30000", buildNgocSiAiYouTubeSeekAction(30_000L))
        assertEquals(-30_000L, parseNgocSiAiYouTubeSeekAction("seek_by_ms:-30000"))
        assertNull(parseNgocSiAiYouTubeSeekAction("seek_by_ms:999999"))
        assertNull(parseNgocSiAiYouTubeSeekAction("seek_by_ms:30001"))

        assertEquals(
            "seek_by_ms:30000",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM,
                youtubeActiveSource = false,
                seekDeltaMs = 30_000L
            )
        )
        assertEquals(
            "seek_by_ms:-15000",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.SEEK_BACK_CUSTOM,
                youtubeActiveSource = true,
                seekDeltaMs = -15_000L
            )
        )
        assertNull(
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
                youtubeActiveSource = false,
                seekDeltaMs = 30_000L
            )
        )
    }

    @Test
    fun inlineYouTubeManualQueueRespectsShuffleAndRepeatBoundaries() {
        assertEquals(
            2,
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 1,
                queueSize = 4,
                offset = 1,
                shuffleEnabled = false,
                repeatMode = 0
            )
        )
        assertNull(
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 3,
                queueSize = 4,
                offset = 1,
                shuffleEnabled = false,
                repeatMode = 0
            )
        )
        assertEquals(
            0,
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 3,
                queueSize = 4,
                offset = 1,
                shuffleEnabled = false,
                repeatMode = 1
            )
        )
        assertEquals(
            3,
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 4,
                offset = -1,
                shuffleEnabled = false,
                repeatMode = 1
            )
        )
        assertEquals(
            2,
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 4,
                offset = 1,
                shuffleEnabled = true,
                repeatMode = 0,
                shuffleTargetIndex = 2
            )
        )
        assertNull(
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 4,
                offset = 1,
                shuffleEnabled = true,
                repeatMode = 0,
                shuffleTargetIndex = 0
            )
        )
        assertNull(
            selectNgocSiAiManualYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 4,
                offset = -1,
                shuffleEnabled = true,
                repeatMode = 0
            )
        )
    }

    @Test
    fun inlineYouTubeEndedQueueRespectsRepeatOneShuffleAndRepeatAll() {
        assertEquals(
            1,
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 1,
                queueSize = 4,
                shuffleEnabled = false,
                repeatMode = 2
            )
        )
        assertEquals(
            2,
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 1,
                queueSize = 4,
                shuffleEnabled = false,
                repeatMode = 0
            )
        )
        assertNull(
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 3,
                queueSize = 4,
                shuffleEnabled = false,
                repeatMode = 0
            )
        )
        assertEquals(
            0,
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 3,
                queueSize = 4,
                shuffleEnabled = false,
                repeatMode = 1
            )
        )
        assertEquals(
            2,
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 1,
                queueSize = 4,
                shuffleEnabled = true,
                repeatMode = 0,
                shuffleTargetIndex = 2
            )
        )
        assertEquals(
            0,
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 1,
                shuffleEnabled = true,
                repeatMode = 1
            )
        )
        assertNull(
            selectNgocSiAiEndedYouTubeQueueIndex(
                currentIndex = 0,
                queueSize = 0,
                shuffleEnabled = false,
                repeatMode = 0
            )
        )
    }


    @Test
    fun genericCurrentTrackQueryFollowsTheActiveYouTubeSource() {
        assertEquals(
            "current_track",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.CURRENT_TRACK,
                youtubeActiveSource = true
            )
        )
        assertNull(
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.CURRENT_TRACK,
                youtubeActiveSource = false
            )
        )
    }



    @Test
    fun youtubeAiDispatchUsesLiveControlHostAndFallsBackAfterProcessDeath() {
        assertEquals(
            NgocSiAiYouTubeDispatchTarget.OPEN_YOUTUBE,
            selectNgocSiAiYouTubeDispatchTarget(
                hasSavedVideo = false,
                liveControlHostAvailable = false
            )
        )
        assertEquals(
            NgocSiAiYouTubeDispatchTarget.LIVE_CONTROL_HOST,
            selectNgocSiAiYouTubeDispatchTarget(
                hasSavedVideo = true,
                liveControlHostAvailable = true
            )
        )
        // A saved video with no live Activity must use MainActivity's inline player;
        // an old persisted "Activity active" flag is deliberately not part of this policy.
        assertEquals(
            NgocSiAiYouTubeDispatchTarget.MAIN_INLINE_PLAYER,
            selectNgocSiAiYouTubeDispatchTarget(
                hasSavedVideo = true,
                liveControlHostAvailable = false
            )
        )
    }

    @Test
    fun wakeWordSeekCommandsPreserveExplicitAddressAndAcceptSpokenNumberForms() {
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiWakePhrase("AI, tua tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_10,
            classifyNgocSiAiWakePhrase("AI, tua lùi 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiWakePhrase("AI, tua tới mười giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_10,
            classifyNgocSiAiWakePhrase("AI, tua lùi mười giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
            classifyNgocSiAiWakePhrase("AI, tua YouTube tới mười giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
            classifyNgocSiAiWakePhrase("AI, tua YouTube lùi mười giây")
        )
        assertNull(classifyNgocSiAiWakePhrase("tua tới 10 giây"))
    }

    @Test
    fun youtubeSeekActionsFollowTheSelectedSourceAndAcceptExplicitVideoPhrases() {
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
            classifyNgocSiAiLocalCommand("Tua YouTube tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
            classifyNgocSiAiLocalCommand("Tua YouTube lùi 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
            classifyNgocSiAiActivatedCommand("AI, tua YouTube tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
            classifyNgocSiAiActivatedCommand("AI, tua YouTube lùi 10 giây")
        )
        assertEquals(
            "seek_forward_10",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
                youtubeActiveSource = false
            )
        )
        assertEquals(
            "seek_back_10",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
                youtubeActiveSource = false
            )
        )
        assertEquals(
            "seek_forward_10",
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.SEEK_FORWARD_10,
                youtubeActiveSource = true
            )
        )
        assertNull(
            ngocSiAiYouTubeActionForCommand(
                NgocSiAiLocalCommand.SEEK_FORWARD_10,
                youtubeActiveSource = false
            )
        )
    }

    @Test
    fun geminiSeekToolCommandsPassAllowlistInsideAnActiveAiSession() {
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiActivatedCommand(
                "AI, ${buildNgocSiAiToolCommandPhrase("seek_forward_10")}"
            )
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_BACK_10,
            classifyNgocSiAiActivatedCommand(
                "AI, ${buildNgocSiAiToolCommandPhrase("seek_back_10")}"
            )
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
            classifyNgocSiAiActivatedCommand(
                "AI, ${buildNgocSiAiToolCommandPhrase("youtube_seek_forward_10")}"
            )
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
            classifyNgocSiAiActivatedCommand(
                "AI, ${buildNgocSiAiToolCommandPhrase("youtube_seek_back_10")}"
            )
        )
        // The explicit-address guard must remain active outside the AI session.
        assertNull(classifyNgocSiAiActivatedCommand("tua tới 10 giây"))
        assertNull(classifyNgocSiAiActivatedCommand("tua YouTube lùi 10 giây"))
    }

    @Test
    fun recognizesShortVideoNavigationPhrasesAndKeepsTheYouTubeSource() {
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, video tiếp theo"))
        assertEquals(NgocSiAiLocalCommand.NEXT, classifyNgocSiAiWakePhrase("AI, chuyển video kế tiếp"))
        assertEquals(NgocSiAiLocalCommand.PREVIOUS, classifyNgocSiAiWakePhrase("AI, video trước đó"))
        assertEquals(
            "next",
            ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.NEXT, youtubeActiveSource = true)
        )
        assertEquals(
            "previous",
            ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.PREVIOUS, youtubeActiveSource = true)
        )
        assertNull(
            ngocSiAiYouTubeActionForCommand(NgocSiAiLocalCommand.NEXT, youtubeActiveSource = false)
        )
    }
    @Test
    fun acceptsNaturalWakeWordVariantsWithoutAllowingCommandsWithoutWakeWord() {
        assertEquals(true, isNgocSiAiWakeWordOnly("Hey AI ơi"))
        assertEquals(true, isNgocSiAiWakeWordOnly("OK AI ơi"))
        assertEquals(true, isNgocSiAiWakeWordOnly("Alo AI ơi"))
        assertEquals(false, isNgocSiAiWakeWordOnly("mở Radio"))
        assertEquals(
            NgocSiAiLocalCommand.OPEN_RADIO,
            classifyNgocSiAiWakePhrase("Hey AI, mở Radio")
        )
        assertEquals(
            NgocSiAiLocalCommand.OPEN_TV,
            classifyNgocSiAiWakePhrase("OK AI, mở truyền hình")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEEK_FORWARD_10,
            classifyNgocSiAiWakePhrase("Alo AI, tua tới 10 giây")
        )
        assertEquals(
            NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM,
            classifyNgocSiAiWakePhrase("AI ơi, tua YouTube lùi 15 giây")
        )
        assertNull(classifyNgocSiAiWakePhrase("mở Radio"))
        assertNull(classifyNgocSiAiWakePhrase("tua tới 10 giây"))
    }

}

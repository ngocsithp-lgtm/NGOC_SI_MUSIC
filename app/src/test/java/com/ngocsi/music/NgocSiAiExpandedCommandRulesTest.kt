package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiExpandedCommandRulesTest {
    @Test
    fun queueInsertionRecognizesExplicitSongAndDestination() {
        val phrase = "AI, thêm bài hát Diễm xưa vào hàng đợi"
        assertEquals("Diễm xưa", extractNgocSiAiQueueTrackQuery(phrase))
        assertEquals(NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE, classifyNgocSiAiLocalCommand(phrase))
        assertEquals(NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE, classifyNgocSiAiWakePhrase(phrase))
        assertEquals(NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE, classifyNgocSiAiActivatedCommand(phrase))
    }

    @Test
    fun driveSearchRecognizesDriveFirstAndSongFirstPhrasing() {
        assertEquals("Bolero", extractNgocSiAiDriveQuery("AI, tìm Drive Bolero"))
        assertEquals("Diễm xưa", extractNgocSiAiDriveQuery("Tìm bài hát Diễm xưa trong Drive"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_DRIVE, classifyNgocSiAiLocalCommand("Tìm Drive Bolero"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_DRIVE, classifyNgocSiAiWakePhrase("AI, tìm Drive Bolero"))
    }

    @Test
    fun sourceNeutralMusicSearchUsesDriveOnlyWhenAiOpenedFromDrive() {
        val phrase = "AI, tìm nhạc Bolero"
        assertEquals("Bolero", extractNgocSiAiContextualDriveQuery(phrase, "Google Drive"))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_DRIVE,
            classifyNgocSiAiLocalCommand(phrase, "Google Drive")
        )
        assertEquals(
            "Diễm xưa",
            extractNgocSiAiContextualDriveQuery("Tìm bài hát Diễm xưa trong thư mục này", "Drive")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_DRIVE,
            classifyNgocSiAiWakePhrase("AI, tìm bài hát Diễm xưa", "Drive")
        )

        // An explicit source always beats the page context.
        assertNull(extractNgocSiAiContextualDriveQuery("Tìm nhạc Bolero trên YouTube", "Drive"))
        assertNull(extractNgocSiAiContextualDriveQuery(phrase, "Radio"))
        assertNull(extractNgocSiAiContextualDriveQuery(phrase, null))
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand(phrase, "Radio")
        )
        assertEquals(
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
            classifyNgocSiAiLocalCommand(phrase)
        )
    }

    @Test
    fun nonActionsRemainQuestions() {
        assertNull(classifyNgocSiAiLocalCommand("Làm thế nào để tìm nhạc trong Drive?"))
        assertNull(extractNgocSiAiQueueTrackQuery("Tôi thích bài hát Diễm xưa"))
    }

    @Test
    fun newActionPhrasesOfferTheConstrainedToolButQuestionsDoNot() {
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tìm Drive Bolero"))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Thêm bài hát Diễm xưa vào hàng đợi"))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Làm thế nào để tìm nhạc trong Drive?"))
    }

    @Test
    fun customSleepTimerAcceptsNumericAndSpokenMinutesAndHours() {
        assertEquals(1, extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ tắt nhạc sau một phút"))
        assertEquals(7, extractNgocSiAiSleepTimerMinutes("AI, đặt hẹn giờ 7 phút"))
        assertEquals(120, extractNgocSiAiSleepTimerMinutes("AI ơi, hẹn giờ sau hai giờ"))
        assertEquals(180, extractNgocSiAiSleepTimerMinutes("Đặt hẹn giờ 3 tiếng"))

        assertEquals(
            NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM,
            classifyNgocSiAiWakePhrase("AI, hẹn giờ 1 phút")
        )
        assertEquals(
            NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM,
            classifyNgocSiAiActivatedCommand("AI, hẹn giờ 5 phút")
        )
        assertNull(extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ 0 phút"))
        assertNull(extractNgocSiAiSleepTimerMinutes("AI, hẹn giờ 25 giờ"))
        assertNull(extractNgocSiAiSleepTimerMinutes("Tôi nghe podcast dài một tiếng"))
    }

    @Test
    fun modelActionsUseTheSameAllowlistedLocalRouter() {
        val add = buildNgocSiAiToolCommandPhrase("add_track_to_queue", "Diễm xưa")
        val search = buildNgocSiAiToolCommandPhrase("search_drive", "Bolero")
        val timer = buildNgocSiAiToolCommandPhrase("sleep_timer_custom", "5")
        assertEquals(NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE, classifyNgocSiAiActivatedCommand("AI, $add"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_DRIVE, classifyNgocSiAiActivatedCommand("AI, $search"))
        assertEquals(NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM, classifyNgocSiAiActivatedCommand("AI, $timer"))
        assertNull(buildNgocSiAiToolCommandPhrase("add_track_to_queue", ""))
        assertNull(buildNgocSiAiToolCommandPhrase("search_drive", ""))
        assertNull(buildNgocSiAiToolCommandPhrase("sleep_timer_custom", "1441"))
    }

    @Test
    fun everyPublishedGeminiActionMapsToARecognizedLocalCommand() {
        val actionSamples = listOf(
            "play" to "",
            "play_second" to "",
            "play_track" to "Diễm xưa",
            "add_track_to_queue" to "Diễm xưa",
            "pause" to "",
            "stop" to "",
            "next" to "",
            "previous" to "",
            "current_track" to "",
            "volume_up" to "",
            "volume_down" to "",
            "mute" to "",
            "unmute" to "",
            "set_playback_speed" to "1.25",
            "seek_forward_10" to "",
            "seek_back_10" to "",
            "seek_by_seconds" to "10",
            "youtube_seek_by_seconds" to "-10",
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
            "search_online_music" to "Bolero",
            "search_drive" to "Bolero",
            "open_youtube" to "",
            "search_youtube" to "Trịnh Công Sơn",
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
            "sleep_timer_custom" to "5",
            "cancel_sleep_timer" to "",
            "help" to ""
        )

        assertEquals(70, actionSamples.size)
        actionSamples.forEach { (action, query) ->
            val phrase = buildNgocSiAiToolCommandPhrase(action, query)
            assertTrue("$action must map to a local phrase", phrase != null)
            assertTrue(
                "$action phrase must be recognized: $phrase",
                classifyNgocSiAiActivatedCommand("AI, $phrase") != null
            )
        }
    }

    @Test
    fun everyPublishedGeminiActionMapsToItsExactLocalCommand() {
        val actionExpectations = listOf(
            Triple("play", "", NgocSiAiLocalCommand.PLAY),
            Triple("play_second", "", NgocSiAiLocalCommand.PLAY_SECOND),
            Triple("play_track", "Diễm xưa", NgocSiAiLocalCommand.PLAY_TRACK),
            Triple("add_track_to_queue", "Diễm xưa", NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE),
            Triple("pause", "", NgocSiAiLocalCommand.PAUSE),
            Triple("stop", "", NgocSiAiLocalCommand.STOP),
            Triple("next", "", NgocSiAiLocalCommand.NEXT),
            Triple("previous", "", NgocSiAiLocalCommand.PREVIOUS),
            Triple("current_track", "", NgocSiAiLocalCommand.CURRENT_TRACK),
            Triple("volume_up", "", NgocSiAiLocalCommand.VOLUME_UP),
            Triple("volume_down", "", NgocSiAiLocalCommand.VOLUME_DOWN),
            Triple("mute", "", NgocSiAiLocalCommand.MUTE),
            Triple("unmute", "", NgocSiAiLocalCommand.UNMUTE),
            Triple("set_playback_speed", "1.25", NgocSiAiLocalCommand.SET_PLAYBACK_SPEED),
            Triple("seek_forward_10", "", NgocSiAiLocalCommand.SEEK_FORWARD_10),
            Triple("seek_back_10", "", NgocSiAiLocalCommand.SEEK_BACK_10),
            Triple("seek_by_seconds", "10", NgocSiAiLocalCommand.SEEK_FORWARD_10),
            Triple("youtube_seek_by_seconds", "-10", NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10),
            Triple("favorite_current_track", "", NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK),
            Triple("shuffle_on", "", NgocSiAiLocalCommand.SHUFFLE_ON),
            Triple("shuffle_off", "", NgocSiAiLocalCommand.SHUFFLE_OFF),
            Triple("repeat_off", "", NgocSiAiLocalCommand.REPEAT_OFF),
            Triple("repeat_all", "", NgocSiAiLocalCommand.REPEAT_ALL),
            Triple("repeat_one", "", NgocSiAiLocalCommand.REPEAT_ONE),
            Triple("open_home", "", NgocSiAiLocalCommand.OPEN_HOME),
            Triple("open_library", "", NgocSiAiLocalCommand.OPEN_LIBRARY),
            Triple("open_favorites", "", NgocSiAiLocalCommand.OPEN_FAVORITES),
            Triple("open_albums", "", NgocSiAiLocalCommand.OPEN_ALBUMS),
            Triple("open_folders", "", NgocSiAiLocalCommand.OPEN_FOLDERS),
            Triple("open_online", "", NgocSiAiLocalCommand.OPEN_ONLINE),
            Triple("search_online_music", "Bolero", NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC),
            Triple("search_drive", "Bolero", NgocSiAiLocalCommand.SEARCH_DRIVE),
            Triple("open_youtube", "", NgocSiAiLocalCommand.OPEN_YOUTUBE),
            Triple("search_youtube", "Trịnh Công Sơn", NgocSiAiLocalCommand.SEARCH_YOUTUBE),
            Triple("youtube_play", "", NgocSiAiLocalCommand.RESUME_YOUTUBE),
            Triple("youtube_pause", "", NgocSiAiLocalCommand.PAUSE_YOUTUBE),
            Triple("youtube_next", "", NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO),
            Triple("youtube_previous", "", NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO),
            Triple("youtube_seek_forward_10", "", NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10),
            Triple("youtube_seek_back_10", "", NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10),
            Triple("youtube_shuffle_on", "", NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON),
            Triple("youtube_shuffle_off", "", NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF),
            Triple("youtube_repeat_off", "", NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF),
            Triple("youtube_repeat_all", "", NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL),
            Triple("youtube_repeat_one", "", NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE),
            Triple("youtube_favorite", "", NgocSiAiLocalCommand.YOUTUBE_FAVORITE),
            Triple("youtube_watch_later", "", NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER),
            Triple("open_drive", "", NgocSiAiLocalCommand.OPEN_DRIVE),
            Triple("open_radio", "", NgocSiAiLocalCommand.OPEN_RADIO),
            Triple("play_radio_station", "VOV3", NgocSiAiLocalCommand.PLAY_RADIO_STATION),
            Triple("open_tv", "", NgocSiAiLocalCommand.OPEN_TV),
            Triple("open_tv_channel", "VTV3", NgocSiAiLocalCommand.OPEN_TV_CHANNEL),
            Triple("create_playlist", "Nhạc Trịnh", NgocSiAiLocalCommand.CREATE_PLAYLIST),
            Triple("play_playlist", "Nhạc Trịnh", NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST),
            Triple("add_current_to_playlist", "Nhạc Trịnh", NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST),
            Triple("add_queue_to_playlist", "Nhạc Trịnh", NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST),
            Triple("open_playlists", "", NgocSiAiLocalCommand.OPEN_PLAYLISTS),
            Triple("open_queue", "", NgocSiAiLocalCommand.OPEN_QUEUE),
            Triple("open_player", "", NgocSiAiLocalCommand.OPEN_PLAYER),
            Triple("open_settings", "", NgocSiAiLocalCommand.OPEN_SETTINGS),
            Triple("open_sleep_timer", "", NgocSiAiLocalCommand.OPEN_SLEEP_TIMER),
            Triple("sleep_timer_15", "", NgocSiAiLocalCommand.SLEEP_TIMER_15),
            Triple("sleep_timer_30", "", NgocSiAiLocalCommand.SLEEP_TIMER_30),
            Triple("sleep_timer_45", "", NgocSiAiLocalCommand.SLEEP_TIMER_45),
            Triple("sleep_timer_60", "", NgocSiAiLocalCommand.SLEEP_TIMER_60),
            Triple("sleep_timer_90", "", NgocSiAiLocalCommand.SLEEP_TIMER_90),
            Triple("sleep_timer_120", "", NgocSiAiLocalCommand.SLEEP_TIMER_120),
            Triple("sleep_timer_custom", "5", NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM),
            Triple("cancel_sleep_timer", "", NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER),
            Triple("help", "", NgocSiAiLocalCommand.HELP)
        )

        assertEquals(70, actionExpectations.size)
        actionExpectations.forEach { (action, query, expectedCommand) ->
            val phrase = buildNgocSiAiToolCommandPhrase(action, query)
            assertTrue("$action must map to a local phrase", phrase != null)
            assertEquals(
                "$action must map to $expectedCommand; phrase=$phrase",
                expectedCommand,
                classifyNgocSiAiActivatedCommand("AI, $phrase")
            )
        }
    }
}
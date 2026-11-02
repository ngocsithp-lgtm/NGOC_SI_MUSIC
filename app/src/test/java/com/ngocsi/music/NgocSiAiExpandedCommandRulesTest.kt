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
}
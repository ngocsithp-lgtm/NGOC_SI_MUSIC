package com.ngocsi.music

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiSharedCommandRouterTest {
    @Test
    fun chatAndHandsFreeToolRoutesResolveTheSameSupportedMediaActions() {
        val cases = listOf(
            "play" to NgocSiAiLocalCommand.PLAY,
            "pause" to NgocSiAiLocalCommand.PAUSE,
            "next" to NgocSiAiLocalCommand.NEXT,
            "previous" to NgocSiAiLocalCommand.PREVIOUS,
            // Contextual YouTube follow-ups must route to dedicated YouTube commands,
            // never to the generic Media3 queue transport commands.
            "youtube_play" to NgocSiAiLocalCommand.RESUME_YOUTUBE,
            "youtube_pause" to NgocSiAiLocalCommand.PAUSE_YOUTUBE,
            "youtube_next" to NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO,
            "youtube_previous" to NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO,
            "seek_forward_10" to NgocSiAiLocalCommand.SEEK_FORWARD_10,
            "seek_back_10" to NgocSiAiLocalCommand.SEEK_BACK_10,
            "shuffle_on" to NgocSiAiLocalCommand.SHUFFLE_ON,
            "shuffle_off" to NgocSiAiLocalCommand.SHUFFLE_OFF,
            "repeat_off" to NgocSiAiLocalCommand.REPEAT_OFF,
            "repeat_all" to NgocSiAiLocalCommand.REPEAT_ALL,
            "repeat_one" to NgocSiAiLocalCommand.REPEAT_ONE
        )
        cases.forEach { (action, expected) ->
            val chat = resolveNgocSiAiToolCommand(action, "", false)
            val handsFree = resolveNgocSiAiToolCommand(action, "", true)
            assertEquals(action, expected, chat?.command)
            assertEquals(action, expected, handsFree?.command)
            assertEquals(action, chat?.phrase, handsFree?.phrase)
        }
    }

    @Test
    fun everyGeminiAllowlistedActionResolvesForChatAndHandsFreeVoice() {
        // Keep this catalog aligned with SUPPORTED_APP_ACTIONS in NgocSiAiRemoteClient.
        // Non-empty sample arguments cover every action that requires a query.
        val actions = listOf(
            "play" to "",
            "play_second" to "",
            "play_track" to "Biển nhớ",
            "add_track_to_queue" to "Biển nhớ",
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
            "seek_by_seconds" to "30",
            "youtube_seek_forward_10" to "",
            "youtube_seek_back_10" to "",
            "youtube_seek_by_seconds" to "-15",
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
            "search_youtube" to "Bolero",
            "search_and_play_youtube" to "Bolero",
            "youtube_play" to "",
            "youtube_pause" to "",
            "youtube_next" to "",
            "youtube_previous" to "",
            "youtube_shuffle_on" to "",
            "youtube_shuffle_off" to "",
            "youtube_repeat_off" to "",
            "youtube_repeat_all" to "",
            "youtube_repeat_one" to "",
            "youtube_favorite" to "",
            "youtube_watch_later" to "",
            "open_drive" to "",
            "open_radio" to "",
            "play_radio_station" to "VOV1",
            "open_tv" to "",
            "open_tv_channel" to "VTV1",
            "create_playlist" to "Bolero yêu thích",
            "play_playlist" to "Bolero yêu thích",
            "add_current_to_playlist" to "Bolero yêu thích",
            "add_queue_to_playlist" to "Bolero yêu thích",
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
            "sleep_timer_custom" to "15",
            "cancel_sleep_timer" to "",
            "help" to ""
        )

        assertEquals(71, actions.size)
        actions.forEach { (action, query) ->
            val chatRoute = resolveNgocSiAiToolCommand(action, query, false)
            val voiceRoute = resolveNgocSiAiToolCommand(action, query, true)
            org.junit.Assert.assertNotNull("Chat route missing for $action", chatRoute)
            org.junit.Assert.assertNotNull("Hands-free route missing for $action", voiceRoute)
            assertEquals("Chat/voice phrase mismatch for $action", chatRoute?.phrase, voiceRoute?.phrase)
            assertEquals("Chat/voice command mismatch for $action", chatRoute?.command, voiceRoute?.command)
        }
    }

    @Test
    fun routesRejectUnknownActionsAndMissingOrInvalidArguments() {
        assertNull(resolveNgocSiAiToolCommand("delete_library", "", false))
        assertNull(resolveNgocSiAiToolCommand("play_track", "", false))
        assertNull(resolveNgocSiAiToolCommand("youtube_seek_by_seconds", "0", true))
        assertNull(resolveNgocSiAiToolCommand("sleep_timer_custom", "1441", true))
    }

    @Test
    fun handsFreeRouterKeepsItsActivatedSessionGuard() {
        val phrase = requireNotNull(buildNgocSiAiToolCommandPhrase("set_playback_speed", "1.25"))
        assertNull(classifyNgocSiAiActivatedCommand(phrase))
        assertEquals(
            NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
            resolveNgocSiAiToolCommand("set_playback_speed", "1.25", true)?.command
        )
    }
}

class NgocSiAiSharedTrackMatchingTest {
    @Test
    fun exactTitleBeatsAnEarlierPartialOrArtistMatch() {
        val candidates = listOf(
            NgocSiAiTrackCandidate("Diễm xưa bất hủ", "Trịnh Công Sơn"),
            NgocSiAiTrackCandidate("Ca khúc khác", "Diễm Xưa"),
            NgocSiAiTrackCandidate("Diễm Xưa", "Khánh Ly")
        )
        assertEquals(2, selectNgocSiAiTrackMatchIndex(candidates, "diem xua"))
    }

    @Test
    fun trackMatchingIgnoresVietnameseDiacriticsAndCanMatchArtists() {
        val candidates = listOf(
            NgocSiAiTrackCandidate("Biển nhớ", "Khánh Ly"),
            NgocSiAiTrackCandidate("Một cõi đi về", "Trịnh Công Sơn")
        )
        assertEquals(0, selectNgocSiAiTrackMatchIndex(candidates, "bien nho"))
        assertEquals(1, selectNgocSiAiTrackMatchIndex(candidates, "trinh cong son"))
    }

    @Test
    fun trackMatchingRejectsEmptyQueriesAndUnknownTracks() {
        val candidates = listOf(NgocSiAiTrackCandidate("Biển nhớ", "Khánh Ly"))
        assertNull(selectNgocSiAiTrackMatchIndex(candidates, ""))
        assertNull(selectNgocSiAiTrackMatchIndex(candidates, "khong ton tai"))
    }
}


class NgocSiAiSharedVolumeCommandTest {
    @Test
    fun chatAndHandsFreeShareTheSameMediaVolumeAdjustments() {
        assertEquals(AudioManager.ADJUST_RAISE, ngocSiAiVolumeAdjustment(NgocSiAiLocalCommand.VOLUME_UP))
        assertEquals(AudioManager.ADJUST_LOWER, ngocSiAiVolumeAdjustment(NgocSiAiLocalCommand.VOLUME_DOWN))
        assertEquals(AudioManager.ADJUST_MUTE, ngocSiAiVolumeAdjustment(NgocSiAiLocalCommand.MUTE))
        assertEquals(AudioManager.ADJUST_UNMUTE, ngocSiAiVolumeAdjustment(NgocSiAiLocalCommand.UNMUTE))
        assertNull(ngocSiAiVolumeAdjustment(NgocSiAiLocalCommand.NEXT))
    }
}

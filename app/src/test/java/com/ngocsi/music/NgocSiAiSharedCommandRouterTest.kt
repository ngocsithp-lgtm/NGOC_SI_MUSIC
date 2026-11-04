package com.ngocsi.music

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

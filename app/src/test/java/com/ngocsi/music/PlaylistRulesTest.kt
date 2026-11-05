package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRulesTest {
    @Test
    fun normalizeName_collapsesWhitespaceAndLimitsLength() {
        assertEquals("Danh sách nhạc yêu thích", PlaylistRules.normalizeName("  Danh   sách nhạc yêu thích  "))
        assertEquals(80, PlaylistRules.normalizeName("x".repeat(120)).length)
    }

    @Test
    fun addSong_trimsAndKeepsUrisUnique() {
        assertEquals(
            listOf("content://a", "content://b"),
            PlaylistRules.addSong(listOf("content://a"), "  content://b  ")
        )
        assertEquals(
            listOf("content://a"),
            PlaylistRules.addSong(listOf("content://a"), "content://a")
        )
    }

    @Test
    fun addSong_ignoresBlankUri() {
        assertEquals(listOf("content://a"), PlaylistRules.addSong(listOf("content://a"), "   "))
    }

    @Test
    fun legacyPlaylistMigrationPreservesSourceListsWithoutLosingItems() {
        val video = YouTubePlaylistItem("abcdefghijk", "Video Bolero", "Kênh nhạc", "")
        val entries = PlaylistRules.legacyPlaylistEntries(
            listOf("content://song-a", "content://song-b"),
            listOf(video)
        )
        assertEquals(
            listOf("song:content://song-a", "song:content://song-b", "youtube:abcdefghijk"),
            entries.map { it.stableKey }
        )
    }

    @Test
    fun mixedPlaylistEntriesAppendInOrderAndDeduplicateBySourceQualifiedKey() {
        val song = PlaylistRules.songEntry("content://song-a")!!
        val video = PlaylistRules.youtubeEntry(
            YouTubePlaylistItem("abcdefghijk", "Video", "Channel", "")
        )!!
        val secondSong = PlaylistRules.songEntry("content://song-b")!!
        val mixed = PlaylistRules.addPlaylistEntries(
            listOf(song, video),
            listOf(song.copy(title = "Duplicate title"), secondSong, video)
        )
        assertEquals(
            listOf(song.stableKey, video.stableKey, secondSong.stableKey),
            mixed.map { it.stableKey }
        )
    }

    @Test
    fun mixedPlaylistCanMoveSongAcrossYouTubeEntry() {
        val entries = listOf(
            PlaylistRules.songEntry("content://song-a")!!,
            PlaylistRules.youtubeEntry(YouTubePlaylistItem("abcdefghijk", "Video", "Channel", ""))!!,
            PlaylistRules.songEntry("content://song-b")!!
        )
        val moved = PlaylistRules.movePlaylistEntry(
            entries,
            movingKey = entries[2].stableKey,
            targetKey = entries[1].stableKey,
            placeAfterTarget = false
        )
        assertEquals(
            listOf(entries[0].stableKey, entries[2].stableKey, entries[1].stableKey),
            moved.map { it.stableKey }
        )
    }

    @Test
    fun removingMixedPlaylistEntryKeepsOtherSourceTypes() {
        val song = PlaylistRules.songEntry("content://song-a")!!
        val video = PlaylistRules.youtubeEntry(YouTubePlaylistItem("abcdefghijk", "Video", "Channel", ""))!!
        assertEquals(
            listOf(video.stableKey),
            PlaylistRules.removePlaylistEntry(listOf(song, video), song.stableKey).map { it.stableKey }
        )
    }

    @Test
    fun addSongs_batchesUrisTrimsBlanksAndPreservesFirstSeenOrder() {
        assertEquals(
            listOf("content://a", "content://b", "content://c", "content://d"),
            PlaylistRules.addSongs(
                listOf("content://a", " content://a ", "content://b"),
                listOf(" ", "content://c", " content://b ", "content://d")
            )
        )
    }

    @Test
    fun contiguousPlaylistSegmentEnd_stopsAtEverySourceTypeBoundary() {
        val entries = listOf(
            PlaylistRules.songEntry("content://song-a")!!,
            PlaylistRules.songEntry("content://song-b")!!,
            PlaylistRules.youtubeEntry(YouTubePlaylistItem("abcdefghijk", "Video A", "Kênh", ""))!!,
            PlaylistRules.youtubeEntry(YouTubePlaylistItem("lmnopqrstuv", "Video B", "Kênh", ""))!!,
            PlaylistRules.songEntry("content://song-c")!!
        )
        assertEquals(2, PlaylistRules.contiguousPlaylistSegmentEnd(
            entries, 0, PlaylistEntry.TYPE_SONG
        ))
        assertEquals(4, PlaylistRules.contiguousPlaylistSegmentEnd(
            entries, 2, PlaylistEntry.TYPE_YOUTUBE
        ))
        assertEquals(5, PlaylistRules.contiguousPlaylistSegmentEnd(
            entries, 4, PlaylistEntry.TYPE_SONG
        ))
        assertEquals(2, PlaylistRules.contiguousPlaylistSegmentEnd(
            entries, 2, PlaylistEntry.TYPE_SONG
        ))
        assertEquals(-1, PlaylistRules.contiguousPlaylistSegmentEnd(
            entries, -1, PlaylistEntry.TYPE_SONG
        ))
    }

    @Test
    fun requiresOrderedMixedPlayback_onlyWhenPlayableEntriesSpanDifferentSources() {
        val deviceSong = PlaylistRules.songEntry("content://device-a")!!
        val driveSong = PlaylistRules.songEntry("content://drive-a")!!
        val youtube = PlaylistRules.youtubeEntry(
            YouTubePlaylistItem("abcdefghijk", "Video", "Channel", "")
        )!!

        assertTrue(
            !PlaylistRules.requiresOrderedMixedPlayback(
                listOf(deviceSong, PlaylistRules.songEntry("content://device-b")!!),
                mapOf("content://device-a" to "Thiết bị", "content://device-b" to "Thiết bị")
            )
        )
        assertTrue(
            PlaylistRules.requiresOrderedMixedPlayback(
                listOf(deviceSong, driveSong),
                mapOf("content://device-a" to "Thiết bị", "content://drive-a" to "Google Drive")
            )
        )
        assertTrue(
            PlaylistRules.requiresOrderedMixedPlayback(
                listOf(deviceSong, youtube),
                mapOf("content://device-a" to "Thiết bị")
            )
        )
        // Missing songs are not treated as an extra source and therefore do not
        // force a homogeneous playlist into ordered cross-source playback.
        assertTrue(
            !PlaylistRules.requiresOrderedMixedPlayback(
                listOf(deviceSong, PlaylistRules.songEntry("content://missing")!!),
                mapOf("content://device-a" to "Thiết bị")
            )
        )
    }

    @Test
    fun moveSong_placesAnItemBeforeOrAfterTheSelectedTarget() {
        val original = listOf("a", "b", "c", "d")
        assertEquals(
            listOf("b", "a", "c", "d"),
            PlaylistRules.moveSong(original, "b", "a", placeAfterTarget = false)
        )
        assertEquals(
            listOf("a", "c", "b", "d"),
            PlaylistRules.moveSong(original, "b", "c", placeAfterTarget = true)
        )
        assertEquals(
            listOf("a", "d", "b", "c"),
            PlaylistRules.moveSong(original, "d", "b", placeAfterTarget = false)
        )
    }

    @Test
    fun moveSongKeepsOriginalOrderWhenAnItemOrTargetIsMissing() {
        val original = listOf("a", "b", "c")
        assertEquals(
            original,
            PlaylistRules.moveSong(original, "missing", "a", placeAfterTarget = false)
        )
        assertEquals(
            original,
            PlaylistRules.moveSong(original, "a", "missing", placeAfterTarget = true)
        )
        assertEquals(
            original,
            PlaylistRules.moveSong(original, "a", "a", placeAfterTarget = false)
        )
    }

    @Test
    fun removeSong_removesAllMatchingEntries() {
        assertEquals(
            listOf("content://a"),
            PlaylistRules.removeSong(
                listOf("content://a", "content://b", "content://b"),
                "content://b"
            )
        )
        assertTrue(PlaylistRules.removeSong(emptyList(), "content://missing").isEmpty())
    }

    @Test
    fun addYouTubeItems_validatesIdsAndKeepsEntriesUnique() {
        val first = YouTubePlaylistItem(
            videoId = "abcdefghijk",
            title = "Video Bolero",
            channelTitle = "Kênh nhạc",
            thumbnailUrl = ""
        )
        val duplicate = first.copy(title = "Tên mới")
        val second = YouTubePlaylistItem(
            videoId = "lmnopqrstuv",
            title = "Video thứ hai",
            channelTitle = "Kênh khác",
            thumbnailUrl = "https://example.com/thumb.jpg"
        )
        val result = PlaylistRules.addYouTubeItems(
            listOf(first),
            listOf(duplicate, second, first.copy(videoId = "bad/id"))
        )

        assertEquals(listOf("abcdefghijk", "lmnopqrstuv"), result.map { it.videoId })
        assertEquals("Video Bolero", result.first().title)
        assertEquals(
            "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
            PlaylistRules.addYouTubeItems(emptyList(), listOf(first)).first().thumbnailUrl
        )
    }

    @Test
    fun moveAndRemoveYouTubeItemsPreserveTheExpectedOrder() {
        val items = listOf(
            YouTubePlaylistItem("abcdefghijk", "A", "Kênh", ""),
            YouTubePlaylistItem("lmnopqrstuv", "B", "Kênh", ""),
            YouTubePlaylistItem("zyxwvutsrq0", "C", "Kênh", "")
        )
        val moved = PlaylistRules.moveYouTubeItem(
            items,
            movingVideoId = "zyxwvutsrq0",
            targetVideoId = "abcdefghijk",
            placeAfterTarget = false
        )
        assertEquals(
            listOf("zyxwvutsrq0", "abcdefghijk", "lmnopqrstuv"),
            moved.map { it.videoId }
        )
        assertEquals(
            listOf("abcdefghijk", "lmnopqrstuv"),
            PlaylistRules.removeYouTubeItem(moved, "zyxwvutsrq0").map { it.videoId }
        )
        assertTrue(PlaylistRules.removeYouTubeItem(items, "invalid/id").size == items.size)
    }
}

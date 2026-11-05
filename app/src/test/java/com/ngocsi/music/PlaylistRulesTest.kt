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

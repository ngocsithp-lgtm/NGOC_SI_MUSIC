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
}

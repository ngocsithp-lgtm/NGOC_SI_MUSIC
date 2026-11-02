package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun modelActionsUseTheSameAllowlistedLocalRouter() {
        val add = buildNgocSiAiToolCommandPhrase("add_track_to_queue", "Diễm xưa")
        val search = buildNgocSiAiToolCommandPhrase("search_drive", "Bolero")
        assertEquals(NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE, classifyNgocSiAiActivatedCommand("AI, $add"))
        assertEquals(NgocSiAiLocalCommand.SEARCH_DRIVE, classifyNgocSiAiActivatedCommand("AI, $search"))
        assertNull(buildNgocSiAiToolCommandPhrase("add_track_to_queue", ""))
        assertNull(buildNgocSiAiToolCommandPhrase("search_drive", ""))
    }
}
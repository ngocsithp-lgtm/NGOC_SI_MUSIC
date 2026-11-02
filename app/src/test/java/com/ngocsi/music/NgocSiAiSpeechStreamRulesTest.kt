package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiSpeechStreamRulesTest {
    @Test
    fun emitsFirstCompleteSentenceWithoutWaitingForTheWholeAnswer() {
        val partial = "Chào bạn. Tôi đang nghe"
        val first = extractNgocSiAiSpeechStreamChunks(partial, alreadySpokenCharacters = 0)

        assertEquals(listOf("Chào bạn."), first.chunks)
        assertEquals(10, first.nextCharacterOffset)

        val remainder = extractNgocSiAiSpeechStreamChunks(
            partial,
            alreadySpokenCharacters = first.nextCharacterOffset,
            flushRemainder = true
        )
        assertEquals(listOf("Tôi đang nghe"), remainder.chunks)
        assertEquals(partial.length, remainder.nextCharacterOffset)
    }

    @Test
    fun emitsMultipleCompleteSentencesInTheirOriginalOrder() {
        val result = extractNgocSiAiSpeechStreamChunks(
            "Câu thứ nhất. Câu thứ hai! Câu thứ ba?",
            alreadySpokenCharacters = 0
        )
        assertEquals(
            listOf("Câu thứ nhất.", "Câu thứ hai!", "Câu thứ ba?"),
            result.chunks
        )
        assertEquals("Câu thứ nhất. Câu thứ hai! Câu thứ ba?".length, result.nextCharacterOffset)
    }

    @Test
    fun doesNotSplitDecimalNumbersAtTheDot() {
        val partial = "Bản 5.92 đang chạy"
        val result = extractNgocSiAiSpeechStreamChunks(partial, alreadySpokenCharacters = 0)
        assertTrue(result.chunks.isEmpty())

        val final = extractNgocSiAiSpeechStreamChunks(
            partial,
            alreadySpokenCharacters = result.nextCharacterOffset,
            flushRemainder = true
        )
        assertEquals(listOf(partial), final.chunks)
    }

    @Test
    fun longSentencesSplitOnlyAtWhitespaceBoundaries() {
        val sentence = ("xin chao moi nguoi dang nghe " ).repeat(8).trim()
        val result = extractNgocSiAiSpeechStreamChunks(
            sentence,
            alreadySpokenCharacters = 0,
            maxChunkCharacters = 80
        )
        assertTrue(result.chunks.isNotEmpty())
        assertTrue(result.chunks.all { it.isNotBlank() })
        assertTrue(result.nextCharacterOffset > 0)
        assertTrue(result.nextCharacterOffset <= sentence.length)
        assertTrue(sentence[result.nextCharacterOffset - 1].isWhitespace() ||
            result.nextCharacterOffset == sentence.length)
    }

    @Test
    fun repeatedPartialTextDoesNotEmitAlreadyConsumedSentencesAgain() {
        val partial = "Xin chào. Tôi vẫn đang trả lời"
        val first = extractNgocSiAiSpeechStreamChunks(partial, alreadySpokenCharacters = 0)
        val second = extractNgocSiAiSpeechStreamChunks(
            partial,
            alreadySpokenCharacters = first.nextCharacterOffset
        )
        assertEquals(listOf("Xin chào."), first.chunks)
        assertTrue(second.chunks.isEmpty())
        assertEquals(first.nextCharacterOffset, second.nextCharacterOffset)
    }
}

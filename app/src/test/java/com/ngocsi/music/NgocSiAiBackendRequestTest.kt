package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiBackendRequestTest {
    @Test
    fun sendsOnlyAlternatingHistoryStartingAndEndingWithUser() {
        val history = listOf(
            NgocSiAiChatTurn("assistant", "welcome should be filtered"),
            NgocSiAiChatTurn("user", "Xin chào"),
            NgocSiAiChatTurn("assistant", "Chào bạn"),
            NgocSiAiChatTurn("user", "Tôi muốn nghe nhạc"),
            NgocSiAiChatTurn("assistant", "Bạn có thể chọn một bài"),
            NgocSiAiChatTurn("user", "Giải thích thêm")
        )

        val result = NgocSiAiBackendRequest.normalize(history)

        assertEquals("user", result.first().role)
        assertEquals("user", result.last().role)
        assertEquals("Giải thích thêm", result.last().content)
        assertTrue(result.size <= 5)
        result.forEachIndexed { index, turn ->
            assertEquals(if (index % 2 == 0) "user" else "assistant", turn.role)
        }
    }

    @Test
    fun replacesFailedConsecutiveUserPromptAndKeepsNewestPrompt() {
        val history = listOf(
            NgocSiAiChatTurn("user", "Câu hỏi bị lỗi trước"),
            NgocSiAiChatTurn("user", "Câu hỏi mới nhất")
        )

        val result = NgocSiAiBackendRequest.normalize(history)

        assertEquals(1, result.size)
        assertEquals("Câu hỏi mới nhất", result.single().content)
    }

    @Test
    fun boundsHistoryAndTotalCharactersToCallableLimits() {
        val history = mutableListOf<NgocSiAiChatTurn>()
        repeat(4) {
            history += NgocSiAiChatTurn("user", "u".repeat(2_000))
            history += NgocSiAiChatTurn("assistant", "a".repeat(2_500))
        }
        history += NgocSiAiChatTurn("user", "cuối".repeat(500))

        val result = NgocSiAiBackendRequest.normalize(history)

        assertTrue(result.size <= 5)
        assertTrue(result.all { it.content.length <= 2_000 })
        assertTrue(result.sumOf { it.content.length } <= 8_000)
        assertEquals("user", result.last().role)
    }

    @Test
    fun callablePayloadUsesExpectedMessageShape() {
        val payload = NgocSiAiBackendRequest.toCallableData(
            listOf(
                NgocSiAiChatTurn("user", "Xin chào"),
                NgocSiAiChatTurn("assistant", "Chào bạn"),
                NgocSiAiChatTurn("user", "Bạn làm được gì?")
            )
        )

        @Suppress("UNCHECKED_CAST")
        val messages = payload["messages"] as List<Map<String, String>>
        assertEquals(3, messages.size)
        assertEquals("user", messages[0]["role"])
        assertEquals("Xin chào", messages[0]["content"])
        assertEquals("assistant", messages[1]["role"])
        assertEquals("user", messages[2]["role"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyHistoryRatherThanCallingBackend() {
        NgocSiAiBackendRequest.toCallableData(emptyList())
    }
}

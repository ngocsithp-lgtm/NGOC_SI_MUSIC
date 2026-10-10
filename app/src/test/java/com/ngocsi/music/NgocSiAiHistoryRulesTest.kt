package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiHistoryRulesTest {
    @Test
    fun restoreDropsAnInterruptedTrailingUserTurn() {
        val restored = restoreCompletedNgocSiAiConversation(
            listOf(
                NgocSiAiMessage("user", "Câu hỏi 1"),
                NgocSiAiMessage("assistant", "Trả lời 1"),
                NgocSiAiMessage("user", "Câu hỏi đang dở")
            )
        )

        assertEquals(
            listOf(
                NgocSiAiMessage("user", "Câu hỏi 1"),
                NgocSiAiMessage("assistant", "Trả lời 1")
            ),
            restored
        )
    }

    @Test
    fun restoreRejectsIncompleteOrMalformedStoredHistory() {
        assertTrue(
            restoreCompletedNgocSiAiConversation(
                listOf(NgocSiAiMessage("user", "Câu hỏi chưa được trả lời"))
            ).isEmpty()
        )
        assertTrue(
            restoreCompletedNgocSiAiConversation(
                listOf(
                    NgocSiAiMessage("user", "Câu hỏi 1"),
                    NgocSiAiMessage("user", "Câu hỏi 2"),
                    NgocSiAiMessage("assistant", "Trả lời")
                )
            ).isEmpty()
        )
    }

    @Test
    fun trimsWhitespaceAndKeepsTheNewestUserMessage() {
        val history = normalizeNgocSiAiHistory(
            listOf(NgocSiAiMessage(role = "user", content = "  Xin chào  "))
        )

        assertEquals(listOf(NgocSiAiMessage(role = "user", content = "Xin chào")), history)
    }

    @Test
    fun trimsOldestCompleteTurnsWhenTotalTextExceedsServerBudget() {
        val messages = buildList {
            repeat(4) {
                add(NgocSiAiMessage(role = "user", content = "u".repeat(2_000)))
                add(NgocSiAiMessage(role = "assistant", content = "a".repeat(2_000)))
            }
            add(NgocSiAiMessage(role = "user", content = "  câu hỏi mới  "))
        }

        val history = normalizeNgocSiAiHistory(messages)

        assertTrue(history.size <= NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        assertTrue(history.sumOf { it.content.length } <= NGOC_SI_AI_MAX_TOTAL_CHARS)
        assertEquals("user", history.first().role)
        assertEquals("user", history.last().role)
        assertEquals("câu hỏi mới", history.last().content)
        assertFalse(history.first().role == "assistant")
    }

    @Test
    fun truncatesOversizedHistoricalAssistantAnswerButPreservesNewestUserMessage() {
        val history = normalizeNgocSiAiHistory(
            listOf(
                NgocSiAiMessage(role = "user", content = "Câu hỏi trước"),
                NgocSiAiMessage(role = "assistant", content = "a".repeat(2_500)),
                NgocSiAiMessage(role = "user", content = "Câu hỏi mới")
            )
        )

        assertEquals(2_000, history[1].content.length)
        assertEquals("Câu hỏi mới", history.last().content)
        assertEquals("user", history.last().role)
    }

    @Test
    fun limitsHistoryToLatestEightMessages() {
        val messages = buildList {
            repeat(5) { index ->
                add(NgocSiAiMessage(role = "user", content = "câu hỏi $index"))
                add(NgocSiAiMessage(role = "assistant", content = "trả lời $index"))
            }
            add(NgocSiAiMessage(role = "user", content = "câu hỏi cuối"))
        }

        val history = normalizeNgocSiAiHistory(messages)

        assertTrue(history.size <= NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        assertEquals("câu hỏi cuối", history.last().content)
    }

    @Test
    fun rejectsUnsupportedRoles() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            normalizeNgocSiAiHistory(listOf(NgocSiAiMessage(role = "system", content = "không hợp lệ")))
        }
        assertTrue(error.message!!.contains("không được hỗ trợ"))
    }

    @Test
    fun rejectsMessagesLongerThanTwoThousandCharacters() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            normalizeNgocSiAiHistory(
                listOf(NgocSiAiMessage(role = "user", content = "x".repeat(2_001)))
            )
        }
        assertTrue(error.message!!.contains("2.000"))
    }

    @Test
    fun requiresTheNewestMessageToBeFromTheUser() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            normalizeNgocSiAiHistory(
                listOf(NgocSiAiMessage(role = "user", content = "Xin chào"),
                        NgocSiAiMessage(role = "assistant", content = "Chào bạn"))
            )
        }
        assertTrue(error.message!!.contains("câu hỏi mới"))
    }
}

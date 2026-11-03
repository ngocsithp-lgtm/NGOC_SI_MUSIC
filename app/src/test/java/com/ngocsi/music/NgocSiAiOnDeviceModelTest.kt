package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiOnDeviceModelTest {
    @Test
    fun removesQwenThinkingBlockBeforeShowingOrSpeakingTheAnswer() {
        assertEquals(
            "Hà Nội là thủ đô của Việt Nam.",
            stripNgocSiAiOnDeviceThinkingContent(
                "<think>Need reason about the answer.\nDo not show this.</think>\nHà Nội là thủ đô của Việt Nam."
            )
        )
    }

    @Test
    fun preservesAnOrdinaryReplyWithoutThinkingMarkers() {
        assertEquals(
            "Xin chào. Tôi là NGỌC SĨ AI.",
            stripNgocSiAiOnDeviceThinkingContent("  Xin chào. Tôi là NGỌC SĨ AI.  ")
        )
    }

    @Test
    fun localModelContextRestoresOnlyCompletedTurnsInChronologicalOrder() {
        val context = buildNgocSiAiLocalConversationContext(
            listOf(
                NgocSiAiMessage("user", "Tôi thích nghe Bolero."),
                NgocSiAiMessage("assistant", "Mình đã ghi nhận sở thích Bolero."),
                NgocSiAiMessage("user", "Hãy mở YouTube."),
                NgocSiAiMessage("assistant", "Mình đã mở YouTube."),
                NgocSiAiMessage("user", "Câu hỏi bị ngắt khi ứng dụng đóng.")
            )
        )

        assertTrue(context.indexOf("Người dùng: Tôi thích nghe Bolero.") <
            context.indexOf("NGỌC SĨ AI: Mình đã ghi nhận"))
        assertTrue(context.contains("Người dùng: Hãy mở YouTube."))
        assertTrue(context.contains("NGỌC SĨ AI: Mình đã mở YouTube."))
        assertFalse(context.contains("Câu hỏi bị ngắt"))
    }

    @Test
    fun localModelContextIsBoundedAndKeepsTheNewestCompletedTurn() {
        val messages = buildList {
            repeat(12) { index ->
                add(NgocSiAiMessage("user", "Câu hỏi $index " + "u".repeat(500)))
                add(NgocSiAiMessage("assistant", "Trả lời $index " + "a".repeat(500)))
            }
        }

        val context = buildNgocSiAiLocalConversationContext(messages)

        assertTrue(context.length <= NGOC_SI_AI_LOCAL_HISTORY_MAX_CONTEXT_CHARS)
        assertTrue(context.contains("Câu hỏi 11"))
        assertTrue(context.contains("Trả lời 11"))
    }

    @Test
    fun localModelContextIsEmptyWithoutACompletedPriorTurn() {
        assertEquals(
            "",
            buildNgocSiAiLocalConversationContext(
                listOf(NgocSiAiMessage("user", "Câu hỏi chưa được trả lời"))
            )
        )
        assertEquals("", buildNgocSiAiLocalConversationContext(emptyList()))
    }

    @Test
    fun localModelUsesASeparateLargeFileOutsideTheApk() {
        assertEquals(613_722_264L, NgocSiAiOnDeviceModel.MODEL_SIZE_BYTES)
        assertTrue(NgocSiAiOnDeviceModel.MODEL_SIZE_BYTES > 500L * 1024L * 1024L)
        assertEquals("586 MiB", NgocSiAiOnDeviceModel.MODEL_SIZE_LABEL)
    }
}

package com.ngocsi.music

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiLocalKnowledgeRulesTest {
    @Test
    fun answersTodayFromTheDeviceLocalDateWithoutCallingGemini() {
        val today = LocalDate.of(2026, 11, 24)
        val expected = "Hôm nay là thứ ba, ngày 24 tháng 11 năm 2026."
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Hôm nay là ngày gì?", today))
        assertEquals(expected, localNgocSiAiKnowledgeFallback("AI, hôm nay là ngày mấy?", today))
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Hey AI, hôm nay thứ mấy?", today))
        assertEquals(formatNgocSiAiTodayAnswer(LocalDate.now()), localConversationFallback("AI, hôm nay là ngày gì?"))
    }

    @Test
    fun formatsSundayCorrectly() {
        assertEquals(
            "Hôm nay là Chủ Nhật, ngày 29 tháng 11 năm 2026.",
            formatNgocSiAiTodayAnswer(LocalDate.of(2026, 11, 29))
        )
    }

    @Test
    fun answersVietnamCapitalWithoutCallingGemini() {
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Thủ đô Việt Nam là gì?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("AI, thủ đô của nước Việt Nam ở đâu?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Hey AI, thủ đô Việt Nam là gì?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Ngọc Sĩ ơi, thủ đô nước ta ở đâu?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("OK AI ơi, thủ đô Việt Nam là thành phố nào?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Thủ đô Việt Nam")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Thủ đô nước ta là gì?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Thủ đô nước Việt Nam là thành phố nào?")
        )
        assertEquals(
            "Thủ đô của Việt Nam là Hà Nội.",
            localNgocSiAiKnowledgeFallback("Thủ đô Việt Nam ở thành phố nào?")
        )
    }

    @Test
    fun answersOtherAllowlistedStableVietnamFacts() {
        assertEquals(
            "Đơn vị tiền tệ của Việt Nam là đồng Việt Nam, ký hiệu là VND.",
            localNgocSiAiKnowledgeFallback("Việt Nam dùng tiền gì?")
        )
        assertEquals(
            "Ngôn ngữ được sử dụng chính thức ở Việt Nam là tiếng Việt.",
            localNgocSiAiKnowledgeFallback("Ngôn ngữ Việt Nam là gì?")
        )
        assertEquals(
            "Việt Nam nằm ở Đông Nam Á, thuộc khu vực bán đảo Đông Dương.",
            localNgocSiAiKnowledgeFallback("Việt Nam nằm ở đâu?")
        )
    }

    @Test
    fun doesNotGuessFactsOutsideTheLocalAllowlist() {
        assertNull(localNgocSiAiKnowledgeFallback("Thủ đô của Pháp là gì?"))
        assertNull(localNgocSiAiKnowledgeFallback("Tình hình kinh tế Việt Nam năm nay thế nào?"))
        assertNull(localNgocSiAiKnowledgeFallback("Ai là người giàu nhất Việt Nam hiện nay?"))
    }

    @Test
    fun understandsCommonVietnamCapitalSpeechRecognitionVariants() {
        val expected = "Thủ đô của Việt Nam là Hà Nội."
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Thủ đô VN là gì?"))
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Thủ đô nước mình ở đâu?"))
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Thủ đô nước mình là gì?"))
        assertEquals(expected, localNgocSiAiKnowledgeFallback("Al, thủ đô Việt Nam là gì?"))
        assertEquals(
            "Đúng, Hà Nội là thủ đô của Việt Nam.",
            localNgocSiAiKnowledgeFallback("Hà Nội có phải là thủ đô Việt Nam không?")
        )
    }

    @Test
    fun answersCapitalThroughTheSameOfflineFallbackUsedByChat() {
        val expected = "Thủ đô của Việt Nam là Hà Nội."
        assertEquals(expected, localConversationFallback("AI, thủ đô Việt Nam là gì?"))
        assertEquals(expected, localConversationFallback("Al, thủ đô Việt Nam là gì?"))
        assertEquals(expected, localConversationFallback("Thủ đô nước mình ở đâu?"))
    }
}

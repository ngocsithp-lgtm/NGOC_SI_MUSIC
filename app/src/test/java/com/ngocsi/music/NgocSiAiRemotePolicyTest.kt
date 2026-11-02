package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiRemotePolicyTest {
    @Test
    fun remoteConversationTimeoutIsSharedByVoiceAndChat() {
        assertEquals(90_000L, NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS)
    }

    @Test
    fun shortEverydayQuestionsUseLowLatencyThinking() {
        assertEquals(ThinkingLevel.LOW, selectNgocSiAiThinkingLevel("Xin chào, bạn khỏe không?"))
    }

    @Test
    fun shortFollowUpsStayLowThinkingRegardlessOfPreviousContext() {
        assertEquals(ThinkingLevel.LOW, selectNgocSiAiThinkingLevel("Tại sao?"))
    }

    @Test
    fun complexRequestsKeepMediumReasoningAndLargerAnswerBudget() {
        val prompt = "Hãy phân tích kỹ, tìm nguyên nhân và lập kế hoạch nâng cấp AI từng bước."
        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
    }

    @Test
    fun everydayQuestionsUseSmallerAnswerBudget() {
        assertEquals(
            NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS,
            selectNgocSiAiMaxOutputTokens("Thời tiết có ảnh hưởng đến pin điện thoại không?")
        )
    }

    @Test
    fun appActionsKeepTheAllowlistedCommandToolAvailable() {
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Mở YouTube và phát nhạc bolero"))
    }

    @Test
    fun ordinaryKnowledgeQuestionsSkipTheAppCommandTool() {
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Vì sao bầu trời có màu xanh?"))
    }

    @Test
    fun naturalVietnameseNavigationPhrasesKeepTheAppCommandToolAvailable() {
        listOf(
            "AI, chuyển qua Radio giúp tôi.",
            "AI, vào thư mục Drive.",
            "AI, bấm mở mục YouTube.",
            "AI, đi đến thư viện nhạc."
        ).forEach { phrase ->
            assertTrue("$phrase should expose app command tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun naturalPlaybackAndSeekPhrasesKeepTheAppCommandToolAvailable() {
        listOf(
            "AI, bấm phát video tiếp theo.",
            "AI, tiếp đi.",
            "AI, lùi 10 giây.",
            "AI, tua lùi 15 giây trên YouTube.",
            "AI, giảm âm lượng xuống.",
            "AI, tắt tiếng."
        ).forEach { phrase ->
            assertTrue("$phrase should expose app command tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun timerAndPlaylistRequestsKeepTheAppCommandToolAvailable() {
        listOf(
            "AI, đặt hẹn giờ tắt nhạc sau 30 phút.",
            "AI, mở playlist Nhạc Trịnh.",
            "AI, thêm bài đang phát vào yêu thích."
        ).forEach { phrase ->
            assertTrue("$phrase should expose app command tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun informationalQuestionsDoNotBecomeAppActions() {
        listOf(
            "Vì sao YouTube có thể phát nhạc nền?",
            "Kể cho tôi nghe về lịch sử của Radio.",
            "Tại sao âm lượng điện thoại đôi khi nhỏ?"
        ).forEach { phrase ->
            assertFalse("$phrase should remain a normal chat question", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }
}


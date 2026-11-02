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
}

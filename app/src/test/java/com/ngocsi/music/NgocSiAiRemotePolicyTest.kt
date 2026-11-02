package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiRemotePolicyTest {
    @Test
    fun remoteConversationTimeoutAllowsMoreThanThePreviousTwentyFiveSeconds() {
        assertEquals(90_000L, NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS)
        assertTrue(NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS > 25_000L)
    }

    @Test
    fun shortEverydayQuestionsUseLowLatencyThinking() {
        assertEquals(
            ThinkingLevel.LOW,
            selectNgocSiAiThinkingLevel("Xin chào, bạn khỏe không?", historyMessageCount = 0)
        )
    }

    @Test
    fun complexRequestsKeepMediumReasoning() {
        assertEquals(
            ThinkingLevel.MEDIUM,
            selectNgocSiAiThinkingLevel(
                "Hãy phân tích kỹ, tìm nguyên nhân và lập kế hoạch nâng cấp AI từng bước.",
                historyMessageCount = 2
            )
        )
    }

    @Test
    fun longContextualChatsKeepMediumReasoning() {
        assertEquals(
            ThinkingLevel.MEDIUM,
            selectNgocSiAiThinkingLevel("Tiếp tục việc vừa trao đổi.", historyMessageCount = 8)
        )
    }
}

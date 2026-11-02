package com.ngocsi.music

import com.google.firebase.ai.type.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiRemotePolicyTest {
    @Test
    fun ordinaryQuestionsUseAResponsiveSharedDeadline() {
        assertEquals(25_000L, NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS)
        assertEquals(25_000L, selectNgocSiAiRemoteTimeoutMillis("Bạn có khỏe không?"))
        assertTrue(ngocSiAiRemoteTimeoutMessage("Bạn có khỏe không?").contains("25 giây"))
    }

    @Test
    fun complexQuestionsGetASeparateBoundedDeadline() {
        val prompt = "Hãy phân tích kỹ, tìm nguyên nhân và lập kế hoạch nâng cấp AI từng bước."
        assertEquals(40_000L, NGOC_SI_AI_COMPLEX_REMOTE_CONVERSATION_TIMEOUT_MILLIS)
        assertEquals(40_000L, selectNgocSiAiRemoteTimeoutMillis(prompt))
        assertTrue(ngocSiAiRemoteTimeoutMessage(prompt).contains("40 giây"))
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
    fun compoundAppRequestsUsePlanningReasoningButSingleActionsStayFast() {
        assertEquals(ThinkingLevel.LOW, selectNgocSiAiThinkingLevel("AI, mở YouTube."))
        assertEquals(
            ThinkingLevel.MEDIUM,
            selectNgocSiAiThinkingLevel("AI, tìm bài bolero trên YouTube rồi phát và hẹn giờ 30 phút.")
        )
        assertEquals(
            ThinkingLevel.MEDIUM,
            selectNgocSiAiThinkingLevel("Mở Radio rồi phát đài VOV và hẹn giờ 20 phút.")
        )
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
            "Tại sao âm lượng điện thoại đôi khi nhỏ?",
            "Làm thế nào để mở Drive?"
        ).forEach { phrase ->
            assertFalse("$phrase should remain a normal chat question", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun customSleepTimerRequestsExposeTheConstrainedAppTool() {
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, đặt hẹn giờ tắt nhạc sau 5 phút"))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Hẹn giờ của ứng dụng hoạt động như thế nào?"))
    }

    @Test
    fun directCapabilityQuestionsCanStillRequestAnAppAction() {
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool("AI, bạn có thể mở YouTube không?")
        )
    }

    @Test
    fun shortConfirmationCanContinueAnExplicitAppActionOffer() {
        val history = listOf(
            NgocSiAiMessage("user", "Tôi muốn mở YouTube."),
            NgocSiAiMessage("assistant", "Bạn muốn mình mở YouTube ngay bây giờ không?")
        )
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Được, làm đi.", history))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, tiến hành đi.", listOf(
            NgocSiAiMessage("user", "Đặt hẹn giờ tắt nhạc sau 15 phút."),
            NgocSiAiMessage("assistant", "Mình có thể đặt hẹn giờ 15 phút cho bạn. Bạn muốn mình thực hiện không?")
        )))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tiếp tục", listOf(
            NgocSiAiMessage("assistant", "Bạn muốn mình mở Radio không?")
        )))
    }

    @Test
    fun contextualConfirmationDoesNotTriggerWithoutAnImmediateActionOffer() {
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Được"))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Làm đi", listOf(
            NgocSiAiMessage("user", "Vì sao YouTube phát nhạc nền được?"),
            NgocSiAiMessage("assistant", "YouTube có thể phát nền tùy ứng dụng và thiết bị.")
        )))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Đồng ý", listOf(
            NgocSiAiMessage("assistant", "Bạn muốn mình hướng dẫn cách mở YouTube không?")
        )))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Được", listOf(
            NgocSiAiMessage("assistant", "Mình có thể mở YouTube cho bạn.")
        )))
    }

    @Test
    fun contextualActionConfirmationBypassesLocalSmallTalkAndResumeFallbacks() {
        val offeredAction = listOf(
            NgocSiAiMessage("user", "Tôi muốn mở YouTube."),
            NgocSiAiMessage("assistant", "Bạn muốn mình mở YouTube ngay bây giờ không?")
        )
        assertTrue(isNgocSiAiContextualActionConfirmation("Được", offeredAction))
        assertTrue(isNgocSiAiContextualActionConfirmation("Tiếp tục", listOf(
            NgocSiAiMessage("assistant", "Bạn muốn mình mở Radio không?")
        )))
        assertFalse(isNgocSiAiContextualActionConfirmation("Được", emptyList()))
        assertFalse(isNgocSiAiContextualActionConfirmation("Tạm dừng nhạc", offeredAction))
        assertFalse(isNgocSiAiContextualActionConfirmation("Làm thế nào để mở YouTube?", offeredAction))
    }

    @Test
    fun agentRunsOnlyTheFirstValidToolCallBeforeReadingFeedback() {
        val calls = listOf(
            NgocSiAiAgentToolCall("unexpected_tool", "open_youtube"),
            NgocSiAiAgentToolCall("execute_app_command", "open_youtube"),
            NgocSiAiAgentToolCall("execute_app_command", "sleep_timer_30")
        )
        assertEquals(1, selectNgocSiAiAgentToolCallIndex(calls, "execute_app_command"))
    }

    @Test
    fun agentRejectsEmptyOrUnknownToolCalls() {
        assertNull(
            selectNgocSiAiAgentToolCallIndex(
                listOf(
                    NgocSiAiAgentToolCall("unexpected_tool", "open_youtube"),
                    NgocSiAiAgentToolCall("execute_app_command", "")
                ),
                "execute_app_command"
            )
        )
        assertNull(
            selectNgocSiAiAgentToolCallIndex(
                listOf(NgocSiAiAgentToolCall("execute_app_command", "open_youtube")),
                ""
            )
        )
    }

    @Test
    fun agentDefersAdditionalStepsUntilPreviousActionFeedbackIsAvailable() {
        val feedback = ngocSiAiDeferredToolCallFeedback()
        assertTrue(feedback.contains("hoãn bước này"))
        assertTrue(feedback.contains("phản hồi"))
        assertFalse(feedback.contains("đã thực hiện thành công"))
    }

    @Test
    fun agentContinuesOnlyWhenAppFeedbackDoesNotReportFailure() {
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đang mở YouTube."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đang tìm nhạc theo từ khóa."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(false, "Đã gửi lệnh mở YouTube."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Không tìm thấy bài hát."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Bạn cần đăng nhập Google Drive."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Ứng dụng gặp lỗi khi nhận lệnh."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Thiếu thông tin; chưa thực hiện thao tác."))
    }
}


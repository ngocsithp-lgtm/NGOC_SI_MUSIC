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
    fun simpleUpgradeAndOptimizationRequestsUseTheLowLatencyPath() {
        val prompts = listOf(
            "AI xử lý chậm quá, bạn có thể nâng cấp cho tốt hơn không?",
            "Nâng cấp AI",
            "Tối ưu AI"
        )
        prompts.forEach { prompt ->
            assertEquals("$prompt should use low-latency thinking", ThinkingLevel.LOW, selectNgocSiAiThinkingLevel(prompt))
            assertEquals(25_000L, selectNgocSiAiRemoteTimeoutMillis(prompt))
        }
    }


    @Test
    fun complexRequestsKeepMediumIntentButUseFlashLiteSupportedThinkingLevel() {
        val prompt = "Hãy phân tích kỹ, tìm nguyên nhân và lập kế hoạch nâng cấp AI từng bước."
        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel(prompt, modelName = NGOC_SI_AI_FAST_CHAT_MODEL_NAME)
        )
        assertEquals(NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
    }



    @Test
    fun youtubeVideoAnalysisGetsMoreTimeWithoutEscalatingEverydayReasoning() {
        val prompt = "AI, phân tích kỹ video YouTube này và chỉ ra các đoạn cảm xúc thay đổi."

        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel(prompt, modelName = NGOC_SI_AI_FAST_CHAT_MODEL_NAME)
        )
        assertTrue(isNgocSiAiMultimodalVideoAnalysisRequest(prompt))
        assertEquals(
            NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS,
            selectNgocSiAiRemoteTimeoutMillis(prompt)
        )
    }

    @Test
    fun ordinaryPlaybackAndChatKeepTheirExistingRemoteDeadlines() {
        assertFalse(isNgocSiAiMultimodalVideoAnalysisRequest("AI, tua tới 10 giây"))
        assertFalse(isNgocSiAiMultimodalVideoAnalysisRequest("Xin chào, bạn khỏe không?"))
        assertEquals(
            NGOC_SI_AI_REMOTE_CONVERSATION_TIMEOUT_MILLIS,
            selectNgocSiAiRemoteTimeoutMillis("AI, mở YouTube")
        )
    }

    @Test
    fun deliberateDeepAnalysisUsesHighThinkingAndASeparateBoundedBudget() {
        val prompt = "Hãy kiểm tra toàn diện mã nguồn, phân tích chuyên sâu và tìm nguyên nhân gốc."
        assertEquals(ThinkingLevel.HIGH, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_REASONING_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiGenerationThinkingLevel(prompt))
        assertFalse(requiresMaximumNgocSiAiThinking(prompt))
        assertEquals(60_000L, NGOC_SI_AI_DEEP_REMOTE_CONVERSATION_TIMEOUT_MILLIS)
        assertEquals(60_000L, selectNgocSiAiRemoteTimeoutMillis(prompt))
        assertTrue(ngocSiAiRemoteTimeoutMessage(prompt).contains("60 giây"))
        assertEquals(NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
    }

    @Test
    fun explicitCompletionRequestsUseBalancedReasoningByDefault() {
        val prompt = "Hãy nâng cấp và hoàn thiện đến khi hoàn thành, kiểm tra toàn diện."
        assertEquals(ThinkingLevel.HIGH, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_REASONING_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiGenerationThinkingLevel(prompt))
        assertEquals(ThinkingLevel.LOW, selectNgocSiAiThinkingLevel("Nâng cấp AI"))
    }

    @Test
    fun explicitMaximumReasoningCanStillUseHighThinking() {
        val prompt = "Suy nghĩ thật sâu ở mức tối đa và chứng minh bài toán rất phức tạp."
        assertTrue(requiresMaximumNgocSiAiThinking(prompt))
        assertEquals(ThinkingLevel.HIGH, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_REASONING_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(ThinkingLevel.HIGH, selectNgocSiAiGenerationThinkingLevel(prompt))
    }

    @Test
    fun flashLiteAlwaysReceivesMinimalThinkingForChatAndAppTools() {
        val prompts = listOf(
            "Xin chào",
            "Hãy phân tích kỹ nội dung này",
            "AI, mở YouTube",
            "AI, tìm bài Bolero rồi phát và hẹn giờ 30 phút"
        )
        prompts.forEach { prompt ->
            assertEquals(
                "$prompt must use Flash-Lite's supported MINIMAL level",
                ThinkingLevel.MINIMAL,
                selectNgocSiAiGenerationThinkingLevel(
                    prompt = prompt,
                    offersAppCommandTool = shouldOfferNgocSiAiAppCommandTool(prompt),
                    modelName = NGOC_SI_AI_FAST_CHAT_MODEL_NAME
                )
            )
        }
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
        assertEquals(384, NGOC_SI_AI_SHORT_REPLY_MAX_OUTPUT_TOKENS)
        assertEquals(1_024, NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS)
        assertEquals(2_048, NGOC_SI_AI_DEEP_REPLY_MAX_OUTPUT_TOKENS)
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
            "AI, đi đến thư viện nhạc.",
            "AI, tìm nhạc Bolero."
        ).forEach { phrase ->
            assertTrue("$phrase should expose app command tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun confirmedYoutubeTransportAliasesExposeToolsButNegativeNavigationDoesNot() {
        val playingYoutubeHistory = listOf(
            NgocSiAiMessage("user", "Phát video Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Đang phát video Bolero trên YouTube.")
        )
        listOf(
            "Dừng lại",
            "Dừng video đi",
            "Tạm ngưng video",
            "Tiếp tục",
            "Phát"
        ).forEach { phrase ->
            assertTrue(
                "$phrase should expose the contextual YouTube transport tool",
                shouldOfferNgocSiAiAppCommandTool(phrase, playingYoutubeHistory)
            )
        }

        listOf(
            "Đừng mở YouTube",
            "Đừng vào YouTube",
            "Đừng chuyển sang Radio",
            "Đừng tìm bài trên Drive"
        ).forEach { phrase ->
            assertFalse(
                "$phrase is a negative instruction, not permission to act",
                shouldOfferNgocSiAiAppCommandTool(phrase, playingYoutubeHistory)
            )
        }
    }

    @Test
    fun bareContinueIsConversationUnlessThereIsAnExplicitActionContext() {
        listOf("Tiếp tục", "AI, tiếp tục.", "Làm tiếp đi", "Tiếp nữa").forEach { phrase ->
            assertFalse("$phrase alone should not require app-action tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tiếp tục", listOf(
            NgocSiAiMessage("assistant", "Bạn muốn mình mở Radio không?")
        )))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tiếp tục", listOf(
            NgocSiAiMessage("assistant", "Đang phát video Bolero trên YouTube.")
        )))
    }

    @Test
    fun detailedFollowUpPhraseGetsMediumReasoningAndFlashLiteModel() {
        val phrase = "AI, phân tích kỹ hơn."
        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(phrase))
        assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(phrase))
        assertTrue(isNgocSiAiContextualFollowUp(phrase))
        assertEquals(NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(phrase))
    }

    @Test
    fun singerComparisonUsesFlashLiteAndMediumBudget() {
        val prompt = "AI, so sánh cách hát của ca sĩ Tuấn Vũ với Chế Linh."

        assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(prompt))
        assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
        assertEquals(
            NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS,
            selectNgocSiAiMaxOutputTokens(prompt)
        )
    }

    @Test
    fun wakeWordPrefixedDetailedFollowUpUsesTheSameContextualRoute() {
        val phrases = listOf(
            "AI, phân tích kỹ hơn.",
            "Ngọc Sĩ AI, nói rõ hơn về phần đó.",
            "Hey AI, giải thích thêm ý này."
        )
        phrases.forEach { phrase ->
            assertTrue("$phrase should be contextual", isNgocSiAiContextualFollowUp(phrase))
            assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(phrase))
        }
    }

    @Test
    fun directPauseAliasesExposeTheToolEvenWithoutConversationHistory() {
        listOf(
            "Dừng lại",
            "Dừng video đi",
            "Tạm ngưng video",
            "Ngừng nhạc"
        ).forEach { phrase ->
            assertTrue(
                "$phrase should expose a direct playback control",
                shouldOfferNgocSiAiAppCommandTool(phrase)
            )
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
            "AI, thêm bài đang phát vào yêu thích.",
            "AI, lưu bài này vào playlist Bolero.",
            "AI, thêm bài đang nghe vào playlist Nhạc Trịnh.",
            "AI, thêm tất cả bài trong hàng đợi vào playlist Bolero.",
            "AI, lưu toàn bộ hàng đợi vào playlist Nhạc Trịnh."
        ).forEach { phrase ->
            assertTrue("$phrase should expose app command tools", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun musicTasteAndListeningStatementsDoNotOpenTheAppActionTool() {
        listOf(
            "Tôi thích nghe nhạc Bolero.",
            "AI, tôi thích nghe nhạc Bolero.",
            "Ngọc Sĩ AI, tôi thường nghe YouTube buổi tối.",
            "Tôi đang nghe Radio.",
            "Tôi nghe nhạc Trịnh mỗi ngày."
        ).forEach { phrase ->
            assertFalse("$phrase is a statement, not an app command", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun negativeListeningAndNavigationStatementsDoNotAuthorizeAppActions() {
        listOf(
            "Tôi không muốn nghe nhạc nữa.",
            "AI, tôi không muốn mở YouTube.",
            "Tôi không cần mở Drive.",
            "Tôi không thích nghe nhạc trên Radio.",
            "Tôi không nghe YouTube."
        ).forEach { phrase ->
            assertFalse("$phrase must not authorize an app action", shouldOfferNgocSiAiAppCommandTool(phrase))
        }
    }

    @Test
    fun explicitRequestsAndIndependentActionsStillExposeTheTool() {
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tôi muốn nghe nhạc Bolero."))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, tìm nhạc Bolero rồi phát."))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, mở YouTube."))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tôi thích nghe Bolero, hãy tìm bài trên YouTube rồi phát."))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, đừng phát nhạc nữa."))
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
    fun wakePrefixedHowToQuestionsNeverExposeTheAppActionTool() {
        listOf(
            "AI, làm thế nào để mở YouTube?",
            "Al, chỉ tôi cách mở YouTube.",
            "Ngọc Sĩ AI, hướng dẫn cách tìm nhạc trong Drive.",
            "AI, tôi muốn biết cách mở YouTube.",
            "AI, mở YouTube như thế nào?",
            "AI, tìm Drive bằng cách nào?"
        ).forEach { phrase ->
            assertFalse(
                "$phrase is a how-to question, not permission to execute an action",
                shouldOfferNgocSiAiAppCommandTool(phrase)
            )
        }

        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, mở YouTube."))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("AI, bạn có thể mở YouTube không?"))
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
    fun readOnlyCurrentTrackFeedbackDoesNotLookLikeAnUnfinishedPlaybackAction() {
        assertTrue(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đang phát: Diễm xưa — Khánh Ly",
                "current_track"
            )
        )
        assertTrue(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đang tạm dừng: Diễm xưa",
                "current_track"
            )
        )
        assertFalse(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đang phát video YouTube tiếp theo.",
                "youtube_next"
            )
        )
    }

    @Test
    fun toolFeedbackIsClassifiedAsFailedPendingOrCompleted() {
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(false, "Đã mở YouTube.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Hãy nói tên bài, ví dụ: Phát bài Diễm xưa.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Hãy chọn tốc độ 1.25x.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Không mở được chức năng trong ứng dụng.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Không gửi được lệnh điều khiển.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Hàng đợi đang trống. Hãy mở Thư viện trước.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.PENDING,
            classifyNgocSiAiAgentFeedback(true, "Đang chuyển sang video YouTube tiếp theo.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.PENDING,
            classifyNgocSiAiAgentFeedback(true, "Đã gửi lệnh mở YouTube.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đã tua tiến 10 giây.")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đang phát: Diễm xưa", "current_track")
        )
    }

    @Test
    fun agentStopFeedbackPreservesPendingAndFailedAppState() {
        val pending = ngocSiAiAgentStopFeedback(
            NgocSiAiAgentFeedbackState.PENDING,
            "Đang mở YouTube."
        )
        assertTrue(requireNotNull(pending).contains("chưa xác nhận"))
        assertTrue(pending.contains("Đang mở YouTube."))

        val failed = ngocSiAiAgentStopFeedback(
            NgocSiAiAgentFeedbackState.FAILED,
            "Không tìm thấy bài hát."
        )
        assertTrue(requireNotNull(failed).contains("chưa thành công"))
        assertTrue(failed.contains("Không tìm thấy bài hát."))

        assertNull(
            ngocSiAiAgentStopFeedback(
                NgocSiAiAgentFeedbackState.COMPLETED,
                "Đã tua tiến 10 giây."
            )
        )
    }

    @Test
    fun completedPauseAndPreviousTrackActionsAllowTheNextDependentStep() {
        assertTrue(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đã tạm dừng nhạc.",
                "pause"
            )
        )
        assertTrue(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đã quay lại bài trước.",
                "previous"
            )
        )
        assertFalse(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đang tạm dừng nhạc.",
                "pause"
            )
        )
        assertFalse(
            shouldContinueNgocSiAiAgentSequence(
                true,
                "Đang quay lại bài trước.",
                "previous"
            )
        )
    }

    @Test
    fun agentContinuesOnlyAfterCompletedAppFeedback() {
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã bật phát ngẫu nhiên cho hàng đợi hiện tại."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã tua tiến 10 giây."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã dừng hẳn nhạc."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đang mở YouTube."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đang tìm nhạc theo từ khóa."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đang phát bài Diễm xưa."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đang nạp thư viện nhạc."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đang đặt hẹn giờ tắt nhạc sau 30 phút."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đã gửi lệnh mở YouTube."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Đã yêu cầu hủy hẹn giờ."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(false, "Đã gửi lệnh mở YouTube."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Không tìm thấy bài hát."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Hàng đợi hiện tại chưa có bài thứ hai."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Hàng đợi đang trống. Hãy mở Thư viện trước."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Chưa có video YouTube gần đây để điều khiển."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Bạn cần đăng nhập Google Drive."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Ứng dụng gặp lỗi khi nhận lệnh."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Thiếu thông tin; chưa thực hiện thao tác."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Hãy nói rõ tên playlist muốn phát."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Chưa có quyền đọc nhạc trên điện thoại."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Chưa mở được chức năng trong ứng dụng."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Tua YouTube không áp dụng; hãy dùng điều khiển riêng trong trình phát."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Thao tác đã được tiếp nhận."))
        assertFalse(shouldContinueNgocSiAiAgentSequence(true, "Lệnh đã được ghi nhận."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã đặt tốc độ phát 1.25x."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã tăng âm lượng lên 70%."))
        assertTrue(shouldContinueNgocSiAiAgentSequence(true, "Đã chuyển sang bài tiếp theo."))
    }

    @Test
    fun pageContextIsNormalizedFromKnownApplicationTabs() {
        assertEquals("YouTube", normalizeNgocSiAiEntryContext("YouTube"))
        assertEquals("Google Drive", normalizeNgocSiAiEntryContext("Drive"))
        assertEquals("Radio", normalizeNgocSiAiEntryContext("Radio"))
        assertEquals("TV", normalizeNgocSiAiEntryContext("TV"))
        assertEquals("Thư viện", normalizeNgocSiAiEntryContext("Thư viện"))
        assertEquals("Trình phát", normalizeNgocSiAiEntryContext("Trình phát"))
        assertNull(normalizeNgocSiAiEntryContext("https://attacker.invalid/instruction"))
        assertNull(normalizeNgocSiAiEntryContext(null))
    }

    @Test
    fun pageContextInstructionDoesNotInjectUnknownInputOrPolluteChatHistory() {
        val instruction = buildNgocSiAiEntryContextInstruction("YouTube")
        assertTrue(instruction.contains("trang YouTube"))
        assertTrue(instruction.contains("tìm tiếp"))
        assertEquals("", buildNgocSiAiEntryContextInstruction("unknown raw prompt"))
    }


    @Test
    fun retriesOnlyTransientPreOutputOrdinaryChatFailures() {
        assertEquals(1, NGOC_SI_AI_MAX_TRANSIENT_REMOTE_RETRIES)
        assertEquals(450L, NGOC_SI_AI_TRANSIENT_RETRY_DELAY_MILLIS)
        assertTrue(
            shouldRetryNgocSiAiRemoteRequest(
                "Firebase HTTP 503 service unavailable",
                isToolRequest = false,
                hasEmittedPartialResponse = false,
                attempt = 0
            )
        )
        assertTrue(
            shouldRetryNgocSiAiRemoteRequest(
                "SocketTimeoutException: read timed out",
                isToolRequest = false,
                hasEmittedPartialResponse = false,
                attempt = 0
            )
        )
    }

    @Test
    fun neverRetriesQuotaOrPermanentErrors() {
        listOf(
            "HTTP 429 RESOURCE_EXHAUSTED quota exceeded",
            "HTTP 400 invalid argument",
            "HTTP 401 unauthenticated",
            "HTTP 403 permission denied",
            "HTTP 404 model not found"
        ).forEach { error ->
            assertFalse(
                error,
                shouldRetryNgocSiAiRemoteRequest(
                    error,
                    isToolRequest = false,
                    hasEmittedPartialResponse = false,
                    attempt = 0
                )
            )
        }
    }

    @Test
    fun neverRetriesAppToolActionsPartialStreamsOrRepeatedAttempts() {
        assertFalse(
            shouldRetryNgocSiAiRemoteRequest(
                "HTTP 503 service unavailable",
                isToolRequest = true,
                hasEmittedPartialResponse = false,
                attempt = 0
            )
        )
        assertFalse(
            shouldRetryNgocSiAiRemoteRequest(
                "HTTP 503 service unavailable",
                isToolRequest = false,
                hasEmittedPartialResponse = true,
                attempt = 0
            )
        )
        assertFalse(
            shouldRetryNgocSiAiRemoteRequest(
                "HTTP 503 service unavailable",
                isToolRequest = false,
                hasEmittedPartialResponse = false,
                attempt = 1
            )
        )
        assertFalse(
            shouldRetryNgocSiAiRemoteRequest(
                "UnknownHostException: unable to resolve host",
                isToolRequest = false,
                hasEmittedPartialResponse = false,
                attempt = 0
            )
        )
    }

    @Test
    fun substantiveKnowledgeQuestionsUseFlashWithoutIncreasingThinkingLatency() {
        assertEquals("gemini-3.8-flash", NGOC_SI_AI_REASONING_MODEL_NAME)
        assertTrue(isNgocSiAiKnowledgeQuestion("Thủ đô Việt Nam là gì?"))
        assertEquals(
            NGOC_SI_AI_FAST_CHAT_MODEL_NAME,
            selectNgocSiAiModelName("Thủ đô Việt Nam là gì?")
        )
        assertEquals(ThinkingLevel.MINIMAL, selectNgocSiAiGenerationThinkingLevel("Thủ đô Việt Nam là gì?"))
        assertEquals(NGOC_SI_AI_KNOWLEDGE_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens("Thủ đô Việt Nam là gì?"))
        assertFalse(isNgocSiAiKnowledgeQuestion("Xin chào"))
        assertEquals(
            NGOC_SI_AI_FAST_CHAT_MODEL_NAME,
            selectNgocSiAiModelName("Xin chào")
        )
    }

    @Test
    fun naturalVietnameseInterrogativesAreRecognizedEvenWithoutSpeechPunctuation() {
        val questions = listOf(
            "Bạn thích ca sĩ nào?",
            "Bạn thích ca sĩ nào",
            "Tôi muốn biết ca sĩ nào nổi tiếng",
            "Tôi thích thể loại nhạc gì",
            "Ai là ca sĩ bạn yêu thích?"
        )
        questions.forEach { prompt ->
            assertTrue("$prompt should use the substantive-question route", isNgocSiAiKnowledgeQuestion(prompt))
            assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
            assertEquals(NGOC_SI_AI_KNOWLEDGE_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
        }
        assertFalse(isNgocSiAiKnowledgeQuestion("Xin chào"))
        assertFalse(isNgocSiAiKnowledgeQuestion("Xin chào AI"))
        assertFalse(isNgocSiAiKnowledgeQuestion("Thời tiết có ảnh hưởng đến pin điện thoại không?"))
    }

    @Test
    fun shortNaturalContinuationsUseFlashLiteAndKeepTheContextAnswerBudget() {
        val prompts = listOf(
            "Tiếp tục",
            "Làm tiếp đi",
            "Nói rõ hơn về phần đó",
            "Ví dụ cụ thể?",
            "Cho tôi một ví dụ",
            "Giải thích thêm ý này"
        )
        prompts.forEach { prompt ->
            assertTrue("$prompt should be treated as a contextual follow-up", isNgocSiAiContextualFollowUp(prompt))
            assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
            assertEquals(ThinkingLevel.LOW, selectNgocSiAiThinkingLevel(prompt))
            assertEquals(NGOC_SI_AI_KNOWLEDGE_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
        }
        assertFalse(isNgocSiAiContextualFollowUp("Xin chào AI"))
        assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName("Xin chào AI"))
    }

    @Test
    fun detailedContinuationRequestsUseMediumReasoningAndLargerBudget() {
        listOf("Phân tích thêm phần đó", "Giải thích chi tiết hơn").forEach { prompt ->
            assertTrue(isNgocSiAiContextualFollowUp(prompt))
            assertEquals(ThinkingLevel.MEDIUM, selectNgocSiAiThinkingLevel(prompt))
            assertEquals(NGOC_SI_AI_FAST_CHAT_MODEL_NAME, selectNgocSiAiModelName(prompt))
            assertEquals(NGOC_SI_AI_COMPLEX_REPLY_MAX_OUTPUT_TOKENS, selectNgocSiAiMaxOutputTokens(prompt))
        }
    }

    @Test
    fun mediumReasoningUsesFlashLiteToPreserveFlashQuota() {
        assertEquals("gemini-3.8-flash", NGOC_SI_AI_REASONING_MODEL_NAME)
        assertEquals(
            NGOC_SI_AI_FAST_CHAT_MODEL_NAME,
            selectNgocSiAiModelName("Hãy phân tích kỹ nguyên nhân và lập kế hoạch khắc phục.")
        )
    }

    @Test
    fun appToolRequestsUseFlashLiteToPreserveFlashQuota() {
        assertEquals(
            NGOC_SI_AI_FAST_CHAT_MODEL_NAME,
            selectNgocSiAiModelName("Mở YouTube", offersAppCommandTool = true)
        )
    }

    @Test
    fun onlyDeliberateHighDepthRequestsUseGemini38() {
        val commonRequests = listOf(
            "Thủ đô Việt Nam là gì?",
            "Mở YouTube",
            "Phân tích thêm phần đó",
            "Phân tích video YouTube này",
            "So sánh cách hát của hai ca sĩ"
        )
        commonRequests.forEach { prompt ->
            assertEquals(
                "$prompt should preserve the Flash quota pool",
                NGOC_SI_AI_FAST_CHAT_MODEL_NAME,
                selectNgocSiAiModelName(prompt, offersAppCommandTool = prompt == "Mở YouTube")
            )
        }
        val deepPrompt = "Hãy kiểm tra toàn diện mã nguồn, phân tích chuyên sâu và tìm nguyên nhân gốc."
        assertEquals(ThinkingLevel.HIGH, selectNgocSiAiThinkingLevel(deepPrompt))
        assertEquals(NGOC_SI_AI_REASONING_MODEL_NAME, selectNgocSiAiModelName(deepPrompt))
    }

    @Test
    fun greetingsKeepMinimalThinkingAndKnowledgeQuestionsUseFlashLowThinking() {
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel("Xin chào")
        )
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel("Thủ đô Việt Nam là gì?")
        )
    }

    @Test
    fun flashLiteUsesOnlySupportedMinimalThinkingForReasoningAndToolRequests() {
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel(
                "Hãy phân tích kỹ nguyên nhân và lập kế hoạch khắc phục."
            )
        )
        assertEquals(
            ThinkingLevel.MINIMAL,
            selectNgocSiAiGenerationThinkingLevel("Mở YouTube", offersAppCommandTool = true)
        )
        assertEquals(
            ThinkingLevel.MEDIUM,
            selectNgocSiAiGenerationThinkingLevel(
                "Hãy phân tích kỹ nguyên nhân và lập kế hoạch khắc phục.",
                modelName = NGOC_SI_AI_REASONING_MODEL_NAME
            )
        )
        assertEquals(
            ThinkingLevel.LOW,
            selectNgocSiAiGenerationThinkingLevel(
                "Mở YouTube",
                offersAppCommandTool = true,
                modelName = NGOC_SI_AI_REASONING_MODEL_NAME
            )
        )
    }
    @Test
    fun shortPronounCommandsReuseOnlyRecentMediaContext() {
        val mediaHistory = listOf(
            NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Mình vừa tìm thấy video nhạc Bolero trên YouTube.")
        )
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Phát nó đi", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Bài đó phát đi", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Mở video đó", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Thêm nó vào hàng đợi", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Bạn có thể phát nó không?", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Phát bài vừa tìm", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Nghe bài vừa rồi", mediaHistory))
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tìm video tương tự", mediaHistory))

        // Multiple candidates from the same source are still ambiguous.
        listOf(
            "Mình tìm thấy 3 video Bolero trên YouTube.",
            "Mình tìm thấy hai video Bolero trên YouTube.",
            "Có nhiều bài hát Bolero trên YouTube.",
            "Đây là các video phù hợp trên YouTube."
        ).forEach { assistantAnswer ->
            assertFalse(
                assistantAnswer,
                shouldOfferNgocSiAiAppCommandTool(
                    "Phát nó đi",
                    listOf(
                        NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
                        NgocSiAiMessage("assistant", assistantAnswer)
                    )
                )
            )
        }
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
                    NgocSiAiMessage("assistant", "Mình tìm thấy một video Bolero trên YouTube.")
                )
            )
        )

        // The immediate assistant answer must resolve one source, not just mention media.
        assertFalse(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm một bài nhạc"),
                    NgocSiAiMessage("assistant", "Mình tìm thấy bài hát Diễm xưa.")
                )
            )
        )
        assertFalse(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm một bài nhạc"),
                    NgocSiAiMessage("assistant", "Mình thấy video trên YouTube và một bản trong Google Drive.")
                )
            )
        )
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm bài trong thư viện"),
                    NgocSiAiMessage("assistant", "Mình tìm thấy bài hát Diễm xưa trong thư viện nhạc trên điện thoại.")
                )
            )
        )
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm trên Google Drive"),
                    NgocSiAiMessage("assistant", "Mình tìm thấy tệp nhạc Diễm xưa trong Google Drive.")
                )
            )
        )
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool(
                "Mở kênh đó",
                listOf(
                    NgocSiAiMessage("user", "Tìm kênh truyền hình"),
                    NgocSiAiMessage("assistant", "Mình tìm thấy kênh VTV1 trong danh sách TV.")
                )
            )
        )

        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự"))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Nó là gì?", mediaHistory))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tại sao nó dừng?", mediaHistory))
        assertFalse(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
                    NgocSiAiMessage("assistant", "Bolero là một thể loại nhạc trữ tình phổ biến.")
                )
            )
        )
        assertFalse(
            shouldOfferNgocSiAiAppCommandTool(
                "Phát nó đi",
                listOf(
                    NgocSiAiMessage("user", "Tôi đang hỏi về lịch sử Việt Nam."),
                    NgocSiAiMessage("assistant", "Mình có thể giải thích thêm về giai đoạn đó.")
                )
            )
        )
    }


    @Test
    fun contextualSearchOnlyFollowUpsAreLockedToOneSupportedRecentSource() {
        val youtubeHistory = listOf(
            NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Mình vừa tìm thấy video nhạc Bolero trên YouTube.")
        )
        assertEquals(
            "search_youtube",
            resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", youtubeHistory)
        )
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", youtubeHistory))

        val driveHistory = listOf(
            NgocSiAiMessage("user", "Tìm nhạc trên Google Drive"),
            NgocSiAiMessage("assistant", "Mình tìm thấy tệp nhạc Diễm xưa trong Google Drive.")
        )
        assertEquals(
            "search_drive",
            resolveNgocSiAiContextualSearchOnlyAction("Tìm bài vừa tìm", driveHistory)
        )
        assertTrue(shouldOfferNgocSiAiAppCommandTool("Tìm bài vừa tìm", driveHistory))

        val onlineHistory = listOf(
            NgocSiAiMessage("user", "Tìm nhạc trên Audius"),
            NgocSiAiMessage("assistant", "Mình tìm thấy bài nhạc Bolero trên Audius.")
        )
        assertEquals(
            "search_online_music",
            resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", onlineHistory)
        )

        // A search-only follow-up never maps to a play/autoplay action.
        assertNull(
            resolveNgocSiAiContextualSearchOnlyAction(
                "Tìm bài tương tự rồi phát",
                youtubeHistory
            )
        )

        val multipleYoutubeResults = listOf(
            NgocSiAiMessage("user", "Tìm nhạc Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Mình tìm thấy 3 video Bolero trên YouTube.")
        )
        assertNull(
            resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", multipleYoutubeResults)
        )
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", multipleYoutubeResults))

        val mixedSources = listOf(
            NgocSiAiMessage("user", "Tìm nhạc"),
            NgocSiAiMessage("assistant", "Mình tìm thấy video trên YouTube và tệp nhạc trong Google Drive.")
        )
        assertNull(resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", mixedSources))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", mixedSources))

        val missingResult = listOf(
            NgocSiAiMessage("user", "Tìm nhạc trên YouTube"),
            NgocSiAiMessage("assistant", "Mình không tìm thấy video nào trên YouTube.")
        )
        assertNull(resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", missingResult))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", missingResult))

        val unsupportedSource = listOf(
            NgocSiAiMessage("user", "Mở Radio"),
            NgocSiAiMessage("assistant", "Đang phát chương trình Radio VOV.")
        )
        assertNull(resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", unsupportedSource))
        assertFalse(shouldOfferNgocSiAiAppCommandTool("Tìm bài tương tự", unsupportedSource))

        assertNull(resolveNgocSiAiContextualSearchOnlyAction("Tìm bài tương tự", emptyList()))
        assertNull(resolveNgocSiAiContextualSearchOnlyAction("Tìm Bolero", youtubeHistory))

        assertEquals(
            "search_youtube",
            resolveNgocSiAiContextualSearchOnlyAction(
                "Tìm bài hát tương tự với bài đó",
                youtubeHistory
            )
        )
        assertTrue(
            shouldOfferNgocSiAiAppCommandTool(
                "Tìm bài hát tương tự với bài đó",
                youtubeHistory
            )
        )
        assertFalse(isNgocSiAiContextualSearchQuerySpecificEnough("bài tương tự"))
        assertFalse(isNgocSiAiContextualSearchQuerySpecificEnough("Tìm bài hát tương tự"))
        assertFalse(isNgocSiAiContextualSearchQuerySpecificEnough("similar songs"))
        assertTrue(isNgocSiAiContextualSearchQuerySpecificEnough("Bolero"))
        assertTrue(isNgocSiAiContextualSearchQuerySpecificEnough("Diễm xưa Trịnh Công Sơn"))
    }


    @Test
    fun agentFeedbackDistinguishesCompletedActionsFromPendingAndFailedActions() {
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đã mở trang YouTube.", "open_youtube")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đã phát video Bolero.", "youtube_play")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đã tìm thấy 5 bài hát.", "search_online_music")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.COMPLETED,
            classifyNgocSiAiAgentFeedback(true, "Đang mở YouTube.", "open_youtube")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.PENDING,
            classifyNgocSiAiAgentFeedback(true, "Đang phát video.", "youtube_play")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(true, "Không mở được YouTube.", "open_youtube")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.FAILED,
            classifyNgocSiAiAgentFeedback(false, "Đã mở YouTube.", "open_youtube")
        )

        listOf(
            "Đang mở trang chủ." to "open_home",
            "Đang mở Thư viện nhạc." to "open_library",
            "Đang mở YouTube." to "open_youtube",
            "Đang mở Google Drive." to "open_drive",
            "Đang mở Radio." to "open_radio",
            "Đang mở Truyền hình." to "open_tv",
            "Đang mở hẹn giờ tắt nhạc." to "open_sleep_timer"
        ).forEach { (feedback, action) ->
            assertEquals(
                "$action should complete after its navigation Intent is accepted",
                NgocSiAiAgentFeedbackState.COMPLETED,
                classifyNgocSiAiAgentFeedback(true, feedback, action)
            )
        }

        assertEquals(
            NgocSiAiAgentFeedbackState.PENDING,
            classifyNgocSiAiAgentFeedback(true, "Đang mở đài VOV3.", "play_radio_station")
        )
        assertEquals(
            NgocSiAiAgentFeedbackState.PENDING,
            classifyNgocSiAiAgentFeedback(true, "Đang tìm trên YouTube: Bolero", "search_youtube")
        )
    }

    @Test
    fun contextualYoutubeTransportFollowUpsKeepTheConfirmedActiveSource() {
        val playingYoutubeHistory = listOf(
            NgocSiAiMessage("user", "Phát video Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Đang phát video Bolero trên YouTube.")
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm dừng nó", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm dừng video đó", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Dừng lại", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Dừng video đi", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Dừng phát video đi", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("AI, dừng phát video đó", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_pause",
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm ngưng video", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_play",
            resolveNgocSiAiContextualYoutubeTransportAction("Phát nó đi", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_next",
            resolveNgocSiAiContextualYoutubeTransportAction("Tiếp theo", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_next",
            resolveNgocSiAiContextualYoutubeTransportAction("Chuyển sang video tiếp theo", playingYoutubeHistory)
        )
        assertEquals(
            "youtube_previous",
            resolveNgocSiAiContextualYoutubeTransportAction("Bài trước", playingYoutubeHistory)
        )

        val pausedYoutubeHistory = listOf(
            NgocSiAiMessage("user", "Tạm dừng YouTube"),
            NgocSiAiMessage("assistant", "Đang tạm dừng video Bolero trên YouTube.")
        )
        assertEquals(
            "youtube_play",
            resolveNgocSiAiContextualYoutubeTransportAction("Phát tiếp nó đi", pausedYoutubeHistory)
        )
        assertEquals(
            "youtube_play",
            resolveNgocSiAiContextualYoutubeTransportAction("Tiếp tục", pausedYoutubeHistory)
        )
        assertEquals(
            "youtube_play",
            resolveNgocSiAiContextualYoutubeTransportAction("Phát", pausedYoutubeHistory)
        )
        assertEquals(
            "youtube_play",
            resolveNgocSiAiContextualYoutubeTransportAction("Chơi tiếp", pausedYoutubeHistory)
        )

        // Explicit alternate-source and negative requests must not inherit YouTube.
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm dừng Drive", playingYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Đừng phát nó", pausedYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("AI, đừng phát video đó", pausedYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("dung phat video di", pausedYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Đừng chuyển bài", pausedYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Đừng mở YouTube", pausedYoutubeHistory)
        )

        // A search result is not proof that this video is the active player item.
        val searchOnlyHistory = listOf(
            NgocSiAiMessage("user", "Tìm video Bolero trên YouTube"),
            NgocSiAiMessage("assistant", "Mình vừa tìm thấy video Bolero trên YouTube.")
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm dừng nó", searchOnlyHistory)
        )

        // Never infer YouTube when the latest answer contains another source or multiple videos.
        val mixedSourceHistory = listOf(
            NgocSiAiMessage("user", "Tìm video"),
            NgocSiAiMessage("assistant", "Đang phát video trên YouTube và có bản nhạc trong Google Drive.")
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tạm dừng nó", mixedSourceHistory)
        )

        val multipleVideoHistory = listOf(
            NgocSiAiMessage("user", "Tìm video YouTube"),
            NgocSiAiMessage("assistant", "Đang phát một trong 3 video trên YouTube.")
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tiếp theo", multipleVideoHistory)
        )

        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction(
                "Tạm dừng nó",
                listOf(NgocSiAiMessage("user", "Tìm video trên YouTube"))
            )
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tìm bài tương tự", playingYoutubeHistory)
        )
        assertNull(
            resolveNgocSiAiContextualYoutubeTransportAction("Tại sao video đó dừng?", playingYoutubeHistory)
        )
    }

}


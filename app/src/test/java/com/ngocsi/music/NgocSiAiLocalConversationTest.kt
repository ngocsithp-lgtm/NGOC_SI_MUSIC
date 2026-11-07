package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiLocalConversationTest {
    @Test
    fun accentedGreetingIsHandledLocally() {
        assertEquals(
            "Chào bạn. Mình đang nghe đây. Bạn muốn trò chuyện hay cần mình điều khiển nhạc trong ứng dụng?",
            localConversationFallback("Xin chào bạn")
        )
    }

    @Test
    fun empathyAndSmallTalkDoNotNeedOnlineModel() {
        assertTrue(localConversationFallback("Tôi buồn lắm")?.contains("không phán xét") == true)
        assertTrue(localConversationFallback("Mình mệt quá")?.contains("khá mệt") == true)
        assertTrue(localConversationFallback("Tôi muốn tâm sự")?.contains("cứ kể") == true)
        assertNotNull(localConversationFallback("Cảm ơn bạn"))
    }

    @Test
    fun slowAiQuestionGetsImmediateLocalExplanationWithoutGemini() {
        val answer = localConversationFallback("Tại sao AI trả lời chậm?")
        assertNotNull(answer)
        assertTrue(answer!!.contains("Gemini trực tuyến"))
        assertTrue(answer.contains("25 giây"))
        assertTrue(answer.contains("40 giây"))
        assertTrue(answer.contains("lệnh điều khiển nhạc"))
    }

    @Test
    fun quotaQuestionGetsHonestLocalExplanation() {
        val answer = localConversationFallback("Tại sao AI hết hạn mức?")
        assertNotNull(answer)
        assertTrue(answer!!.contains("Google áp dụng"))
        assertTrue(answer.contains("không bật thanh toán"))
        assertTrue(answer.contains("lệnh điều khiển cục bộ vẫn dùng được"))
    }

    @Test
    fun configuredAiDoesNotConsumeNaturalContinuationWithTheOfflineTemplate() {
        assertNull(
            localConversationFallback(
                userText = "Tiếp tục",
                previousAssistantReply = "Ưu tiên kiểm tra chất lượng AI.",
                allowContinuationFallback = false
            )
        )
        assertNull(
            localConversationFallback(
                userText = "Làm tiếp đi",
                previousAssistantReply = "Ưu tiên kiểm tra chất lượng AI.",
                allowContinuationFallback = false
            )
        )
        // Offline callers retain the deterministic explanation by default.
        assertTrue(
            requireNotNull(localConversationFallback("Tiếp tục", "Kiểm tra lệnh giọng nói."))
                .contains("Kiểm tra lệnh giọng nói.")
        )
    }

    @Test
    fun continuationUsesPreviousReplyWithoutPretendingToReasonFurther() {
        val answer = localConversationFallback("Tiếp tục", "Ưu tiên kiểm tra lệnh dừng nhạc.")
        assertNotNull(answer)
        assertTrue(answer!!.contains("Ưu tiên kiểm tra lệnh dừng nhạc."))
        assertTrue(answer.contains("không muốn tự đoán thêm"))

        val naturalContinuation = localConversationFallback(
            "Làm tiếp đi",
            "Ưu tiên kiểm tra lệnh dừng nhạc."
        )
        assertNotNull(naturalContinuation)
        assertTrue(naturalContinuation!!.contains("Ưu tiên kiểm tra lệnh dừng nhạc."))
    }

    @Test
    fun contextualWhyQuestionGetsHonestLocalReplyWhenRemoteQuotaFails() {
        val previous = "Gemini đang bị giới hạn nên chưa trả lời được câu hỏi trước."
        val answer = localConversationFallbackAfterRemoteError(
            userText = "Tại sao?",
            previousAssistantReply = previous,
            remoteError = "AI_QUOTA_429"
        )
        assertNotNull(answer)
        assertTrue(answer!!.contains(previous))
        assertTrue(answer.contains("Gemini đang bị giới hạn hạn mức"))
        assertTrue(answer.contains("chưa muốn đoán lý do"))
    }

    @Test
    fun followUpAfterQuotaErrorKeepsTheMostRecentlySpokenErrorAsContext() {
        val lastSpokenReply =
            "Chat trực tuyến đang bị giới hạn hạn mức. Mã chẩn đoán: AI_QUOTA_429_PROJECT_OR_CAPACITY."
        val answer = localConversationFallbackAfterRemoteError(
            userText = "Tại sao vậy?",
            previousAssistantReply = lastSpokenReply,
            remoteError = "Mã chẩn đoán: AI_QUOTA_429_PROJECT_OR_CAPACITY"
        )

        assertNotNull(answer)
        assertTrue(answer!!.contains(lastSpokenReply))
        assertTrue(answer.contains("Gemini đang bị giới hạn hạn mức"))
        assertTrue(answer.contains("chưa muốn đoán lý do"))
    }

    @Test
    fun naturalFollowUpVariantsStayLinkedToThePreviousAnswerAfterRemoteFailure() {
        val previous = "Mình đề xuất kiểm tra lịch sử hội thoại trước."
        val followUps = listOf(
            "Nói rõ hơn về phần đó?",
            "Ví dụ cụ thể?",
            "Cho tôi một ví dụ",
            "Giải thích thêm ý này",
            "Cụ thể hơn nữa",
            "Còn lý do nào khác?",
            "Ý này là gì?",
            "Nói thêm về bước trước"
        )

        followUps.forEach { phrase ->
            val answer = localConversationFallbackAfterRemoteError(
                userText = phrase,
                previousAssistantReply = previous,
                remoteError = "NETWORK"
            )
            assertNotNull("$phrase should keep the previous turn as context", answer)
            assertTrue("$phrase should quote the previous answer", answer!!.contains(previous))
            assertTrue("$phrase should identify the network problem", answer.contains("Kết nối chat trực tuyến"))
        }
    }

    @Test
    fun detailedAnalysisFollowUpStaysLinkedAfterGeminiQuotaFailure() {
        val previous = "Ví dụ vừa rồi nói về cách hòa âm Bolero."
        val answer = localConversationFallbackAfterRemoteError(
            userText = "AI, phân tích kỹ hơn.",
            previousAssistantReply = previous,
            remoteError = "AI_QUOTA_429"
        )
        assertNotNull(answer)
        assertTrue(answer!!.contains(previous))
        assertTrue(answer.contains("Gemini đang bị giới hạn hạn mức"))
    }

    @Test
    fun wakeWordPrefixedDetailedAnalysisStaysLinkedAfterGeminiQuotaFailure() {
        val previous = "Ví dụ vừa rồi nói về cách hòa âm Bolero."
        val answer = localConversationFallbackAfterRemoteError(
            userText = "AI, phân tích kỹ hơn.",
            previousAssistantReply = previous,
            remoteError = "AI_QUOTA_429"
        )
        assertNotNull(answer)
        assertTrue(answer!!.contains(previous))
        assertTrue(answer.contains("Gemini đang bị giới hạn hạn mức"))
    }

    @Test
    fun unrelatedStatementDoesNotInheritThePreviousAnswerAsContext() {
        assertNull(
            localConversationFallbackAfterRemoteError(
                userText = "Tôi thích màu xanh.",
                previousAssistantReply = "Mình đề xuất kiểm tra lịch sử hội thoại trước.",
                remoteError = "NETWORK"
            )
        )
    }

    @Test
    fun unsupportedFollowUpDoesNotReceiveInventedContextWhenNoPreviousAnswerExists() {
        assertNull(
            localConversationFallbackAfterRemoteError(
                userText = "Tại sao?",
                previousAssistantReply = null,
                remoteError = "NETWORK"
            )
        )
    }

    @Test
    fun unsupportedFactualQuestionIsNotGivenAnInventedOfflineAnswer() {
        assertNull(localConversationFallback("Thủ đô của Pháp là gì?"))
    }
}

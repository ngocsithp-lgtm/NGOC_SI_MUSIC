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
    fun quotaQuestionGetsHonestLocalExplanation() {
        val answer = localConversationFallback("Tại sao AI hết hạn mức?")
        assertNotNull(answer)
        assertTrue(answer!!.contains("Google áp dụng"))
        assertTrue(answer.contains("không bật thanh toán"))
        assertTrue(answer.contains("lệnh điều khiển cục bộ vẫn dùng được"))
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

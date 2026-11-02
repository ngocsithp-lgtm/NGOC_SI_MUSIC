package com.ngocsi.music

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiAppHelpRulesTest {
    @Test
    fun broadAppGuideQuestionFromTheReportedScreenIsAnsweredLocally() {
        val answer = localNgocSiAiAppHelpFallback(
            "AI AI hãy giải thích những chức năng của ứng dụng và hướng dẫn tôi sử dụng"
        )

        assertTrue(answer != null)
        assertTrue(answer!!.contains("Thư viện nhạc"))
        assertTrue(answer.contains("YouTube"))
        assertTrue(answer.contains("Google Drive"))
        assertTrue(answer.contains("Radio và TV"))
        assertTrue(answer.contains("Hàng đợi"))
        assertTrue(answer.contains("Hẹn giờ"))
        assertTrue(answer.contains("Gemini"))
    }

    @Test
    fun featureSpecificGuidesAreReturnedWithoutCallingOnlineModel() {
        assertTrue(localNgocSiAiAppHelpFallback("Hướng dẫn tôi sử dụng YouTube")!!.contains("ô tìm kiếm"))
        val youtubeGuide = localNgocSiAiAppHelpFallback("Giải thích cách dùng YouTube")!!
        assertTrue(youtubeGuide.contains("phát ngẫu nhiên"))
        assertTrue(youtubeGuide.contains("Xem sau"))
        assertTrue(localNgocSiAiAppHelpFallback("Giải thích cách dùng Google Drive")!!.contains("đăng nhập"))
        assertTrue(localNgocSiAiAppHelpFallback("Hướng dẫn nghe Radio")!!.contains("chọn đài"))
        assertTrue(localNgocSiAiAppHelpFallback("Cách sử dụng hẹn giờ")!!.contains("đếm ngược"))
    }

    @Test
    fun explainsTheWakeWordAndContinuousVoiceControlOffline() {
        val byVoice = localNgocSiAiAppHelpFallback(
            "Làm sao để AI điều khiển YouTube bằng giọng nói?"
        )!!
        assertTrue(byVoice.contains("nói “AI”"))
        assertTrue(byVoice.contains("không cần gọi lại"))
        assertTrue(byVoice.contains("tạm dừng AI"))

        val wakeWordGuide = localNgocSiAiAppHelpFallback("Hướng dẫn sử dụng từ khóa AI")!!
        assertTrue(wakeWordGuide.contains("bật chế độ nghe từ khóa"))
        assertTrue(wakeWordGuide.contains("nhạc vẫn tiếp tục phát"))
    }

    @Test
    fun unrelatedKnowledgeQuestionsAreNotAnsweredByTheAppGuide() {
        assertNull(localNgocSiAiAppHelpFallback("Thủ đô của Pháp là gì?"))
        assertNull(localNgocSiAiAppHelpFallback("Hôm nay tôi muốn nghe nhạc"))
        // The letters "ai" inside "bài" must not be treated as the assistant name.
        assertNull(localNgocSiAiAppHelpFallback("Làm sao điều khiển bài hát?"))
    }
}

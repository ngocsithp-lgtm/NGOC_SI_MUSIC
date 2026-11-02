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
        assertTrue(localNgocSiAiAppHelpFallback("Giải thích cách dùng Google Drive")!!.contains("đăng nhập"))
        assertTrue(localNgocSiAiAppHelpFallback("Hướng dẫn nghe Radio")!!.contains("chọn đài"))
        assertTrue(localNgocSiAiAppHelpFallback("Cách sử dụng hẹn giờ")!!.contains("đếm ngược"))
    }

    @Test
    fun unrelatedKnowledgeQuestionsAreNotAnsweredByTheAppGuide() {
        assertNull(localNgocSiAiAppHelpFallback("Thủ đô của Pháp là gì?"))
        assertNull(localNgocSiAiAppHelpFallback("Hôm nay tôi muốn nghe nhạc"))
    }
}

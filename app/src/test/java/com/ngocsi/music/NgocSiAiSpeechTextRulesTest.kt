package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NgocSiAiSpeechTextRulesTest {
    @Test
    fun removesMarkdownEmphasisWithoutRemovingTheWords() {
        assertEquals(
            "Ngọc Sĩ đã mở YouTube.",
            NgocSiAiSpeechTextRules.cleanForSpeech("**Ngọc Sĩ** đã mở *YouTube*.")
        )
    }

    @Test
    fun convertsHeadingsListsAndLinksToNaturalSpeech() {
        val markdown = "## Kết quả\n- **Đã mở** [YouTube](https://example.com)."
        assertEquals("Kết quả. Đã mở YouTube.", NgocSiAiSpeechTextRules.cleanForSpeech(markdown))
    }

    @Test
    fun handlesEmptyAndWhitespaceOnlyReplies() {
        assertEquals("", NgocSiAiSpeechTextRules.cleanForSpeech(""))
        assertEquals("", NgocSiAiSpeechTextRules.cleanForSpeech("   \n  "))
    }

    @Test
    fun keepsTechnicalQuotaCodesOutOfSpokenReplies() {
        assertEquals(
            "Hạn mức miễn phí của mô hình Gemini có thể đã hết. Không gửi lại liên tục; ứng dụng vẫn giữ chế độ không thanh toán.",
            NgocSiAiSpeechTextRules.cleanForSpeech(
                "Hạn mức miễn phí của mô hình Gemini có thể đã hết. Mã chẩn đoán: AI_QUOTA_429_FREE_TIER. Không gửi lại liên tục; ứng dụng vẫn giữ chế độ không thanh toán."
            )
        )
        assertEquals(
            "Lệnh nhạc trên thiết bị vẫn hoạt động.",
            NgocSiAiSpeechTextRules.cleanForSpeech(
                "Lệnh nhạc trên thiết bị vẫn hoạt động. Mã: AI_QUOTA_COOLDOWN."
            )
        )
    }

    @Test
    fun preservesVietnamesePunctuationAndText() {
        assertEquals(
            "Đã xong, bạn nhé!",
            NgocSiAiSpeechTextRules.cleanForSpeech("Đã xong , bạn nhé!")
        )
    }
}

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
    fun preservesVietnamesePunctuationAndText() {
        assertEquals(
            "Đã xong, bạn nhé!",
            NgocSiAiSpeechTextRules.cleanForSpeech("Đã xong , bạn nhé!")
        )
    }
}

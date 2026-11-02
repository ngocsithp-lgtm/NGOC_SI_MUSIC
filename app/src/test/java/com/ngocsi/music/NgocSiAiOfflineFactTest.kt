package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NgocSiAiOfflineFactTest {
    @Test
    fun answersVietnamCapitalWithoutRemoteAi() {
        val questions = listOf(
            "Thủ đô Việt Nam là gì?",
            "Thủ đô của Việt Nam là gì",
            "Việt Nam có thủ đô là gì?",
            "Thủ đô nước ta là gì?"
        )

        questions.forEach { question ->
            assertEquals(
                "Thủ đô của Việt Nam là Hà Nội.",
                localConversationFallback(question)
            )
        }
    }
}

package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiOnDeviceModelTest {
    @Test
    fun removesQwenThinkingBlockBeforeShowingOrSpeakingTheAnswer() {
        assertEquals(
            "Hà Nội là thủ đô của Việt Nam.",
            stripNgocSiAiOnDeviceThinkingContent(
                "<think>Need reason about the answer.\nDo not show this.</think>\nHà Nội là thủ đô của Việt Nam."
            )
        )
    }

    @Test
    fun preservesAnOrdinaryReplyWithoutThinkingMarkers() {
        assertEquals(
            "Xin chào. Tôi là NGỌC SĨ AI.",
            stripNgocSiAiOnDeviceThinkingContent("  Xin chào. Tôi là NGỌC SĨ AI.  ")
        )
    }

    @Test
    fun localModelUsesASeparateLargeFileOutsideTheApk() {
        assertTrue(NgocSiAiOnDeviceModel.MODEL_SIZE_BYTES > 500L * 1024L * 1024L)
        assertEquals("586 MiB", NgocSiAiOnDeviceModel.MODEL_SIZE_LABEL)
    }
}

package com.ngocsi.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiToolExecutionTest {
    @Test
    fun successfulToolFeedbackIsPreserved() = runBlocking {
        val expected = NgocSiAiToolResult(
            routed = true,
            feedback = "Đã tua tiến 10 giây."
        )

        val actual = executeNgocSiAiToolSafely(
            executeTool = { _, _ -> expected },
            action = "seek_forward_10",
            query = ""
        )

        assertEquals(expected, actual)
    }

    @Test
    fun ordinaryToolExceptionsBecomeSafeFailureFeedback() = runBlocking {
        val actual = executeNgocSiAiToolSafely(
            executeTool = { _, _ -> throw IllegalStateException("private implementation detail") },
            action = "open_youtube",
            query = ""
        )

        assertFalse(actual.routed)
        assertEquals(
            "Ứng dụng chưa thực hiện được lệnh này. Vui lòng thử lại.",
            actual.feedback
        )
        assertFalse(actual.feedback.contains("private implementation detail"))
    }

    @Test
    fun coroutineCancellationIsRethrownInsteadOfConvertedIntoToolFailure() = runBlocking {
        val expected = CancellationException("chat screen closed")
        var cancellationWasPropagated = false

        try {
            executeNgocSiAiToolSafely(
                executeTool = { _, _ -> throw expected },
                action = "open_youtube",
                query = ""
            )
        } catch (actual: CancellationException) {
            cancellationWasPropagated = true
            assertSame(expected, actual)
        }

        assertTrue("Cancellation must escape the app tool executor.", cancellationWasPropagated)
    }
}

package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceLifecycleRulesTest {
    @Test
    fun keepsServiceWhenPlaybackIsRequestedEvenDuringBuffering() {
        assertFalse(
            PlaybackServiceLifecycleRules.shouldStopWhenTaskRemoved(
                hasPendingPlaybackIntent = true,
                sleepTimerDeadlineMillis = 0L,
                nowMillis = 1_000L
            )
        )
    }

    @Test
    fun keepsServiceUntilSleepTimerExpiresEvenWhenPaused() {
        assertFalse(
            PlaybackServiceLifecycleRules.shouldStopWhenTaskRemoved(
                hasPendingPlaybackIntent = false,
                sleepTimerDeadlineMillis = 60_000L,
                nowMillis = 30_000L
            )
        )
    }

    @Test
    fun stopsServiceWhenThereIsNoPlaybackIntentOrFutureTimer() {
        assertTrue(
            PlaybackServiceLifecycleRules.shouldStopWhenTaskRemoved(
                hasPendingPlaybackIntent = false,
                sleepTimerDeadlineMillis = 0L,
                nowMillis = 30_000L
            )
        )
    }

    @Test
    fun expiredTimerDoesNotKeepIdleServiceAliveForever() {
        assertTrue(
            PlaybackServiceLifecycleRules.shouldStopWhenTaskRemoved(
                hasPendingPlaybackIntent = false,
                sleepTimerDeadlineMillis = 10_000L,
                nowMillis = 10_000L
            )
        )
    }
}

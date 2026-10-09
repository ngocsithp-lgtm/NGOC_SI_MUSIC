package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRecoveryRulesTest {
    @Test
    fun fatalErrorCanForceFallbackWhilePlaybackIsStillRequested() {
        assertTrue(
            RadioRecoveryRules.shouldAttemptFallback(
                force = true,
                isPlaying = false,
                playWhenReady = true,
                isBuffering = false
            )
        )
    }

    @Test
    fun forcedFallbackNeverOverridesAnExplicitPause() {
        assertFalse(
            RadioRecoveryRules.shouldAttemptFallback(
                force = true,
                isPlaying = false,
                playWhenReady = false,
                isBuffering = false
            )
        )
    }

    @Test
    fun watchdogFallsBackOnlyWhenPlayerIsBufferingAndWantsToPlay() {
        assertTrue(
            RadioRecoveryRules.shouldAttemptFallback(
                force = false,
                isPlaying = false,
                playWhenReady = true,
                isBuffering = true
            )
        )
        assertFalse(
            RadioRecoveryRules.shouldAttemptFallback(
                force = false,
                isPlaying = true,
                playWhenReady = true,
                isBuffering = true
            )
        )
        assertFalse(
            RadioRecoveryRules.shouldAttemptFallback(
                force = false,
                isPlaying = false,
                playWhenReady = true,
                isBuffering = false
            )
        )
    }

    @Test
    fun pausedBufferingDoesNotTriggerAutomaticRecovery() {
        assertFalse(
            RadioRecoveryRules.shouldAttemptFallback(
                force = false,
                isPlaying = false,
                playWhenReady = false,
                isBuffering = true
            )
        )
    }

    @Test
    fun watchdogIsArmedWhenActiveRadioEntersBufferingWhilePlaybackIsRequested() {
        assertTrue(
            RadioRecoveryRules.shouldWatchdog(
                isRadioActive = true,
                playWhenReady = true,
                isBuffering = true
            )
        )
    }

    @Test
    fun watchdogIsNotArmedForNonRadioOrPausedPlayback() {
        assertFalse(
            RadioRecoveryRules.shouldWatchdog(
                isRadioActive = false,
                playWhenReady = true,
                isBuffering = true
            )
        )
        assertFalse(
            RadioRecoveryRules.shouldWatchdog(
                isRadioActive = true,
                playWhenReady = false,
                isBuffering = true
            )
        )
    }

    @Test
    fun watchdogIsNotArmedWhenRadioIsNotBuffering() {
        assertFalse(
            RadioRecoveryRules.shouldWatchdog(
                isRadioActive = true,
                playWhenReady = true,
                isBuffering = false
            )
        )
    }

}

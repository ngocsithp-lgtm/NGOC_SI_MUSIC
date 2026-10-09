package com.ngocsi.music

/**
 * Keeps Radio failover aligned with the user's playback intent.
 *
 * A forced recovery may bypass buffering checks after a fatal stream error,
 * but it must never override an explicit pause.
 */
internal object RadioRecoveryRules {
    fun shouldAttemptFallback(
        force: Boolean,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!playWhenReady) return false
        return force || (!isPlaying && isBuffering)
    }
}

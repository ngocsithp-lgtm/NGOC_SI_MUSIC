package com.ngocsi.music

/**
 * Decides whether the MediaSession service still has work after the app task
 * is removed. BUFFERING and an active sleep timer both require the service.
 */
internal object PlaybackServiceLifecycleRules {
    fun shouldStopWhenTaskRemoved(
        hasPendingPlaybackIntent: Boolean,
        sleepTimerDeadlineMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (hasPendingPlaybackIntent) return false
        val hasFutureSleepTimer =
            sleepTimerDeadlineMillis > 0L && sleepTimerDeadlineMillis > nowMillis
        return !hasFutureSleepTimer
    }
}

package com.ngocsi.music

/**
 * Pure sleep-timer rules shared by the UI and playback service.
 * Wall-clock deadlines let the service enforce a timer while the UI is backgrounded.
 */
internal object SleepTimerRules {
    const val MAX_DURATION_MINUTES = 24 * 60
    const val MILLIS_PER_MINUTE = 60_000L

    /** Returns null when the timer is cancelled or disabled. */
    fun deadline(nowMillis: Long, minutes: Int): Long? {
        val normalizedMinutes = minutes.coerceIn(0, MAX_DURATION_MINUTES)
        if (normalizedMinutes == 0) return null
        return nowMillis + normalizedMinutes.toLong() * MILLIS_PER_MINUTE
    }

    /** Counts partial minutes up, so a positive remaining time never displays 0. */
    fun remainingMinutes(deadlineMillis: Long, nowMillis: Long): Int {
        if (deadlineMillis <= nowMillis) return 0
        val remainingMillis = deadlineMillis - nowMillis
        val minutes = ((remainingMillis - 1L) / MILLIS_PER_MINUTE) + 1L
        return minutes.coerceAtMost(MAX_DURATION_MINUTES.toLong()).toInt()
    }

    fun isExpired(deadlineMillis: Long, nowMillis: Long): Boolean =
        deadlineMillis > 0L && nowMillis >= deadlineMillis
    
    /**
     * Avoid a UI race at the deadline: the service may not have cleared the
     * persisted timer yet. A different future deadline means the user replaced
     * the timer, so the old countdown must not report completion.
     */
    fun shouldNotifyExpiration(
        configuredDeadlineMillis: Long,
        nowMillis: Long,
        storedDeadlineMillis: Long
    ): Boolean {
        if (!isExpired(configuredDeadlineMillis, nowMillis)) return false
        return storedDeadlineMillis <= 0L || storedDeadlineMillis == configuredDeadlineMillis
    }

}

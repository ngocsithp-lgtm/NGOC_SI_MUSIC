package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerRulesTest {
    @Test
    fun deadlineReturnsNullWhenDisabled() {
        assertNull(SleepTimerRules.deadline(1_000L, 0))
        assertNull(SleepTimerRules.deadline(1_000L, -15))
    }

    @Test
    fun deadlineClampsDurationToTwentyFourHours() {
        assertEquals(
            1_000L + 24L * 60L * 60_000L,
            SleepTimerRules.deadline(1_000L, 2_000)
        )
    }

    @Test
    fun remainingMinutesRoundsPartialMinuteUp() {
        assertEquals(2, SleepTimerRules.remainingMinutes(120_001L, 60_001L))
        assertEquals(1, SleepTimerRules.remainingMinutes(60_001L, 60_000L))
    }

    @Test
    fun expiredTimerHasNoRemainingMinutes() {
        assertEquals(0, SleepTimerRules.remainingMinutes(10_000L, 10_000L))
        assertEquals(0, SleepTimerRules.remainingMinutes(9_000L, 10_000L))
    }

    @Test
    fun expirationRequiresAConfiguredDeadline() {
        assertFalse(SleepTimerRules.isExpired(0L, 100L))
        assertFalse(SleepTimerRules.isExpired(101L, 100L))
        assertTrue(SleepTimerRules.isExpired(100L, 100L))
    }
}

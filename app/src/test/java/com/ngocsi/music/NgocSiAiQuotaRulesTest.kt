package com.ngocsi.music

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NgocSiAiQuotaRulesTest {
    @Test
    fun identifiesSpecificQuotaErrorsBeforeGeneric429() {
        assertEquals(
            NgocSiAiQuotaCooldownKind.DAILY,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429_DAILY")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.FREE_TIER,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429_FREE_TIER")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.PAID_TIER,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429_PAID_TIER")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.RATE,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429_RATE")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.TEMPORARY,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429")
        )
        assertNull(classifyNgocSiAiQuotaCooldown("NETWORK"))
    }

    @Test
    fun dailyQuotaCooldownEndsAtNextPacificMidnight() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = Instant.parse("2026-10-12T19:30:00Z").toEpochMilli()
        val actual = ngocSiAiQuotaCooldownUntilMillis(
            NgocSiAiQuotaCooldownKind.DAILY,
            now,
            zone
        )
        assertEquals(Instant.parse("2026-10-13T07:00:00Z").toEpochMilli(), actual)
    }

    @Test
    fun shortTermErrorsUseBoundedCooldowns() {
        val now = 1_000_000L
        assertEquals(
            now + 60_000L,
            ngocSiAiQuotaCooldownUntilMillis(NgocSiAiQuotaCooldownKind.RATE, now)
        )
        assertEquals(
            now + 120_000L,
            ngocSiAiQuotaCooldownUntilMillis(NgocSiAiQuotaCooldownKind.TEMPORARY, now)
        )
    }

    @Test
    fun cooldownNoticeMentionsLocalCommandsStillWork() {
        val message = ngocSiAiQuotaCooldownNotice(NgocSiAiQuotaCooldownKind.RATE, 60_000L)
        org.junit.Assert.assertTrue(message.contains("Lệnh nhạc trên thiết bị vẫn hoạt động"))
        org.junit.Assert.assertTrue(message.contains("AI_QUOTA_COOLDOWN"))
    }
}

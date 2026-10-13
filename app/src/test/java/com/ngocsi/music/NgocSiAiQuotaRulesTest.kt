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
    fun classifiesProviderMinuteQuotaAliasesForTheShortCooldown() {
        assertEquals(
            NgocSiAiQuotaCooldownKind.RATE,
            classifyNgocSiAiQuotaErrorDetails(
                "HTTP 429 RESOURCE_EXHAUSTED quotaId=GenerateContentRequestsPerMinute-FreeTier"
            )
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.RATE,
            classifyNgocSiAiQuotaErrorDetails("429 RPM limit reached")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.RATE,
            classifyNgocSiAiQuotaErrorDetails("429 quota_window: 1m")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel(
                "HTTP 429 RESOURCE_EXHAUSTED quotaId=GenerateContentRequestsPerMinute-FreeTier"
            )
        )
    }

    @Test
    fun classifiesDailyFreeTierAndTemporaryQuotaErrors() {
        assertEquals(
            NgocSiAiQuotaCooldownKind.DAILY,
            classifyNgocSiAiQuotaErrorDetails("429 quota exceeded: requests_per_day")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.FREE_TIER,
            classifyNgocSiAiQuotaErrorDetails(
                "429 RESOURCE_EXHAUSTED: generate_content_free_tier"
            )
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.TEMPORARY,
            classifyNgocSiAiQuotaErrorDetails("The model is overloaded; HTTP 429")
        )
        assertNull(classifyNgocSiAiQuotaErrorDetails("Network timeout"))
    }

    @Test
    fun recognizesGenericProjectQuotaErrorsWithoutGuessingTheExactLimit() {
        assertEquals(
            true,
            isAmbiguousNgocSiAiProjectQuotaError(
                "HTTP 429 too_many_requests: You exceeded your current quota, please check your plan and billing details."
            )
        )
        assertEquals(
            false,
            isAmbiguousNgocSiAiProjectQuotaError(
                "HTTP 429 RESOURCE_EXHAUSTED quotaId=GenerateContentRequestsPerMinute-FreeTier"
            )
        )
        assertEquals(
            false,
            isAmbiguousNgocSiAiProjectQuotaError("Network timeout")
        )
    }

    @Test
    fun quotaStatusNoticeSeparatesRemoteQuotaFromLocalPlayback() {
        org.junit.Assert.assertTrue(
            ngocSiAiQuotaStatusNotice("AI_QUOTA_429_PROJECT_OR_CAPACITY")
                ?.contains("lệnh nhạc trên điện thoại vẫn hoạt động") == true
        )
        org.junit.Assert.assertTrue(
            ngocSiAiQuotaStatusNotice("AI_QUOTA_COOLDOWN")
                ?.contains("Chat trực tuyến đang tạm dừng") == true
        )
        assertNull(ngocSiAiQuotaStatusNotice("NETWORK"))
    }

    @Test
    fun cooldownNoticeMentionsLocalCommandsStillWork() {
        val message = ngocSiAiQuotaCooldownNotice(NgocSiAiQuotaCooldownKind.RATE, 60_000L)
        org.junit.Assert.assertTrue(message.contains("Lệnh nhạc trên thiết bị vẫn hoạt động"))
        org.junit.Assert.assertTrue(message.contains("AI_QUOTA_COOLDOWN"))
    }

    @Test
    fun genericProjectQuotaIsDiagnosedBeforeGenericTemporary429() {
        val genericProjectQuota =
            "HTTP 429 too_many_requests: You exceeded your current quota, please check your plan and billing details."
        assertEquals(
            "AI_QUOTA_429_PROJECT_OR_CAPACITY",
            ngocSiAiQuotaDiagnosticCode(genericProjectQuota)
        )
        assertEquals(
            "AI_QUOTA_429_RATE",
            ngocSiAiQuotaDiagnosticCode(
                "HTTP 429 RESOURCE_EXHAUSTED quotaId=GenerateContentRequestsPerMinute-FreeTier"
            )
        )
        assertEquals(
            "AI_QUOTA_429",
            ngocSiAiQuotaDiagnosticCode("The model is overloaded; HTTP 429")
        )
    }

    @Test
    fun triesFallbackOnlyWhenThereIsAReasonableModelSpecificRecoveryPath() {
        org.junit.Assert.assertTrue(
            shouldTryNgocSiAiFallbackModel("HTTP 429 RESOURCE_EXHAUSTED: free tier quota")
        )
        org.junit.Assert.assertTrue(
            shouldTryNgocSiAiFallbackModel("Daily quota has been exceeded for this model")
        )
        org.junit.Assert.assertTrue(
            shouldTryNgocSiAiFallbackModel("The model is overloaded; HTTP 429")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel("429: too many requests per minute")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel("HTTP 401 UNAUTHENTICATED")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel("Network timeout")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel(
                "HTTP 429 too_many_requests: You exceeded your current quota, please check your plan and billing details."
            )
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel("HTTP 429 RESOURCE_EXHAUSTED: project quota exceeded")
        )
        org.junit.Assert.assertFalse(
            shouldTryNgocSiAiFallbackModel("HTTP 429 RESOURCE_EXHAUSTED")
        )
    }
}

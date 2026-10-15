package com.ngocsi.music

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            NgocSiAiQuotaCooldownKind.PROJECT_OR_CAPACITY,
            classifyNgocSiAiQuotaCooldown("AI_QUOTA_429_PROJECT_OR_CAPACITY")
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.PROJECT_FREE_TIER_RESTRICTED,
            classifyNgocSiAiQuotaCooldown("AI_FREE_TIER_PROJECT_RESTRICTED")
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
    fun freeTierProjectRestrictionDoesNotPretendToResetAtMidnight() {
        val now = Instant.parse("2026-10-12T23:59:00Z").toEpochMilli()
        assertEquals(
            now + 24 * 60 * 60 * 1_000L,
            ngocSiAiQuotaCooldownUntilMillis(
                NgocSiAiQuotaCooldownKind.PROJECT_FREE_TIER_RESTRICTED,
                now,
                ZoneId.of("America/Los_Angeles")
            )
        )
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
        assertEquals(
            now + 15 * 60 * 1_000L,
            ngocSiAiQuotaCooldownUntilMillis(NgocSiAiQuotaCooldownKind.PROJECT_OR_CAPACITY, now)
        )
    }

    @Test
    fun identifiesExplicitFreeTierProjectRestrictionSeparatelyFromAppCheckAnd429() {
        val providerMessage =
            "PERMISSION_DENIED: Access to the Gemini Developer API Free Tier for this project " +
                "is restricted by automated account verification checks, and Free Tier access " +
                "cannot be manually enabled or restored for unbilled projects."

        assertEquals(
            NgocSiAiQuotaCooldownKind.PROJECT_FREE_TIER_RESTRICTED,
            classifyNgocSiAiQuotaErrorDetails(providerMessage)
        )
        assertEquals(
            "AI_FREE_TIER_PROJECT_RESTRICTED",
            ngocSiAiQuotaDiagnosticCode(providerMessage)
        )
        assertEquals(
            NgocSiAiQuotaCooldownKind.PROJECT_FREE_TIER_RESTRICTED,
            classifyNgocSiAiQuotaCooldown(
                "Google error. Mã chẩn đoán: AI_FREE_TIER_PROJECT_RESTRICTED"
            )
        )
        assertEquals(
            "Quyền Gemini miễn phí của dự án đang bị nhà cung cấp hạn chế; lệnh trên điện thoại vẫn hoạt động, không bật thanh toán.",
            ngocSiAiQuotaStatusNotice("AI_FREE_TIER_PROJECT_RESTRICTED")
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
    fun identifiesPaidTierRequiredWithout429QuotaWording() {
        val message =
            "PERMISSION_DENIED: This model is not available on free tier; billing must be enabled."

        assertEquals(
            NgocSiAiQuotaCooldownKind.PAID_TIER,
            classifyNgocSiAiQuotaErrorDetails(message)
        )
        assertEquals("AI_PAID_TIER_REQUIRED", ngocSiAiQuotaDiagnosticCode(message))
        assertEquals(
            NgocSiAiQuotaCooldownKind.PAID_TIER,
            classifyNgocSiAiQuotaCooldown("Mã chẩn đoán: AI_PAID_TIER_REQUIRED")
        )
        assertTrue(
            ngocSiAiQuotaStatusNotice("AI_PAID_TIER_REQUIRED")
                ?.contains("lệnh nhạc trên điện thoại vẫn hoạt động") == true
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
    fun preciseProviderQuotaCauseSurvivesWhenLocalFallbackAnswered() {
        val status = ngocSiAiQuotaUiNotice(
            "AI_FREE_TIER_PROJECT_RESTRICTED",
            localFallbackUsed = true
        )

        assertEquals(
            "Quyền Gemini miễn phí của dự án đang bị nhà cung cấp hạn chế; lệnh trên điện thoại vẫn hoạt động, không bật thanh toán.",
            status
        )
    }

    @Test
    fun nonQuotaFailureStillExplainsLocalFallback() {
        assertEquals(
            "Đã trả lời dự phòng trên máy do kết nối AI gặp lỗi; không phát sinh phí.",
            ngocSiAiQuotaUiNotice("NETWORK", localFallbackUsed = true)
        )
        assertEquals("", ngocSiAiQuotaUiNotice("NETWORK", localFallbackUsed = false))
    }

    @Test
    fun ambiguousProjectQuotaGetsBoundedCooldownAndClearReason() {
        val message = ngocSiAiQuotaCooldownNotice(
            NgocSiAiQuotaCooldownKind.PROJECT_OR_CAPACITY,
            15 * 60 * 1_000L
        )
        org.junit.Assert.assertTrue(message.contains("hạn mức chung của dự án"))
        org.junit.Assert.assertTrue(message.contains("15 phút"))
        org.junit.Assert.assertTrue(message.contains("Lệnh nhạc trên thiết bị vẫn hoạt động"))
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
    fun previewNeverRetriesAnotherModelWithoutExplicitBillingConsent() {
        val quotaAndCapacityErrors = listOf(
            "HTTP 429 RESOURCE_EXHAUSTED: free tier quota",
            "Daily quota has been exceeded for this model",
            "HTTP 429 too_many_requests: You exceeded your current quota",
            "The model is overloaded; HTTP 429",
            "429: too many requests per minute",
            "HTTP 429 RESOURCE_EXHAUSTED: project quota exceeded"
        )
        quotaAndCapacityErrors.forEach { details ->
            org.junit.Assert.assertFalse(shouldTryNgocSiAiFallbackModel(details))
        }
        org.junit.Assert.assertFalse(shouldTryNgocSiAiFallbackModel("HTTP 401 UNAUTHENTICATED"))
        org.junit.Assert.assertFalse(shouldTryNgocSiAiFallbackModel("Network timeout"))
    }

}

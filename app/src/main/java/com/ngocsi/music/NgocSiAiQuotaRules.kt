package com.ngocsi.music

import java.time.Instant
import java.time.ZoneId

/** Cool down only after the provider reports a quota-like 429; local music commands bypass this. */
internal enum class NgocSiAiQuotaCooldownKind {
    RATE,
    DAILY,
    FREE_TIER,
    PAID_TIER,
    TEMPORARY
}

internal fun classifyNgocSiAiQuotaCooldown(message: String): NgocSiAiQuotaCooldownKind? {
    val upper = message.uppercase()
    return when {
        "AI_QUOTA_429_DAILY" in upper -> NgocSiAiQuotaCooldownKind.DAILY
        "AI_QUOTA_429_FREE_TIER" in upper -> NgocSiAiQuotaCooldownKind.FREE_TIER
        "AI_QUOTA_429_PAID_TIER" in upper -> NgocSiAiQuotaCooldownKind.PAID_TIER
        "AI_QUOTA_429_RATE" in upper -> NgocSiAiQuotaCooldownKind.RATE
        "AI_QUOTA_429" in upper -> NgocSiAiQuotaCooldownKind.TEMPORARY
        else -> null
    }
}

internal fun ngocSiAiQuotaCooldownUntilMillis(
    kind: NgocSiAiQuotaCooldownKind,
    nowMillis: Long,
    resetZone: ZoneId = ZoneId.of("America/Los_Angeles")
): Long {
    val durationMillis = when (kind) {
        NgocSiAiQuotaCooldownKind.RATE -> 60_000L
        NgocSiAiQuotaCooldownKind.DAILY -> return nextProviderDailyReset(nowMillis, resetZone)
        NgocSiAiQuotaCooldownKind.FREE_TIER -> return nextProviderDailyReset(nowMillis, resetZone)
        NgocSiAiQuotaCooldownKind.PAID_TIER -> 6 * 60 * 60 * 1_000L
        NgocSiAiQuotaCooldownKind.TEMPORARY -> 2 * 60 * 1_000L
    }
    return nowMillis + durationMillis
}

private fun nextProviderDailyReset(nowMillis: Long, zone: ZoneId): Long {
    val nextDay = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
    return nextDay.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Classifies quota provider details in one place so user-facing diagnostics,
 * cooldown lengths, and fallback behavior agree even when Google changes wording.
 */
internal fun classifyNgocSiAiQuotaErrorDetails(message: String): NgocSiAiQuotaCooldownKind? {
    val details = message.lowercase(java.util.Locale.ROOT)
    val normalized = details.replace(Regex("[^a-z0-9]+"), "_")
    val minuteLimit = listOf(
        "perminute", "per_minute", "requests per minute", "requests_per_minute",
        "tokens per minute", "tokens_per_minute", "requestsperminute",
        "tokensperminute", "rpm limit", "quota_window_1m", "window_1m",
        "60 seconds", "60_seconds"
    ).any { it in details || it in normalized }
    val dailyLimit = listOf(
        "perday", "per_day", "requests per day", "requests_per_day",
        "tokens per day", "tokens_per_day", "requestsperday", "tokensperday",
        "daily quota", "daily limit", "quota_window_1d", "window_1d"
    ).any { it in details || it in normalized }
    val paidTierRequired = listOf(
        "paid tier", "billing account required", "billing must be enabled",
        "not available on free tier", "upgrade your plan"
    ).any { it in details }
    val freeTierQuota = listOf(
        "generate_content_free_tier", "free_tier", "free tier quota",
        "free-tier quota", "limit: 0"
    ).any { it in details || it in normalized }
    val quotaOrCapacity = minuteLimit || dailyLimit || listOf(
        "429", "resource_exhausted", "quota", "rate limit",
        "too many requests", "overloaded", "capacity"
    ).any { it in details }

    if (!quotaOrCapacity) return null
    return when {
        minuteLimit -> NgocSiAiQuotaCooldownKind.RATE
        dailyLimit -> NgocSiAiQuotaCooldownKind.DAILY
        paidTierRequired -> NgocSiAiQuotaCooldownKind.PAID_TIER
        freeTierQuota -> NgocSiAiQuotaCooldownKind.FREE_TIER
        else -> NgocSiAiQuotaCooldownKind.TEMPORARY
    }
}

/**
 * Some provider 429s use a generic billing/quota sentence without telling the client
 * whether the limit is per-minute, daily, model-specific, or project entitlement.
 * Treat this as an ambiguous provider-side quota result instead of guessing a cause.
 */
internal fun isAmbiguousNgocSiAiProjectQuotaError(message: String): Boolean {
    val details = message.lowercase(java.util.Locale.ROOT)
    val genericQuotaMessage = listOf(
        "you exceeded your current quota",
        "check your plan and billing details",
        "current quota, please check",
        "quota for your project"
    ).any { it in details }
    val quotaResponse = "429" in details || "resource_exhausted" in details ||
        "too many requests" in details || "too_many_requests" in details
    val specificWindow = classifyNgocSiAiQuotaErrorDetails(details) in setOf(
        NgocSiAiQuotaCooldownKind.RATE,
        NgocSiAiQuotaCooldownKind.DAILY,
        NgocSiAiQuotaCooldownKind.FREE_TIER,
        NgocSiAiQuotaCooldownKind.PAID_TIER
    )
    return genericQuotaMessage && quotaResponse && !specificWindow
}

/**
 * An explicit per-minute throttle should not trigger a second model request;
 * quota/capacity errors for other windows may be recoverable with the fallback.
 */
/**
 * Try the alternate model for quota/capacity failures, but don't send a second
 * request when the provider explicitly reports a per-minute throttle.
 */
internal fun shouldTryNgocSiAiFallbackModel(errorDetails: String): Boolean {
    return when (classifyNgocSiAiQuotaErrorDetails(errorDetails)) {
        NgocSiAiQuotaCooldownKind.RATE,
        NgocSiAiQuotaCooldownKind.PAID_TIER -> false
        NgocSiAiQuotaCooldownKind.DAILY,
        NgocSiAiQuotaCooldownKind.FREE_TIER,
        NgocSiAiQuotaCooldownKind.TEMPORARY -> true
        null -> false
    }
}

internal fun ngocSiAiQuotaCooldownNotice(
    kind: NgocSiAiQuotaCooldownKind,
    remainingMillis: Long
): String {
    val totalMinutes = ((remainingMillis.coerceAtLeast(0L) + 59_999L) / 60_000L)
    val waitText = when {
        totalMinutes >= 60 -> "${totalMinutes / 60} giờ ${totalMinutes % 60} phút"
        else -> "$totalMinutes phút"
    }
    val reason = when (kind) {
        NgocSiAiQuotaCooldownKind.RATE -> "giới hạn yêu cầu trong thời gian ngắn"
        NgocSiAiQuotaCooldownKind.DAILY -> "hạn mức trong ngày"
        NgocSiAiQuotaCooldownKind.FREE_TIER -> "hạn mức miễn phí của Gemini"
        NgocSiAiQuotaCooldownKind.PAID_TIER -> "mô hình/tính năng báo yêu cầu gói thanh toán"
        NgocSiAiQuotaCooldownKind.TEMPORARY -> "lỗi hạn mức hoặc quá tải tạm thời"
    }
    return "NGỌC SĨ AI đang tạm ngưng gửi yêu cầu trực tuyến do $reason. " +
        "Hãy thử lại sau khoảng $waitText. Để tránh lỗi lặp, câu hỏi chưa được gửi lại. " +
        "Lệnh nhạc trên thiết bị vẫn hoạt động. Mã: AI_QUOTA_COOLDOWN."
}

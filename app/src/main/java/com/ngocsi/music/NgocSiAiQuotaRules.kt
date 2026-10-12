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
 * Try the alternate model for quota/capacity failures, but don't send a second
 * request when the provider explicitly reports a per-minute throttle.
 */
internal fun shouldTryNgocSiAiFallbackModel(errorDetails: String): Boolean {
    val details = errorDetails.lowercase()
    val minuteLimit = listOf(
        "perminute", "per_minute", "requests per minute", "tokens per minute",
        "requestsperminute", "tokensperminute", "rpm limit"
    ).any { it in details }
    val quotaOrCapacity = listOf(
        "429", "resource_exhausted", "quota", "too many requests", "overloaded", "capacity"
    ).any { it in details }
    return quotaOrCapacity && !minuteLimit
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

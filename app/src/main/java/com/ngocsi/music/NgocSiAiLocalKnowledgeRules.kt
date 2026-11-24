package com.ngocsi.music

import java.time.LocalDate

/**
 * Small, deterministic facts that are safe to answer without a Gemini request.
 *
 * Keep this deliberately narrow: unknown or changing facts must still go online
 * when available rather than being guessed by the offline fallback.
 */
internal fun formatNgocSiAiTodayAnswer(today: LocalDate): String {
    val weekday = when (today.dayOfWeek.value) {
        1 -> "thứ hai"
        2 -> "thứ ba"
        3 -> "thứ tư"
        4 -> "thứ năm"
        5 -> "thứ sáu"
        6 -> "thứ bảy"
        else -> "Chủ Nhật"
    }
    return "Hôm nay là $weekday, ngày ${today.dayOfMonth} tháng ${today.monthValue} năm ${today.year}."
}

internal fun localNgocSiAiKnowledgeFallback(
    userText: String,
    today: LocalDate = LocalDate.now()
): String? {
    var normalized = normalizeNgocSiAiVoicePhrase(userText)
    val wakePrefixes = listOf(
        "hey ai oi ", "ok ai oi ", "alo ai oi ", "ai oi ",
        "hey ai ", "ok ai ", "alo ai ", "ai ",
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si "
    )
    wakePrefixes.firstOrNull { normalized.startsWith(it) }?.let {
        normalized = normalized.removePrefix(it).trim()
    }

    // The device clock is the source of truth for local date questions. This path
    // is deterministic, offline, and follows the phone's configured time zone.
    val asksToday = normalized.contains("hom nay") &&
        listOf("ngay", "thu", "may", "date").any(normalized::contains)
    if (asksToday) return formatNgocSiAiTodayAnswer(today)

    val mentionsVietnamOrOurCountry =
        normalized.contains("viet nam") ||
            normalized.contains("nuoc ta") ||
            normalized.contains("nuoc minh") ||
            normalized.split(' ').any { it == "vn" }
    val asksWhereOrWhichCity = listOf(
        "la gi",
        "o dau",
        "la thanh pho nao",
        "o thanh pho nao"
    ).any { normalized.endsWith(it) }
    val asksWhetherHanoiIsTheCapital =
        normalized.contains("ha noi") &&
            normalized.contains("phai") &&
            normalized.endsWith("khong")

    val asksVietnamCapital =
        normalized.contains("thu do") &&
            mentionsVietnamOrOurCountry &&
            (asksWhereOrWhichCity ||
                normalized == "thu do viet nam" ||
                normalized == "thu do cua viet nam" ||
                normalized == "thu do nuoc ta" ||
                normalized == "thu do nuoc minh" ||
                asksWhetherHanoiIsTheCapital)

    if (asksVietnamCapital) {
        return if (asksWhetherHanoiIsTheCapital) {
            "Đúng, Hà Nội là thủ đô của Việt Nam."
        } else {
            "Thủ đô của Việt Nam là Hà Nội."
        }
    }

    val asksVietnamCurrency =
        normalized.contains("tien te") && normalized.contains("viet nam") ||
            normalized.contains("don vi tien") && normalized.contains("viet nam") ||
            normalized.contains("viet nam dung tien gi")

    if (asksVietnamCurrency) {
        return "Đơn vị tiền tệ của Việt Nam là đồng Việt Nam, ký hiệu là VND."
    }

    val asksVietnamLanguage =
        normalized.contains("ngon ngu") && normalized.contains("viet nam") ||
            normalized.contains("viet nam noi tieng gi")

    if (asksVietnamLanguage) {
        return "Ngôn ngữ được sử dụng chính thức ở Việt Nam là tiếng Việt."
    }

    val asksVietnamLocation =
        normalized.contains("viet nam") &&
            (
                normalized.contains("nam o dau") ||
                    normalized.contains("thuoc chau luc nao") ||
                    normalized.contains("thuoc khu vuc nao")
                )

    if (asksVietnamLocation) {
        return "Việt Nam nằm ở Đông Nam Á, thuộc khu vực bán đảo Đông Dương."
    }

    return null
}

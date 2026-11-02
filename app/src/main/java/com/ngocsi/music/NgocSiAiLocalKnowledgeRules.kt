package com.ngocsi.music

/**
 * Small, deterministic facts that are safe to answer without a Gemini request.
 *
 * Keep this deliberately narrow: unknown or changing facts must still go online
 * when available rather than being guessed by the offline fallback.
 */
internal fun localNgocSiAiKnowledgeFallback(userText: String): String? {
    var normalized = normalizeNgocSiAiVoicePhrase(userText)
    val wakePrefixes = listOf(
        "hey ai oi ", "ok ai oi ", "alo ai oi ", "ai oi ",
        "hey ai ", "ok ai ", "alo ai ", "ai ",
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si "
    )
    wakePrefixes.firstOrNull { normalized.startsWith(it) }?.let {
        normalized = normalized.removePrefix(it).trim()
    }

    val mentionsVietnamOrOurCountry =
        normalized.contains("viet nam") || normalized.contains("nuoc ta")
    val asksWhereOrWhichCity = listOf(
        "la gi",
        "o dau",
        "la thanh pho nao",
        "o thanh pho nao"
    ).any { normalized.endsWith(it) }

    val asksVietnamCapital =
        normalized.contains("thu do") &&
            mentionsVietnamOrOurCountry &&
            (asksWhereOrWhichCity ||
                normalized == "thu do viet nam" ||
                normalized == "thu do cua viet nam" ||
                normalized == "thu do nuoc ta")

    if (asksVietnamCapital) {
        return "Thủ đô của Việt Nam là Hà Nội."
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

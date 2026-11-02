package com.ngocsi.music

/**
 * Small, deterministic facts that are safe to answer without a Gemini request.
 *
 * Keep this deliberately narrow: unknown or changing facts must still go online
 * when available rather than being guessed by the offline fallback.
 */
internal fun localNgocSiAiKnowledgeFallback(userText: String): String? {
    val normalized = normalizeNgocSiAiVoicePhrase(userText)
        .removePrefix("ai ")
        .trim()

    val asksVietnamCapital =
        normalized.contains("thu do") &&
            normalized.contains("viet nam") &&
            (
                normalized.endsWith("la gi") ||
                    normalized.endsWith("o dau") ||
                    normalized.endsWith("la thanh pho nao") ||
                    normalized == "thu do viet nam" ||
                    normalized == "thu do cua viet nam"
                )

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

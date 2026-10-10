package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

internal enum class NgocSiAiLocalCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    CURRENT_TRACK,
    SHUFFLE_ON,
    SHUFFLE_OFF,
    REPEAT_OFF,
    REPEAT_ALL,
    REPEAT_ONE,
    OPEN_HOME,
    OPEN_LIBRARY,
    SEARCH_YOUTUBE,
    OPEN_YOUTUBE,
    OPEN_DRIVE,
    OPEN_RADIO,
    OPEN_TV,
    OPEN_PLAYLISTS,
    OPEN_QUEUE,
    OPEN_PLAYER,
    OPEN_SETTINGS,
    OPEN_SLEEP_TIMER,
    SLEEP_TIMER_15,
    SLEEP_TIMER_30,
    SLEEP_TIMER_45,
    SLEEP_TIMER_60,
    SLEEP_TIMER_90,
    SLEEP_TIMER_120,
    CANCEL_SLEEP_TIMER,
    HELP
}

/**
 * Recognizes only short, explicit playback commands. A normal question such as
 * "Làm thế nào để phát nhạc?" is intentionally not treated as an action.
 */
/**
 * Accepts a playback command only when the recognized utterance starts with the
 * explicit wake phrase "Ngọc Sĩ". This prevents background speech from triggering actions.
 */
internal fun classifyNgocSiAiWakePhrase(input: String): NgocSiAiLocalCommand? {
    val normalized = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    if (!normalized.startsWith("ngoc si ")) return null
    val commandPhrase = normalized.removePrefix("ngoc si ").trim()
    if (commandPhrase.isBlank()) return null
    return classifyNgocSiAiLocalCommand(commandPhrase)
}

internal fun classifyNgocSiAiLocalCommand(input: String): NgocSiAiLocalCommand? {
    var phrase = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    if (phrase.isBlank()) return null

    val politePrefixes = listOf("ngoc si ", "hay ", "vui long ", "lam on ")
    while (true) {
        val prefix = politePrefixes.firstOrNull { phrase.startsWith(it) } ?: break
        phrase = phrase.removePrefix(prefix).trim()
    }

    fun isAnyOf(vararg options: String) = phrase in options.toSet()
    fun startsWithAny(vararg options: String) = options.any { phrase == it || phrase.startsWith("$it ") }

    return when {
        isAnyOf(
            "phat nhac", "phat nhac di", "phat nhac len",
            "phat bai hat", "phat bai hat di", "nghe bai hat", "nghe bai hat di",
            "nghe nhac di", "mo bai hat", "mo bai hat len", "cho toi nghe nhac",
            "bat nhac", "bat nhac len",
            "tiep tuc phat", "tiep tuc phat nhac", "tiep tuc nghe nhac",
            "bat dau phat nhac", "bat dau nghe nhac", "nghe nhac",
            "play", "play music", "resume", "resume music",
            "mo nhac", "mo nhac di", "mo nhac len"
        ) -> NgocSiAiLocalCommand.PLAY

        isAnyOf(
            "tam dung", "tam dung nhac", "tam dung lai", "tam dung bai hat",
            "dung", "dung lai", "dung lai di", "dung nhac", "dung nhac lai",
            "dung phat", "dung phat di", "dung phat nhac", "dung phat nhac di",
            "ngung phat nhac", "tat nhac", "tat nhac di",
            "pause", "pause music", "stop", "stop music"
        ) -> NgocSiAiLocalCommand.PAUSE

        startsWithAny(
            "tiep theo", "bai tiep theo", "nhac tiep theo", "bai ke tiep", "nhac ke tiep",
            "chuyen bai", "chuyen sang bai tiep theo", "chuyen sang bai ke tiep", "sang bai tiep theo",
            "chuyen qua bai sau", "bai sau", "nhac tiep",
            "qua bai", "bo qua bai nay", "skip", "skip song", "skip to next song", "next", "next song"
        ) -> NgocSiAiLocalCommand.NEXT

        startsWithAny(
            "bai truoc", "nhac truoc", "quay lai bai truoc", "tro ve bai truoc", "bai truoc do",
            "quay ve bai truoc", "lui lai bai truoc",
            "quay lai", "previous", "previous song", "previous track"
        ) -> NgocSiAiLocalCommand.PREVIOUS

        isAnyOf(
            "dang phat bai gi", "bai gi dang phat", "ten bai hat hien tai",
            "bai dang phat la gi", "bai nao dang phat", "ten bai dang phat",
            "bai hat nao dang phat", "what is playing", "current song", "what song is playing"
        ) ->
            NgocSiAiLocalCommand.CURRENT_TRACK

        startsWithAny("bat phat ngau nhien", "bat ngau nhien", "phat ngau nhien",
            "enable shuffle", "turn shuffle on", "shuffle on") -> NgocSiAiLocalCommand.SHUFFLE_ON

        startsWithAny("tat phat ngau nhien", "tat ngau nhien", "tat phat tron",
            "disable shuffle", "turn shuffle off", "shuffle off") -> NgocSiAiLocalCommand.SHUFFLE_OFF

        startsWithAny("tat lap", "tat che do lap", "khong lap", "repeat off", "disable repeat") ->
            NgocSiAiLocalCommand.REPEAT_OFF

        startsWithAny("lap hang doi", "lap danh sach", "lap tat ca", "repeat all", "loop playlist") ->
            NgocSiAiLocalCommand.REPEAT_ALL

        startsWithAny("lap bai", "lap mot bai", "lap mot bai hat", "repeat one", "loop current song") ->
            NgocSiAiLocalCommand.REPEAT_ONE

        // Timer phrases require an explicit duration; vague mentions only open the timer dialog.
        startsWithAny("huy hen gio", "bo hen gio", "tat hen gio", "xoa hen gio", "cancel sleep timer") ->
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER

        phrase.contains("hen gio") && phrase.contains("15 phut") ||
            startsWithAny("tat nhac sau 15 phut", "dung nhac sau 15 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_15
        phrase.contains("hen gio") && phrase.contains("30 phut") ||
            startsWithAny("tat nhac sau 30 phut", "dung nhac sau 30 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_30
        phrase.contains("hen gio") && phrase.contains("45 phut") ||
            startsWithAny("tat nhac sau 45 phut", "dung nhac sau 45 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_45
        phrase.contains("hen gio") && phrase.contains("60 phut") ||
            startsWithAny("tat nhac sau 60 phut", "dung nhac sau 60 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_60
        phrase.contains("hen gio") && phrase.contains("90 phut") ||
            startsWithAny("tat nhac sau 90 phut", "dung nhac sau 90 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_90
        phrase.contains("hen gio") && phrase.contains("120 phut") ||
            startsWithAny("tat nhac sau 120 phut", "dung nhac sau 120 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_120

        isAnyOf("ban lam duoc gi", "toi co the ra lenh gi", "ai lam duoc gi", "tro giup", "huong dan su dung", "cac chuc nang cua ai") ->
            NgocSiAiLocalCommand.HELP

        isAnyOf("ve trang chu", "mo trang chu", "mo home", "home", "trang chu") ->
            NgocSiAiLocalCommand.OPEN_HOME

        startsWithAny("mo thu vien", "vao thu vien", "mo muc thu vien", "mo nhac trong thu vien") ->
            NgocSiAiLocalCommand.OPEN_LIBRARY

        (phrase == "tim youtube" || phrase.startsWith("tim youtube ") ||
            phrase.startsWith("kiem youtube ") ||
            ((phrase.startsWith("tim nhac ") || phrase.startsWith("tim bai hat ") ||
                phrase.startsWith("tim video ")) && phrase.contains("youtube"))) ->
            NgocSiAiLocalCommand.SEARCH_YOUTUBE

        startsWithAny("mo youtube", "vao youtube", "mo video youtube", "mo muc youtube") ->
            NgocSiAiLocalCommand.OPEN_YOUTUBE

        startsWithAny("mo drive", "vao drive", "mo google drive", "vao google drive", "mo muc drive") ->
            NgocSiAiLocalCommand.OPEN_DRIVE

        startsWithAny("mo radio", "vao radio", "mo dai phat thanh", "vao dai phat thanh") ->
            NgocSiAiLocalCommand.OPEN_RADIO

        startsWithAny("mo tv", "vao tv", "mo truyen hinh", "vao truyen hinh", "mo tivi") ->
            NgocSiAiLocalCommand.OPEN_TV

        startsWithAny("mo danh sach phat", "mo playlist", "mo cac playlist", "vao playlist") ->
            NgocSiAiLocalCommand.OPEN_PLAYLISTS

        startsWithAny("mo hang doi", "mo danh sach cho phat", "mo queue", "xem hang doi") ->
            NgocSiAiLocalCommand.OPEN_QUEUE

        startsWithAny("mo trinh phat", "mo man hinh dang phat", "mo player", "xem bai dang phat") ->
            NgocSiAiLocalCommand.OPEN_PLAYER

        startsWithAny("mo cai dat", "vao cai dat", "mo phan cai dat") ->
            NgocSiAiLocalCommand.OPEN_SETTINGS

        startsWithAny("hen gio", "mo hen gio", "dat hen gio", "hen gio tat nhac", "mo bo dem gio") ->
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER

        else -> null
    }
}

/**
 * Speech services can return several hypotheses. Prefer one that maps to a
 * supported explicit command rather than blindly using the first transcript.
 */
internal fun selectNgocSiAiLocalCommandCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiLocalCommand(it) != null }
        ?: cleaned.firstOrNull()
}

/** Wake-word mode must keep its stricter explicit “Ngọc Sĩ” requirement. */
internal fun selectNgocSiAiWakePhraseCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiWakePhrase(it) != null }
        ?: cleaned.firstOrNull()
}


/**
 * Extract a user-supplied YouTube search phrase while preserving Vietnamese spelling.
 * The caller sends it only to the app's existing YouTube search UI after an explicit request.
 */
internal fun extractNgocSiAiYoutubeQuery(input: String): String? {
    var query = input.trim()
    val prefixes = listOf(
        Regex("""^(?:ngọc sĩ[\s,:]+)?(?:hãy\s+)?(?:tìm|kiếm)\s+(?:video|bài hát|nhạc)\s+(?:trên\s+)?youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:ngọc sĩ[\s,:]+)?(?:hãy\s+)?(?:tìm|kiếm)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:ngọc sĩ[\s,:]+)?(?:hãy\s+)?(?:tìm|kiếm)\s+(?:video|bài hát|nhạc)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:ngọc sĩ[\s,:]+)?(?:hãy\s+)?(?:phát|mở)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(query) } ?: return null
    query = prefix.replaceFirst(query, "")
    query = query.replace(
        Regex("""\s+(?:trên\s+)?youtube\s*[?!.,]*$""", RegexOption.IGNORE_CASE),
        ""
    ).trim().trim(',', ':', '-', '.', '?', '!')
    return query.take(120).takeIf { it.length >= 2 }
}

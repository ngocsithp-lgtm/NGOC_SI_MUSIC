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
    OPEN_ONLINE,
    OPEN_SLEEP_TIMER,
    CANCEL_SLEEP_TIMER,
    OPEN_LIBRARY,
    OPEN_YOUTUBE,
    OPEN_DRIVE,
    OPEN_RADIO,
    OPEN_TV,
    OPEN_QUEUE,
    OPEN_PLAYLISTS,
    OPEN_PLAYER,
    OPEN_SETTINGS
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
        ?.takeUnless(::isNgocSiAiNavigationCommand)
}

internal fun isNgocSiAiNavigationCommand(command: NgocSiAiLocalCommand): Boolean =
    command in setOf(
        NgocSiAiLocalCommand.OPEN_HOME,
        NgocSiAiLocalCommand.OPEN_ONLINE,
        NgocSiAiLocalCommand.OPEN_SLEEP_TIMER,
        NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER,
        NgocSiAiLocalCommand.OPEN_LIBRARY,
        NgocSiAiLocalCommand.OPEN_YOUTUBE,
        NgocSiAiLocalCommand.OPEN_DRIVE,
        NgocSiAiLocalCommand.OPEN_RADIO,
        NgocSiAiLocalCommand.OPEN_TV,
        NgocSiAiLocalCommand.OPEN_QUEUE,
        NgocSiAiLocalCommand.OPEN_PLAYLISTS,
        NgocSiAiLocalCommand.OPEN_PLAYER,
        NgocSiAiLocalCommand.OPEN_SETTINGS
    )

internal fun hasNgocSiAiWakePhrase(input: String): Boolean {
    val normalized = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    return normalized == "ngoc si" || normalized.startsWith("ngoc si ")
}

internal fun parseNgocSiAiSleepTimerMinutes(input: String): Int? {
    val normalized = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    val timerRequest = normalized.contains("hen gio") ||
        normalized.contains("tat nhac sau") ||
        normalized.contains("dung nhac sau") ||
        normalized.contains("sleep timer") ||
        normalized.contains("set timer")
    if (!timerRequest) return null

    val digits = Regex("(^|[^0-9])(120|90|60|45|30|15)([^0-9]|$)")
        .find(normalized)?.groupValues?.get(2)?.toIntOrNull()
    if (digits != null) return digits

    val numberWords = listOf(
        "mot tram hai muoi" to 120,
        "chin muoi" to 90,
        "sau muoi" to 60,
        "bon muoi lam" to 45,
        "ba muoi" to 30,
        "muoi lam" to 15
    )
    return numberWords.firstOrNull { normalized.contains(it.first) }?.second
}

private fun stripNgocSiAiPoliteWrappers(input: String): String {
    var phrase = input
    val prefixes = listOf(
        "ngoc si ", "vui long ", "lam on ", "ban hay ", "hay ",
        "toi muon ", "cho toi ", "giup toi ", "lam giup toi "
    )
    val suffixes = listOf(
        " giup toi voi", " giup minh voi", " giup toi", " giup minh",
        " dum toi", " dum minh", " voi", " nhe", " nha", " nhen",
        " vay", " nhi", " di", " len", " lai", " thoi"
    )
    while (phrase.isNotBlank()) {
        val prefix = prefixes.firstOrNull { phrase.startsWith(it) }
        if (prefix != null) {
            phrase = phrase.removePrefix(prefix).trim()
            continue
        }
        val suffix = suffixes.firstOrNull {
            phrase.length > it.length && phrase.endsWith(it)
        }
        if (suffix != null) {
            phrase = phrase.removeSuffix(suffix).trim()
            continue
        }
        break
    }
    return phrase
}

internal fun classifyNgocSiAiLocalCommand(input: String): NgocSiAiLocalCommand? {
    var phrase = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    if (phrase.isBlank()) return null

    phrase = stripNgocSiAiPoliteWrappers(phrase)
    if (phrase.isBlank()) return null

    fun isAnyOf(vararg options: String) = phrase in options.toSet()
    fun startsWithAny(vararg options: String) = options.any { phrase == it || phrase.startsWith("$it ") }

    return when {
        startsWithAny("mo trang chu", "ve trang chu", "quay ve trang chu", "open home", "go home") ->
            NgocSiAiLocalCommand.OPEN_HOME

        startsWithAny("mo online", "vao online", "open online") ->
            NgocSiAiLocalCommand.OPEN_ONLINE

        startsWithAny("tat hen gio", "huy hen gio", "huy hen gio tat nhac", "cancel sleep timer", "turn off sleep timer") ->
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER

        startsWithAny("mo hen gio", "hen gio", "tat nhac sau", "dung nhac sau", "open sleep timer", "set sleep timer") ->
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER

        // Explicit navigation commands are allowlisted; AI text is never executed as code.
        startsWithAny("mo thu vien drive", "mo google drive", "mo drive", "vao drive", "open drive") ->
            NgocSiAiLocalCommand.OPEN_DRIVE

        startsWithAny("mo thu vien", "vao thu vien", "open library") ->
            NgocSiAiLocalCommand.OPEN_LIBRARY

        startsWithAny("mo youtube", "vao youtube", "mo muc youtube", "open youtube") ->
            NgocSiAiLocalCommand.OPEN_YOUTUBE

        startsWithAny("mo radio", "vao radio", "mo muc radio", "open radio") ->
            NgocSiAiLocalCommand.OPEN_RADIO

        startsWithAny("mo tv", "mo tivi", "mo truyen hinh", "vao tv", "open tv") ->
            NgocSiAiLocalCommand.OPEN_TV

        startsWithAny("mo hang doi", "vao hang doi", "open queue") ->
            NgocSiAiLocalCommand.OPEN_QUEUE

        startsWithAny("mo playlist", "mo danh sach phat", "vao playlist", "open playlists") ->
            NgocSiAiLocalCommand.OPEN_PLAYLISTS

        startsWithAny("mo trinh phat", "vao trinh phat", "open player") ->
            NgocSiAiLocalCommand.OPEN_PLAYER

        startsWithAny("mo cai dat", "vao cai dat", "open settings") ->
            NgocSiAiLocalCommand.OPEN_SETTINGS

        isAnyOf(
            "phat nhac", "bat nhac", "tiep tuc phat", "tiep tuc nghe nhac",
            "tiep tuc nghe", "tiep tuc", "bat dau phat nhac", "choi nhac",
            "play", "play music", "resume", "resume playback", "start music", "mo nhac", "mo nhac len", "bat nhac len", "bat dau nghe nhac", "nghe nhac"
        ) -> NgocSiAiLocalCommand.PLAY

        isAnyOf(
            "tam dung", "tam dung nhac", "tam dung phat nhac",
            "pause", "pause music", "pause playback",
            "dung nhac", "dung lai", "dung phat nhac", "ngung nhac", "ngung lai", "ngung phat", "stop", "stop music"
        ) -> NgocSiAiLocalCommand.PAUSE

        startsWithAny("bai tiep theo", "nhac tiep theo", "chuyen bai", "chuyen sang bai tiep theo", "sang bai tiep theo",
            "bai ke tiep", "chuyen sang bai ke tiep", "qua bai tiep", "qua bai", "bo qua bai nay", "phat bai tiep theo",
            "next", "next song", "next track", "skip", "skip song") -> NgocSiAiLocalCommand.NEXT

        startsWithAny("bai truoc", "nhac truoc", "quay lai bai truoc", "tro ve bai truoc",
            "bai truoc do", "tro ve bai vua roi", "quay lai bai vua roi",
            "previous", "previous song", "previous track") -> NgocSiAiLocalCommand.PREVIOUS

        isAnyOf("dang phat bai gi", "bai gi dang phat", "ten bai hat hien tai",
            "bai dang phat la gi", "what is playing", "current song") ->
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

        else -> null
    }
}

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
    REPEAT_ONE
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

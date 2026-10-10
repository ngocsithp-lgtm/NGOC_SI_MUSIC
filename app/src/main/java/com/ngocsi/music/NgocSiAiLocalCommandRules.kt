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

    return when (phrase) {
        "phat nhac", "bat nhac", "tiep tuc phat", "tiep tuc nghe nhac",
        "play", "play music", "resume" -> NgocSiAiLocalCommand.PLAY

        "tam dung", "tam dung nhac", "pause", "pause music" -> NgocSiAiLocalCommand.PAUSE

        "bai tiep theo", "nhac tiep theo", "chuyen bai", "next", "next song" ->
            NgocSiAiLocalCommand.NEXT

        "bai truoc", "nhac truoc", "quay lai bai truoc", "previous", "previous song" ->
            NgocSiAiLocalCommand.PREVIOUS

        "dang phat bai gi", "bai gi dang phat", "ten bai hat hien tai",
        "bai dang phat la gi", "what is playing", "current song" ->
            NgocSiAiLocalCommand.CURRENT_TRACK

        "bat phat ngau nhien", "bat ngau nhien", "phat ngau nhien",
        "enable shuffle", "turn shuffle on", "shuffle on" ->
            NgocSiAiLocalCommand.SHUFFLE_ON

        "tat phat ngau nhien", "tat ngau nhien", "tat phat tron",
        "disable shuffle", "turn shuffle off", "shuffle off" ->
            NgocSiAiLocalCommand.SHUFFLE_OFF

        "tat lap", "tat che do lap", "khong lap",
        "repeat off", "disable repeat" ->
            NgocSiAiLocalCommand.REPEAT_OFF

        "lap hang doi", "lap danh sach", "lap tat ca",
        "repeat all", "loop playlist" ->
            NgocSiAiLocalCommand.REPEAT_ALL

        "lap bai", "lap mot bai", "lap mot bai hat",
        "repeat one", "loop current song" ->
            NgocSiAiLocalCommand.REPEAT_ONE

        else -> null
    }
}

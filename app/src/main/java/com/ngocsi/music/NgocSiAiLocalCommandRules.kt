package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

internal enum class NgocSiAiLocalCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    CURRENT_TRACK
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

        else -> null
    }
}

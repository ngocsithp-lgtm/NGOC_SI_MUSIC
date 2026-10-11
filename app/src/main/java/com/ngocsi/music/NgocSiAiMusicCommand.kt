package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

/**
 * Small allowlist for direct, deterministic music-player actions.
 * Unrecognized text is sent to the conversational model instead.
 */
internal enum class NgocSiAiMusicCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS
}

internal object NgocSiAiMusicCommandParser {
    fun parse(input: String): NgocSiAiMusicCommand? {
        var text = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Permit common polite prefixes, but don't match arbitrary mid-sentence mentions.
        val prefixes = listOf("ban hay ", "vui long ", "lam on ", "giup toi ", "toi muon ", "hay ")
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }

        return when {
            startsWithAny(
                text,
                "bai tiep theo", "nhac tiep theo", "chuyen bai", "qua bai",
                "next", "next song", "next bai"
            ) -> NgocSiAiMusicCommand.NEXT

            startsWithAny(
                text,
                "bai truoc", "nhac truoc", "quay lai bai truoc", "lui bai",
                "previous", "previous song", "bai vua roi"
            ) -> NgocSiAiMusicCommand.PREVIOUS

            startsWithAny(
                text,
                "tam dung nhac", "dung nhac", "dung phat nhac",
                "pause", "pause music", "tam dung"
            ) -> NgocSiAiMusicCommand.PAUSE

            startsWithAny(
                text,
                "tiep tuc phat", "phat tiep", "bat nhac", "phat nhac",
                "cho phat nhac", "play", "play music", "resume"
            ) -> NgocSiAiMusicCommand.PLAY

            else -> null
        }
    }

    private fun startsWithAny(text: String, vararg phrases: String): Boolean =
        phrases.any { text == it || text.startsWith("$it ") }
}

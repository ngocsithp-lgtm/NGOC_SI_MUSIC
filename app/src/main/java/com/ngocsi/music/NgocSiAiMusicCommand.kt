package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

/**
 * Small allowlist for direct, deterministic music-player actions.
 * Unrecognized text is sent to the conversational model instead.
 */
enum class NgocSiAiMusicCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    OPEN_LIBRARY,
    OPEN_YOUTUBE,
    OPEN_RADIO,
    OPEN_QUEUE,
    OPEN_PLAYER,
    OPEN_TV,
    OPEN_DRIVE,
    OPEN_SETTINGS
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
            startsWithAny(text, "mo thu vien", "vao thu vien", "mo danh sach bai hat") ->
                NgocSiAiMusicCommand.OPEN_LIBRARY

            startsWithAny(text, "mo youtube", "vao youtube", "mo tab youtube") ->
                NgocSiAiMusicCommand.OPEN_YOUTUBE

            startsWithAny(text, "mo radio", "vao radio", "mo dai phat thanh", "mo dai radio") ->
                NgocSiAiMusicCommand.OPEN_RADIO

            startsWithAny(text, "mo hang doi", "xem hang doi", "mo danh sach cho", "xem danh sach cho") ->
                NgocSiAiMusicCommand.OPEN_QUEUE

            startsWithAny(text, "mo trinh phat", "mo bai dang phat", "mo man hinh dang phat", "mo player") ->
                NgocSiAiMusicCommand.OPEN_PLAYER

            startsWithAny(text, "mo tv", "vao tv", "mo truyen hinh", "vao truyen hinh") ->
                NgocSiAiMusicCommand.OPEN_TV

            startsWithAny(text, "mo google drive", "vao google drive", "mo drive", "vao drive") ->
                NgocSiAiMusicCommand.OPEN_DRIVE

            startsWithAny(text, "mo cai dat", "vao cai dat", "mo settings") ->
                NgocSiAiMusicCommand.OPEN_SETTINGS

            startsWithAny(
                text,
                "bai tiep theo", "nhac tiep theo", "chuyen bai", "qua bai",
                "phat bai tiep theo", "phat nhac tiep theo", "next", "next song", "next bai"
            ) -> NgocSiAiMusicCommand.NEXT

            startsWithAny(text, "phat bai truoc", "phat nhac truoc") ->
                NgocSiAiMusicCommand.PREVIOUS

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

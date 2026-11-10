package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic question routing for the active playback source.
 *
 * Track titles and artist labels can be answered from app metadata. Identifying
 * the actual audible singer in a YouTube video is different and must use the
 * online multimodal path rather than a channel/title guess.
 */
internal object NgocSiAiPlaybackQuestionRules {
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripWakePrefix(value: String, includeBareAi: Boolean): String {
        var result = value
        val prefixes = buildList {
            add("ngoc si ai ")
            add("ngoc si ")
            add("ai oi ")
            if (includeBareAi) add("ai ")
        }
        var changed: Boolean
        do {
            changed = false
            for (prefix in prefixes) {
                if (result.startsWith(prefix)) {
                    result = result.removePrefix(prefix).trimStart()
                    changed = true
                    break
                }
            }
        } while (changed)
        return result
    }

    internal fun isCurrentTrackQuestion(text: String): Boolean {
        val question = stripWakePrefix(normalize(text), includeBareAi = true)
        if (question.isBlank()) return false

        val hasTrackReference = listOf(
            "bai hat", "bai nhac", "ten bai", "bai nay", "bai gi",
            "video", "ban nhac", "ca khuc"
        ).any(question::contains)
        val asksForIdentity = listOf(
            "la gi", "ten gi", "ten bai", "ten bai hat", "ten bai nhac",
            "bai gi", "bai hat gi", "bai nhac gi", "bai hat nao",
            "bai nhac nao", "dang nghe bai nao", "dang phat bai nao",
            "video gi", "what song", "what is playing", "what's playing"
        ).any(question::contains)
        val refersToCurrentPlayback = listOf(
            "dang nghe", "dang phat", "hien tai", "luc nay",
            "bai nay", "ca khuc nay", "ban nhac nay", "video nay"
        ).any(question::contains)

        return hasTrackReference && asksForIdentity && refersToCurrentPlayback
    }

    internal fun isYoutubeSingerIdentificationQuestion(
        text: String,
        entryContext: String?
    ): Boolean {
        if (normalize(entryContext.orEmpty()) != "youtube") return false

        // Do not remove a bare leading "ai": it is the core of "ai đang hát?".
        val question = stripWakePrefix(normalize(text), includeBareAi = false)
        if (question.isBlank()) return false

        return listOf(
            "ai dang hat",
            "ai hat bai nay",
            "ai dang hat bai nay",
            "nguoi hat bai nay la ai",
            "ai la nguoi hat bai nay",
            "nguoi dang hat bai nay",
            "ai la nguoi dang hat",
            "ca si dang hat",
            "ca si nao dang hat",
            "ca si hat bai nay",
            "ai la ca si cua bai nay",
            "ten nguoi hat bai nay",
            "ten ca si dang hat",
            "giong hat nay cua ai",
            "ai the hien bai nay",
            "ai the hien ca khuc nay",
            "nguoi the hien bai hat la ai",
            "nguoi trinh bay bai nay la ai",
            "ai dang trinh bay bai nay"
        ).any(question::contains)
    }


    internal fun isCurrentGenreQuestion(text: String): Boolean {
        val question = stripWakePrefix(normalize(text), includeBareAi = true)
        if (question.isBlank()) return false
        val asksGenre = listOf(
            "the loai", "thuoc the loai", "dong nhac", "the loai nhac",
            "genre", "style of music"
        ).any(question::contains)
        val refersToCurrentTrack = listOf(
            "bai nay", "bai hat nay", "ca khuc nay", "ban nhac nay",
            "dang nghe", "dang phat", "bai dang nghe", "bai dang phat",
            "video nay", "hien tai"
        ).any(question::contains)
        return asksGenre && refersToCurrentTrack
    }

    internal fun isCurrentArtistMetadataQuestion(text: String): Boolean {
        val question = stripWakePrefix(normalize(text), includeBareAi = false)
        if (question.isBlank()) return false
        val asksWho = listOf(
            "ai hat", "ai dang hat", "ca si la ai", "nghe si la ai",
            "ca si dang hat", "nghe si dang hat", "nguoi dang hat",
            "ai la nguoi hat bai nay", "ten ca si", "ten nghe si", "nguoi hat la ai"
        ).any(question::contains)
        val refersToCurrentTrack = listOf(
            "bai nay", "bai hat nay", "dang nghe", "dang phat",
            "bai dang phat", "bai dang nghe", "hien tai"
        ).any(question::contains)
        return asksWho && refersToCurrentTrack
    }
}

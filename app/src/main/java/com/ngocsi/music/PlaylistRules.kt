package com.ngocsi.music

internal object PlaylistRules {
    fun normalizeName(name: String, maxLength: Int = 80): String =
        name.trim().replace(Regex("\\s+"), " ").take(maxLength)

    fun addSong(songUris: List<String>, uri: String): List<String> {
        val clean = uri.trim()
        if (clean.isBlank()) return songUris.distinct()
        return (songUris + clean).distinct()
    }

    fun removeSong(songUris: List<String>, uri: String): List<String> =
        songUris.filterNot { it == uri }
}

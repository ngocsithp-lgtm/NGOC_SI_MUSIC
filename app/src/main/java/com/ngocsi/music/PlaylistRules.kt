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

    /** Move one saved playlist item immediately before/after another item. */
    fun moveSong(
        songUris: List<String>,
        movingUri: String,
        targetUri: String,
        placeAfterTarget: Boolean
    ): List<String> {
        if (movingUri.isBlank() || targetUri.isBlank() || movingUri == targetUri) return songUris
        val movingIndex = songUris.indexOf(movingUri)
        val targetIndex = songUris.indexOf(targetUri)
        if (movingIndex < 0 || targetIndex < 0) return songUris

        val reordered = songUris.toMutableList()
        val moving = reordered.removeAt(movingIndex)
        val adjustedTargetIndex = if (movingIndex < targetIndex) targetIndex - 1 else targetIndex
        val destination = adjustedTargetIndex + if (placeAfterTarget) 1 else 0
        reordered.add(destination.coerceIn(0, reordered.size), moving)
        return reordered
    }
}

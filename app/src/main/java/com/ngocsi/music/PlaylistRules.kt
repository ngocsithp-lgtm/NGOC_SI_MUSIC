package com.ngocsi.music

internal object PlaylistRules {
    fun normalizeName(name: String, maxLength: Int = 80): String =
        name.trim().replace(Regex("\\s+"), " ").take(maxLength)

    fun addSong(songUris: List<String>, uri: String): List<String> =
        addSongs(songUris, listOf(uri))

    /** Merge a batch of saved song URIs once, preserving the first-seen order. */
    fun addSongs(songUris: List<String>, incomingUris: List<String>): List<String> {
        val unique = LinkedHashSet<String>()
        (songUris + incomingUris).forEach { uri ->
            val clean = uri.trim()
            if (clean.isNotBlank()) unique.add(clean)
        }
        return unique.toList()
    }

    fun removeSong(songUris: List<String>, uri: String): List<String> =
        songUris.filterNot { it == uri }

    fun sanitizeYouTubeVideoId(videoId: String): String {
        val clean = videoId.trim()
        return clean.takeIf {
            it.length in 6..32 && it.all { ch ->
                ch.isLetterOrDigit() || ch == '-' || ch == '_'
            }
        }.orEmpty()
    }

    fun addYouTubeItems(
        current: List<YouTubePlaylistItem>,
        incoming: List<YouTubePlaylistItem>
    ): List<YouTubePlaylistItem> {
        val updated = current.toMutableList()
        incoming.forEach { item ->
            val videoId = sanitizeYouTubeVideoId(item.videoId)
            if (videoId.isBlank() || updated.any { it.videoId == videoId }) return@forEach
            updated.add(
                item.copy(
                    videoId = videoId,
                    title = item.title.trim().take(200).ifBlank { "Video YouTube" },
                    channelTitle = item.channelTitle.trim().take(160).ifBlank { "YouTube" },
                    thumbnailUrl = item.thumbnailUrl.trim().takeIf {
                        it.startsWith("https://", ignoreCase = true)
                    } ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                )
            )
        }
        return updated
    }

    fun removeYouTubeItem(
        items: List<YouTubePlaylistItem>,
        videoId: String
    ): List<YouTubePlaylistItem> {
        val safeId = sanitizeYouTubeVideoId(videoId)
        return if (safeId.isBlank()) items else items.filterNot { it.videoId == safeId }
    }

    fun moveYouTubeItem(
        items: List<YouTubePlaylistItem>,
        movingVideoId: String,
        targetVideoId: String,
        placeAfterTarget: Boolean
    ): List<YouTubePlaylistItem> {
        val reorderedIds = moveSong(
            items.map { it.videoId },
            sanitizeYouTubeVideoId(movingVideoId),
            sanitizeYouTubeVideoId(targetVideoId),
            placeAfterTarget
        )
        if (reorderedIds == items.map { it.videoId }) return items
        val byId = items.associateBy { it.videoId }
        return reorderedIds.mapNotNull(byId::get)
    }

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

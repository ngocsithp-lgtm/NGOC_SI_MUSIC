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

    fun songEntry(uri: String, title: String = "", artist: String = ""): PlaylistEntry? {
        val cleanUri = uri.trim()
        if (cleanUri.isBlank()) return null
        return PlaylistEntry(
            type = PlaylistEntry.TYPE_SONG,
            uri = cleanUri,
            title = title.trim().take(200),
            artist = artist.trim().take(160)
        )
    }

    fun youtubeEntry(item: YouTubePlaylistItem): PlaylistEntry? {
        val safeId = sanitizeYouTubeVideoId(item.videoId)
        if (safeId.isBlank()) return null
        return PlaylistEntry(
            type = PlaylistEntry.TYPE_YOUTUBE,
            videoId = safeId,
            title = item.title.trim().take(200).ifBlank { "Video YouTube" },
            channelTitle = item.channelTitle.trim().take(160).ifBlank { "YouTube" },
            thumbnailUrl = item.thumbnailUrl.trim().takeIf {
                it.startsWith("https://", ignoreCase = true)
            } ?: "https://i.ytimg.com/vi/$safeId/hqdefault.jpg"
        )
    }

    /** Legacy data did not store cross-source order; preserve each source's order deterministically. */
    fun legacyPlaylistEntries(
        songUris: List<String>,
        youtubeItems: List<YouTubePlaylistItem>
    ): List<PlaylistEntry> = addPlaylistEntries(
        songUris.mapNotNull { uri -> songEntry(uri) },
        youtubeItems.mapNotNull(::youtubeEntry)
    )

    /** Merge by a source-qualified key, preserving first-seen order and metadata. */
    fun addPlaylistEntries(
        current: List<PlaylistEntry>,
        incoming: List<PlaylistEntry>
    ): List<PlaylistEntry> {
        val unique = LinkedHashMap<String, PlaylistEntry>()
        (current + incoming).forEach { entry ->
            val clean = when (entry.type) {
                PlaylistEntry.TYPE_SONG -> songEntry(entry.uri, entry.title, entry.artist)
                PlaylistEntry.TYPE_YOUTUBE -> {
                    val safeId = sanitizeYouTubeVideoId(entry.videoId)
                    if (safeId.isBlank()) null else youtubeEntry(
                        YouTubePlaylistItem(safeId, entry.title, entry.channelTitle, entry.thumbnailUrl)
                    )
                }
                else -> null
            } ?: return@forEach
            if (!unique.containsKey(clean.stableKey)) unique[clean.stableKey] = clean
        }
        return unique.values.toList()
    }

    fun removePlaylistEntry(entries: List<PlaylistEntry>, stableKey: String): List<PlaylistEntry> =
        if (stableKey.isBlank()) entries else entries.filterNot { it.stableKey == stableKey }

    /** End-exclusive boundary of a consecutive run of one playlist entry type. */
    fun contiguousPlaylistSegmentEnd(
        entries: List<PlaylistEntry>,
        startIndex: Int,
        type: String
    ): Int {
        if (startIndex !in entries.indices || entries[startIndex].type != type) return startIndex
        var end = startIndex + 1
        while (end < entries.size && entries[end].type == type) end++
        return end
    }

    /** Move an entry before/after any item, including an item from another source. */
    fun movePlaylistEntry(
        entries: List<PlaylistEntry>,
        movingKey: String,
        targetKey: String,
        placeAfterTarget: Boolean
    ): List<PlaylistEntry> {
        if (movingKey.isBlank() || targetKey.isBlank() || movingKey == targetKey) return entries
        val movingIndex = entries.indexOfFirst { it.stableKey == movingKey }
        val targetIndex = entries.indexOfFirst { it.stableKey == targetKey }
        if (movingIndex < 0 || targetIndex < 0) return entries
        val reordered = entries.toMutableList()
        val moving = reordered.removeAt(movingIndex)
        val adjustedTarget = if (movingIndex < targetIndex) targetIndex - 1 else targetIndex
        val destination = adjustedTarget + if (placeAfterTarget) 1 else 0
        reordered.add(destination.coerceIn(0, reordered.size), moving)
        return reordered
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

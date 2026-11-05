package com.ngocsi.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class YouTubePlaylistItem(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String
)

/** One source-aware item in the canonical ordered playlist. A song URI can be local or Drive. */
data class PlaylistEntry(
    val type: String,
    val uri: String = "",
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val channelTitle: String = "",
    val thumbnailUrl: String = ""
) {
    val stableKey: String
        get() = when (type) {
            TYPE_SONG -> "song:$uri"
            TYPE_YOUTUBE -> "youtube:$videoId"
            else -> "unknown:"
        }

    companion object {
        const val TYPE_SONG = "song"
        const val TYPE_YOUTUBE = "youtube"
    }
}

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songUris: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val youtubeItems: List<YouTubePlaylistItem> = emptyList(),
    /** Canonical mixed-source order; legacy lists remain mirrored for compatibility. */
    val entries: List<PlaylistEntry> = emptyList()
)

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("ngoc_si_music_playlists", Context.MODE_PRIVATE)
    private val key = "playlists"

    fun load(): List<MusicPlaylist> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val uris = buildList {
                        val items = obj.optJSONArray("songUris")
                        if (items != null) {
                            for (j in 0 until items.length()) {
                                val uri = items.optString(j).trim()
                                if (uri.isNotBlank()) add(uri)
                            }
                        }
                    }.distinct()
                    val youtubeItems = buildList {
                        val items = obj.optJSONArray("youtubeItems")
                        if (items != null) {
                            for (j in 0 until items.length()) {
                                val item = items.optJSONObject(j) ?: continue
                                val videoId = PlaylistRules.sanitizeYouTubeVideoId(item.optString("videoId"))
                                if (videoId.isBlank()) continue
                                add(
                                    YouTubePlaylistItem(
                                        videoId = videoId,
                                        title = item.optString("title").ifBlank { "Video YouTube" },
                                        channelTitle = item.optString("channelTitle").ifBlank { "YouTube" },
                                        thumbnailUrl = item.optString("thumbnailUrl").ifBlank {
                                            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                        }
                                    )
                                )
                            }
                        }
                    }.distinctBy { it.videoId }

                    val legacyEntries = PlaylistRules.legacyPlaylistEntries(uris, youtubeItems)
                    val savedEntries = obj.optJSONArray("entries")?.let { items ->
                        buildList {
                            for (j in 0 until items.length()) {
                                val item = items.optJSONObject(j) ?: continue
                                add(
                                    PlaylistEntry(
                                        type = item.optString("type"),
                                        uri = item.optString("uri"),
                                        videoId = item.optString("videoId"),
                                        title = item.optString("title"),
                                        artist = item.optString("artist"),
                                        channelTitle = item.optString("channelTitle"),
                                        thumbnailUrl = item.optString("thumbnailUrl")
                                    )
                                )
                            }
                        }
                    }
                    // Legacy playlists cannot reveal interleaving, so migrate source lists in stable order.
                    // If old compatibility arrays contain an item missing from entries, append it instead of losing it.
                    val entries = if (savedEntries == null) {
                        legacyEntries
                    } else {
                        PlaylistRules.addPlaylistEntries(savedEntries, legacyEntries)
                    }
                    add(
                        MusicPlaylist(
                            id = id,
                            name = name,
                            songUris = uris,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                            youtubeItems = youtubeItems,
                            entries = entries
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun create(name: String): MusicPlaylist? {
        val clean = PlaylistRules.normalizeName(name)
        if (clean.isBlank()) return null
        val now = System.currentTimeMillis()
        val playlist = MusicPlaylist(
            id = UUID.randomUUID().toString(),
            name = clean.take(80),
            songUris = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        save(load() + playlist)
        return playlist
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun rename(playlistId: String, name: String): MusicPlaylist? {
        val clean = PlaylistRules.normalizeName(name)
        if (clean.isBlank()) return null
        val updated = load().map { playlist ->
            if (playlist.id == playlistId) playlist.copy(
                name = clean,
                updatedAt = System.currentTimeMillis()
            ) else playlist
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun addSong(playlistId: String, uri: String): MusicPlaylist? = addSongs(playlistId, listOf(uri))

    /** Persist a batch once and append it to the canonical mixed-source order. */
    fun addSongs(playlistId: String, uris: List<String>): MusicPlaylist? {
        val current = load()
        var changed = false
        val updated = current.map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val incoming = uris.mapNotNull(PlaylistRules::songEntry)
                val entries = PlaylistRules.addPlaylistEntries(playlist.entries, incoming)
                if (entries == playlist.entries) playlist
                else {
                    changed = true
                    playlist.withEntries(entries)
                }
            }
        }
        if (changed) save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun addYouTubeItems(playlistId: String, items: List<YouTubePlaylistItem>): MusicPlaylist? {
        val current = load()
        var changed = false
        val updated = current.map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val incoming = items.mapNotNull(PlaylistRules::youtubeEntry)
                val entries = PlaylistRules.addPlaylistEntries(playlist.entries, incoming)
                if (entries == playlist.entries) playlist
                else {
                    changed = true
                    playlist.withEntries(entries)
                }
            }
        }
        if (changed) save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun removeSong(playlistId: String, uri: String): MusicPlaylist? =
        removeEntry(playlistId, PlaylistRules.songEntry(uri)?.stableKey.orEmpty())

    fun removeYouTubeItem(playlistId: String, videoId: String): MusicPlaylist? =
        removeEntry(
            playlistId,
            PlaylistEntry(
                type = PlaylistEntry.TYPE_YOUTUBE,
                videoId = PlaylistRules.sanitizeYouTubeVideoId(videoId)
            ).stableKey
        )

    fun removeEntry(playlistId: String, stableKey: String): MusicPlaylist? {
        val current = load()
        var changed = false
        val updated = current.map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val entries = PlaylistRules.removePlaylistEntry(playlist.entries, stableKey)
                if (entries == playlist.entries) playlist
                else {
                    changed = true
                    playlist.withEntries(entries)
                }
            }
        }
        if (changed) save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun moveEntry(
        playlistId: String,
        movingKey: String,
        targetKey: String,
        placeAfterTarget: Boolean
    ): MusicPlaylist? {
        val current = load()
        var changed = false
        val updated = current.map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val entries = PlaylistRules.movePlaylistEntry(
                    playlist.entries,
                    movingKey,
                    targetKey,
                    placeAfterTarget
                )
                if (entries == playlist.entries) playlist
                else {
                    changed = true
                    playlist.withEntries(entries)
                }
            }
        }
        if (changed) save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun moveYouTubeItem(
        playlistId: String,
        movingVideoId: String,
        targetVideoId: String,
        placeAfterTarget: Boolean
    ): MusicPlaylist? = moveEntry(
        playlistId,
        PlaylistEntry(PlaylistEntry.TYPE_YOUTUBE, videoId = movingVideoId).stableKey,
        PlaylistEntry(PlaylistEntry.TYPE_YOUTUBE, videoId = targetVideoId).stableKey,
        placeAfterTarget
    )

    fun moveSong(
        playlistId: String,
        movingUri: String,
        targetUri: String,
        placeAfterTarget: Boolean
    ): MusicPlaylist? = moveEntry(
        playlistId,
        PlaylistRules.songEntry(movingUri)?.stableKey.orEmpty(),
        PlaylistRules.songEntry(targetUri)?.stableKey.orEmpty(),
        placeAfterTarget
    )

    private fun MusicPlaylist.withEntries(entries: List<PlaylistEntry>): MusicPlaylist {
        val normalized = PlaylistRules.addPlaylistEntries(emptyList(), entries)
        return copy(
            entries = normalized,
            songUris = normalized.filter { it.type == PlaylistEntry.TYPE_SONG }.map { it.uri },
            youtubeItems = normalized.filter { it.type == PlaylistEntry.TYPE_YOUTUBE }
                .mapNotNull { it.toYouTubeItem() },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun save(playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                put("updatedAt", playlist.updatedAt)
                put("songUris", JSONArray().apply { playlist.songUris.forEach(::put) })
                put("youtubeItems", JSONArray().apply {
                    playlist.youtubeItems.forEach { item ->
                        put(JSONObject().apply {
                            put("videoId", item.videoId)
                            put("title", item.title)
                            put("channelTitle", item.channelTitle)
                            put("thumbnailUrl", item.thumbnailUrl)
                        })
                    }
                })
                put("entries", JSONArray().apply {
                    playlist.entries.forEach { entry ->
                        put(JSONObject().apply {
                            put("type", entry.type)
                            put("uri", entry.uri)
                            put("videoId", entry.videoId)
                            put("title", entry.title)
                            put("artist", entry.artist)
                            put("channelTitle", entry.channelTitle)
                            put("thumbnailUrl", entry.thumbnailUrl)
                        })
                    }
                })
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

internal fun PlaylistEntry.toYouTubeItem(): YouTubePlaylistItem? {
    if (type != PlaylistEntry.TYPE_YOUTUBE) return null
    val safeId = PlaylistRules.sanitizeYouTubeVideoId(videoId)
    if (safeId.isBlank()) return null
    return YouTubePlaylistItem(
        videoId = safeId,
        title = title.ifBlank { "Video YouTube" },
        channelTitle = channelTitle.ifBlank { "YouTube" },
        thumbnailUrl = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$safeId/hqdefault.jpg" }
    )
}

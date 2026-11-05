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

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songUris: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val youtubeItems: List<YouTubePlaylistItem> = emptyList()
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
                                val videoId = PlaylistRules.sanitizeYouTubeVideoId(
                                    item.optString("videoId")
                                )
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
                    add(
                        MusicPlaylist(
                            id = id,
                            name = name,
                            songUris = uris,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                            youtubeItems = youtubeItems
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
            if (playlist.id != playlistId) playlist
            else playlist.copy(
                name = clean,
                updatedAt = System.currentTimeMillis()
            )
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun addSong(playlistId: String, uri: String): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else playlist.copy(
                songUris = PlaylistRules.addSong(playlist.songUris, uri),
                updatedAt = System.currentTimeMillis()
            )
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun removeSong(playlistId: String, uri: String): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else playlist.copy(
                songUris = PlaylistRules.removeSong(playlist.songUris, uri),
                updatedAt = System.currentTimeMillis()
            )
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun addYouTubeItems(
        playlistId: String,
        items: List<YouTubePlaylistItem>
    ): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val merged = PlaylistRules.addYouTubeItems(playlist.youtubeItems, items)
                if (merged == playlist.youtubeItems) playlist
                else playlist.copy(
                    youtubeItems = merged,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun removeYouTubeItem(playlistId: String, videoId: String): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val remaining = PlaylistRules.removeYouTubeItem(playlist.youtubeItems, videoId)
                if (remaining == playlist.youtubeItems) playlist
                else playlist.copy(
                    youtubeItems = remaining,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun moveYouTubeItem(
        playlistId: String,
        movingVideoId: String,
        targetVideoId: String,
        placeAfterTarget: Boolean
    ): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val reordered = PlaylistRules.moveYouTubeItem(
                    playlist.youtubeItems,
                    movingVideoId,
                    targetVideoId,
                    placeAfterTarget
                )
                if (reordered == playlist.youtubeItems) playlist
                else playlist.copy(
                    youtubeItems = reordered,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    fun moveSong(
        playlistId: String,
        movingUri: String,
        targetUri: String,
        placeAfterTarget: Boolean
    ): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else {
                val reordered = PlaylistRules.moveSong(
                    playlist.songUris,
                    movingUri,
                    targetUri,
                    placeAfterTarget
                )
                if (reordered == playlist.songUris) playlist
                else playlist.copy(
                    songUris = reordered,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        save(updated)
        return updated.firstOrNull { it.id == playlistId }
    }

    private fun save(playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                put("updatedAt", playlist.updatedAt)
                put("songUris", JSONArray().apply {
                    playlist.songUris.forEach(::put)
                })
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
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

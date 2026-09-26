package com.ngocsi.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songUris: List<String>,
    val createdAt: Long,
    val updatedAt: Long
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
                    add(
                        MusicPlaylist(
                            id = id,
                            name = name,
                            songUris = uris,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun create(name: String): MusicPlaylist? {
        val clean = name.trim().replace(Regex("\\s+"), " ")
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

    fun addSong(playlistId: String, uri: String): MusicPlaylist? {
        val updated = load().map { playlist ->
            if (playlist.id != playlistId) playlist
            else playlist.copy(
                songUris = (playlist.songUris + uri).distinct(),
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
                songUris = playlist.songUris.filterNot { it == uri },
                updatedAt = System.currentTimeMillis()
            )
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
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

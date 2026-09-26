package com.ngocsi.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures

class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val widgetHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ngoc_si_music", MODE_PRIVATE) }
    private val widgetTicker = object : Runnable {
        override fun run() {
            savePlaybackState()
            broadcastWidget()
            if (player.isPlaying) widgetHandler.postDelayed(this, 2000L)
        }
    }


    @OptIn(UnstableApi::class)
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val uri = prefs.getString("last_song_uri", null)
            val position = prefs.getLong("last_position", 0L).coerceAtLeast(0L)
            val queueUris = prefs.getString("queue_order", null)
                ?.split("\n")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            val orderedUris = if (queueUris.isNotEmpty()) queueUris else listOfNotNull(uri)
            if (orderedUris.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                )
            }

            // During boot/resumption, the system only needs the current item
            // metadata when playback is not being started immediately.
            // Returning the full queue here is unnecessary and can increase
            // startup work, so keep the non-playback path lightweight.
            val resumptionUris = if (isForPlayback) orderedUris
            else listOf(orderedUris.getOrElse(
                uri?.let { orderedUris.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            ) { orderedUris.first() })

            val metadataByUri = mutableMapOf<String, org.json.JSONObject>()
            prefs.getString("queue_metadata", null)?.let { raw ->
                runCatching {
                    val array = org.json.JSONArray(raw)
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val itemUri = item.optString("uri")
                        if (itemUri.isNotBlank()) metadataByUri[itemUri] = item
                    }
                }
            }

            val items = resumptionUris.map { itemUri ->
                val saved = metadataByUri[itemUri]
                val title = saved?.optString("title").orEmpty()
                    .ifBlank { itemUri.substringAfterLast('/').ifBlank { "NGỌC SĨ MUSIC" } }
                val artist = saved?.optString("artist").orEmpty()
                val artwork = saved?.optString("artworkUri").orEmpty()
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (artist.isNotBlank()) setArtist(artist) }
                    .apply { if (artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(artwork)) }
                MediaItem.Builder()
                    .setMediaId(itemUri)
                    .setUri(itemUri)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            }

            val resumeIndex = if (isForPlayback) {
                uri?.let { orderedUris.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            } else 0
            if (isForPlayback) {
                player.setShuffleModeEnabled(prefs.getBoolean("shuffle", false))
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
                player.setPlaybackSpeed(
                    prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
                )
            }

            val startPosition = if (isForPlayback) position else androidx.media3.common.C.TIME_UNSET
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, resumeIndex, startPosition)
            )        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            savePlaybackState()
            broadcastWidget()
            widgetHandler.removeCallbacks(widgetTicker)
            if (isPlaying) widgetHandler.postDelayed(widgetTicker, 2000L)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            savePlaybackState()
            broadcastWidget()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            savePlaybackState()
            broadcastWidget()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            // Refresh immediately when title/artist/artwork metadata changes.
            broadcastWidget()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Persist immediately after seek/previous/next, including when paused.
            // This avoids losing a manually selected position before the next ticker.
            savePlaybackState()
            broadcastWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )

        // Keep playback alive when the UI Activity is closed, while still
        // allowing Android to pause safely when audio output becomes noisy.
        player.setHandleAudioBecomingNoisy(true)

        player.addListener(playerListener)
        broadcastWidget()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(mediaSessionCallback)
            .build()

        broadcastWidget()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    private fun savePlaybackState() {
        val mediaItem = player.currentMediaItem
        val uri = mediaItem?.localConfiguration?.uri?.toString()

        // Keep the service-side resumption snapshot authoritative as well.
        // This matters when playback is controlled from the lock screen, headset,
        // car controls, or after the Activity has already left the foreground.
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString("last_song_uri", uri)
                .putLong("last_position", player.currentPosition.coerceAtLeast(0L))
        }

        if (player.mediaItemCount > 0) {
            val queueUris = buildString {
                for (index in 0 until player.mediaItemCount) {
                    if (index > 0) append('\n')
                    append(player.getMediaItemAt(index).mediaId)
                }
            }
            val metadata = org.json.JSONArray()
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val itemUri = item.localConfiguration?.uri?.toString().orEmpty()
                val md = item.mediaMetadata
                metadata.put(
                    org.json.JSONObject().apply {
                        put("uri", itemUri)
                        put("title", md.title?.toString().orEmpty())
                        put("artist", md.artist?.toString().orEmpty())
                        put("artworkUri", md.artworkUri?.toString().orEmpty())
                    }
                )
            }
            editor.putString("queue_order", queueUris)
                .putString("queue_metadata", metadata.toString())
        } else {
            editor.remove("queue_order")
                .remove("queue_metadata")
        }

        editor.putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .putFloat("playback_speed", player.playbackParameters.speed)
            .apply()
    }

    private fun broadcastWidget() {
        sendBroadcast(
            Intent(this, MusicWidgetProvider::class.java).setAction(
                MusicWidgetProvider.ACTION_REFRESH
            )
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Persist the latest item and position before the task is removed.
        savePlaybackState()
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Persist once more before releasing Media3 resources.
        savePlaybackState()
        widgetHandler.removeCallbacks(widgetTicker)
        player.removeListener(playerListener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}

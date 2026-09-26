package com.ngocsi.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures

@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val widgetHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ngoc_si_music", MODE_PRIVATE) }
    private val sessionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val queueUris = prefs.getString("queue_order", "").orEmpty()
                .split("\\n")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            val lastUri = prefs.getString("last_song_uri", null)
            val items = queueUris.mapNotNull { value ->
                runCatching {
                    val uri = android.net.Uri.parse(value)
                    MediaItem.Builder()
                        .setMediaId(value)
                        .setUri(uri)
                        .build()
                }.getOrNull()
            }
            if (items.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                )
            }
            val startIndex = items.indexOfFirst { it.mediaId == lastUri }
                .takeIf { it >= 0 } ?: 0
            val startPosition = prefs.getLong("last_position", 0L).coerceAtLeast(0L)
            val speed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
            player.setPlaybackSpeed(speed)
            player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
            val resumeItems = if (isForPlayback) items else listOf(items[startIndex])
            val resumeIndex = if (isForPlayback) startIndex else 0
            val resumePosition = if (isForPlayback) startPosition else C.TIME_UNSET
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resumeItems, resumeIndex, resumePosition)
            )
        }
    }

    private val widgetTicker = object : Runnable {
        override fun run() {
            savePlaybackState()
            broadcastWidget()
            if (player.isPlaying) widgetHandler.postDelayed(this, 2000L)
        }
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
            .setCallback(sessionCallback)
            .build()

        broadcastWidget()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    private fun savePlaybackState() {
        val mediaItem = player.currentMediaItem
        val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        prefs.edit()
            .putString("last_song_uri", uri)
            .putLong("last_position", position)
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
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        widgetHandler.removeCallbacks(widgetTicker)
        player.removeListener(playerListener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}

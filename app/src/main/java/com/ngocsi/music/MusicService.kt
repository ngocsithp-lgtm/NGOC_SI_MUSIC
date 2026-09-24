package com.ngocsi.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val widgetHandler = Handler(Looper.getMainLooper())
    private val widgetTicker = object : Runnable {
        override fun run() {
            broadcastWidget()
            if (player.isPlaying) widgetHandler.postDelayed(this, 2000L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            broadcastWidget()
            widgetHandler.removeCallbacks(widgetTicker)
            if (isPlaying) widgetHandler.postDelayed(widgetTicker, 2000L)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            broadcastWidget()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            broadcastWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )

        player.setHandleAudioBecomingNoisy(true)

        // Đồng bộ thao tác tua 10 giây của app với Player/MediaSession,
        // để các bề mặt điều khiển bên ngoài có cùng bước tua.
        player.setSeekBackIncrementMs(10_000L)
        player.setSeekForwardIncrementMs(10_000L)

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
            .build()

        broadcastWidget()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
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

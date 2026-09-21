package com.ngocsithp.ngocmusic

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "com.ngocsithp.ngocmusic.PLAY"
        const val ACTION_PAUSE = "com.ngocsithp.ngocmusic.PAUSE"
        const val ACTION_STOP = "com.ngocsithp.ngocmusic.STOP"

        private const val CHANNEL_ID = "ngoc_music_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_PLAY -> {
                playMusic()
            }

            ACTION_PAUSE -> {
                pauseMusic()
            }

            ACTION_STOP -> {
                stopMusic()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun playMusic() {

        if (mediaPlayer == null) {

            mediaPlayer = MediaPlayer().apply {

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .build()
                )

                setOnCompletionListener {
                    stopSelf()
                }

                // Tạm thời dùng file nhạc trong res/raw
                val musicUri = android.net.Uri.parse(
                    "android.resource://$packageName/raw/sample_music"
                )

                setDataSource(
                    this@MusicService,
                    musicUri
                )

                prepare()
            }
        }

        mediaPlayer?.start()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )
    }

    private fun pauseMusic() {

        mediaPlayer?.pause()

        updateNotification()
    }

    private fun stopMusic() {

        mediaPlayer?.stop()
        mediaPlayer?.release()

        mediaPlayer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotification(): Notification {

        val playIntent = Intent(this, MusicService::class.java)
            .setAction(ACTION_PLAY)

        val pauseIntent = Intent(this, MusicService::class.java)
            .setAction(ACTION_PAUSE)

        val stopIntent = Intent(this, MusicService::class.java)
            .setAction(ACTION_STOP)

        val playPendingIntent =
            PendingIntent.getService(
                this,
                1,
                playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val pausePendingIntent =
            PendingIntent.getService(
                this,
                2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                3,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("NGOC MUSIC")
            .setContentText("Đang phát nhạc")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Tạm dừng",
                pausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_play,
                "Phát",
                playPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dừng",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {

        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification()
        )
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "NGOC MUSIC",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Điều khiển phát nhạc NGOC MUSIC"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {

        mediaPlayer?.release()
        mediaPlayer = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

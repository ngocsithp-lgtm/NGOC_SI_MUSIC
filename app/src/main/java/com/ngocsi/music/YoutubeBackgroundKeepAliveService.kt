package com.ngocsi.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Preview-only experiment to keep the app process alive while an in-app YouTube
 * IFrame is playing. This service cannot guarantee that YouTube/WebView allows
 * background playback and deliberately does not extract or proxy media streams.
 */
class YoutubeBackgroundKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoId = intent?.getStringExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID).orEmpty()
        val title = intent?.getStringExtra(YouTubePlayerActivity.EXTRA_TITLE).orEmpty()
            .ifBlank { "YouTube" }
        val channel = intent?.getStringExtra(YouTubePlayerActivity.EXTRA_CHANNEL).orEmpty()
            .ifBlank { "YouTube" }

        val openPlayer = Intent(this, YouTubePlayerActivity::class.java).apply {
            putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
            putExtra(YouTubePlayerActivity.EXTRA_TITLE, title)
            putExtra(YouTubePlayerActivity.EXTRA_CHANNEL, channel)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            721,
            openPlayer,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NGỌC SĨ MUSIC · thử nghiệm phát nền")
            .setContentText(title)
            .setSubText(channel)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NgocSiMusic:YoutubeBackgroundPreview"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "YouTube phát nền thử nghiệm",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo của bản Preview khi thử nghiệm phát nền YouTube"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "youtube_background_preview"
        private const val NOTIFICATION_ID = 721
    }
}

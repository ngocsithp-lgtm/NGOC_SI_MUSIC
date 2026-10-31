package com.ngocsi.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Keeps the currently user-started in-app YouTube WebView playback process at
 * media-playback priority while the screen is off or another app is foregrounded.
 *
 * The HTML5 video remains the audio renderer; this service does not download,
 * extract, or replace YouTube media. Its media session routes controls to the
 * active inline player host and its notification opens MainActivity's embedded
 * YouTube card, never the separate player page or the local/Drive player.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class YouTubeBackgroundService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.ngocsi.music.youtube.background.UPDATE"
        const val ACTION_STOP = "com.ngocsi.music.youtube.background.STOP"
        const val ACTION_PLAY = "com.ngocsi.music.youtube.background.PLAY"
        const val ACTION_PAUSE = "com.ngocsi.music.youtube.background.PAUSE"
        const val ACTION_NEXT = "com.ngocsi.music.youtube.background.NEXT"
        const val ACTION_PREVIOUS = "com.ngocsi.music.youtube.background.PREVIOUS"
        const val ACTION_CONTROL = "com.ngocsi.music.youtube.background.CONTROL"
        const val ACTION_SLEEP_TIMER_EXPIRED = "com.ngocsi.music.youtube.background.SLEEP_TIMER_EXPIRED"
        const val ACTION_OPEN_INLINE_PLAYER = "com.ngocsi.music.youtube.background.OPEN_INLINE_PLAYER"
        const val EXTRA_OPEN_INLINE_PLAYER = "youtube_open_inline_player"
        const val EXTRA_CONTROL = "youtube_background_control"
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_title"
        const val EXTRA_CHANNEL = "youtube_channel"
        const val EXTRA_THUMBNAIL_URL = "youtube_thumbnail_url"
        const val EXTRA_QUEUE_JSON = "youtube_queue_json"
        const val EXTRA_QUEUE_INDEX = "youtube_queue_index"
        const val EXTRA_PLAYER_STATE = "youtube_player_state"
        const val EXTRA_IS_PLAYING = "youtube_is_playing"

        private const val CHANNEL_ID = "ngocsi_youtube_background"
        private const val NOTIFICATION_ID = 5381
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var videoId = ""
    private var title = "YouTube"
    private var channel = "NGỌC SĨ MUSIC"
    private var thumbnailUrl = ""
    private var queueJson = "[]"
    private var queueIndex = 0
    private var playerState = PlaybackState.STATE_NONE
    private var isPlaying = false
    private var sleepTimerReceiverRegistered = false
    private val prefs by lazy { getSharedPreferences("ngoc_si_music", MODE_PRIVATE) }
    private val sleepTimerHandler = Handler(Looper.getMainLooper())

    // The inline YouTube renderer has its own foreground service. Keep a timer
    // watchdog here so background playback does not depend on MusicService being
    // alive or bound to MainActivity when the deadline is reached.
    private val sleepTimerWatchdog = object : Runnable {
        override fun run() {
            val deadline = prefs.getLong("sleep_timer_end_at", 0L)
            val youtubeSelected = prefs.getBoolean("youtube_active_source", false)
            if (youtubeSelected && SleepTimerRules.isExpired(deadline, System.currentTimeMillis())) {
                // Clear only after confirming YouTube is the selected source.
                // MusicService remains responsible for local/Drive playback timers.
                prefs.edit()
                    .remove("sleep_timer_end_at")
                    .putBoolean("youtube_playback_is_playing", false)
                    .apply()
                isPlaying = false
                playerState = PlaybackState.STATE_PAUSED
                dispatchControl("pause")
                releaseWakeLock()
                if (videoId.isNotBlank()) refreshSessionAndNotification()
            }

            val activeDeadline = prefs.getLong("sleep_timer_end_at", 0L)
            sleepTimerHandler.postDelayed(this, if (activeDeadline > 0L) 1_000L else 5_000L)
        }
    }

    private val sleepTimerExpirationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != ACTION_SLEEP_TIMER_EXPIRED) return
            isPlaying = false
            playerState = PlaybackState.STATE_PAUSED
            releaseWakeLock()
            // Keep lock-screen/notification controls in sync with the timer.
            if (videoId.isNotBlank()) refreshSessionAndNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            sleepTimerExpirationReceiver,
            IntentFilter(ACTION_SLEEP_TIMER_EXPIRED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        sleepTimerReceiverRegistered = true
        createNotificationChannel()
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NgocSiMusic:YouTubeBackgroundPlayback"
        )?.apply {
            setReferenceCounted(false)
        }
        createMediaSession()
        sleepTimerHandler.post(sleepTimerWatchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A user tapping Close in the media notification must not leave
                // the hidden WebView playing without its foreground service.
                dispatchControl("pause")
                stopPlaybackService()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                isPlaying = true
                dispatchControl("play")
                refreshSessionAndNotification()
            }
            ACTION_PAUSE -> {
                isPlaying = false
                dispatchControl("pause")
                refreshSessionAndNotification()
            }
            ACTION_NEXT -> dispatchControl("next")
            ACTION_PREVIOUS -> dispatchControl("previous")
            ACTION_UPDATE, null -> updateFromIntent(intent)
        }
        return START_STICKY
    }

    private fun updateFromIntent(intent: Intent?) {
        if (intent != null) {
            videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.take(32)?.filter {
                it.isLetterOrDigit() || it == '-' || it == '_'
            }.orEmpty().ifBlank { videoId }
            title = intent.getStringExtra(EXTRA_TITLE)?.take(300).orEmpty().ifBlank { title }
            channel = intent.getStringExtra(EXTRA_CHANNEL)?.take(200).orEmpty().ifBlank { channel }
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL).orEmpty().ifBlank {
                if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else thumbnailUrl
            }
            queueJson = intent.getStringExtra(EXTRA_QUEUE_JSON)?.takeIf { it.isNotBlank() } ?: queueJson
            queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, queueIndex).coerceAtLeast(0)
            playerState = intent.getIntExtra(EXTRA_PLAYER_STATE, playerState)
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, playerState == 1)
        }
        if (videoId.isBlank()) {
            stopPlaybackService()
            return
        }

        refreshSessionAndNotification()
        if (isPlaying) acquireWakeLock() else releaseWakeLock()
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "NgocSiYouTubeSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    isPlaying = true
                    dispatchControl("play")
                    refreshSessionAndNotification()
                }

                override fun onPause() {
                    isPlaying = false
                    dispatchControl("pause")
                    refreshSessionAndNotification()
                }

                override fun onSkipToNext() {
                    dispatchControl("next")
                }

                override fun onSkipToPrevious() {
                    dispatchControl("previous")
                }

                override fun onStop() {
                    isPlaying = false
                    dispatchControl("pause")
                    refreshSessionAndNotification()
                }
            })
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
    }

    private fun refreshSessionAndNotification() {
        val session = mediaSession
        if (session != null) {
            val metadata = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, channel)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "YouTube • NGỌC SĨ MUSIC")
                .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, videoId)
                .build()
            session.setMetadata(metadata)

            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
            val nativeState = when {
                isPlaying -> PlaybackState.STATE_PLAYING
                playerState == 3 -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_PAUSED
            }
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(nativeState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isPlaying) 1f else 0f)
                    .build()
            )
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val sessionToken = mediaSession?.sessionToken
        // A notification tap must return to the embedded inline player on MainActivity.
        // The older target opened YouTubePlayerActivity, which looked like a separate
        // YouTube page and lost the user's inline-card context.
        val openPlayer = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_INLINE_PLAYER
            putExtra(EXTRA_OPEN_INLINE_PLAYER, true)
            putExtra(EXTRA_VIDEO_ID, videoId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CHANNEL, channel)
            putExtra(EXTRA_THUMBNAIL_URL, thumbnailUrl)
            putExtra(EXTRA_QUEUE_JSON, queueJson)
            putExtra(EXTRA_QUEUE_INDEX, queueIndex)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            5381,
            openPlayer,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previous = servicePendingIntent(ACTION_PREVIOUS, 5382)
        val transport = servicePendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 5383)
        val next = servicePendingIntent(ACTION_NEXT, 5384)
        val stop = servicePendingIntent(ACTION_STOP, 5385)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "YouTube" })
            .setContentText(if (isPlaying) "Đang phát • $channel" else "Đã tạm dừng • $channel")
            .setSubText("NGỌC SĨ MUSIC • YouTube")
            .setContentIntent(contentPendingIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_previous, "Bài trước", previous)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Tạm dừng" else "Phát",
                transport
            )
            .addAction(android.R.drawable.ic_media_next, "Bài tiếp", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Đóng", stop)

        if (sessionToken != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, YouTubeBackgroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun dispatchControl(control: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL, control)
        )
        if (control == "play") {
            isPlaying = true
            acquireWakeLock()
        } else if (control == "pause") {
            isPlaying = false
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            wakeLock?.let { lock ->
                if (!lock.isHeld) lock.acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "YouTube phát nền",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Điều khiển YouTube khi khóa màn hình hoặc chuyển ứng dụng"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun stopPlaybackService() {
        releaseWakeLock()
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sleepTimerHandler.removeCallbacks(sleepTimerWatchdog)
        releaseWakeLock()
        if (sleepTimerReceiverRegistered) {
            runCatching { unregisterReceiver(sleepTimerExpirationReceiver) }
            sleepTimerReceiverRegistered = false
        }
        runCatching {
            mediaSession?.apply {
                isActive = false
                release()
            }
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

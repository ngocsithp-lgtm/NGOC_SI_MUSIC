package com.ngocsi.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Experimental on-device wake-phrase listener. It intentionally refuses online recognition.
 * Start it only from a visible activity after RECORD_AUDIO permission has been granted.
 */
class NgocSiWakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var musicController: MediaController? = null
    private var listening = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningService()
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY

        startForegroundCompat(buildNotification("Chuẩn bị nhận lệnh offline…"))
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, true).apply()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            failAndStop("Máy chưa hỗ trợ nhận dạng giọng nói offline trên thiết bị này.")
            return START_NOT_STICKY
        }
        if (speechRecognizer != null) return START_NOT_STICKY

        connectMusicController()
        try {
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
            beginListening()
        } catch (_: Exception) {
            failAndStop("Không thể khởi động nhận dạng tiếng Việt offline.")
        }
        return START_NOT_STICKY
    }

    private fun connectMusicController() {
        if (musicController != null) return
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { controller ->
                    if (stopping || speechRecognizer == null) controller.release()
                    else musicController = controller
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            updateNotification("Đang nghe từ khóa “Ngọc Sĩ” · chỉ nhận dạng trên thiết bị")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            if (stopping) return
            if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            ) {
                failAndStop("Gói nhận dạng tiếng Việt offline chưa có trên máy. Không dùng mạng thay thế.")
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                failAndStop("Thiếu quyền micro. Hãy cấp quyền micro rồi bật lại.")
                return
            }
            scheduleListenRetry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_200L else 500L)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val command = phrases.firstNotNullOfOrNull(::classifyNgocSiAiWakePhrase)
            if (command != null) {
                val reply = runPlaybackCommand(command)
                updateNotification("Đã nhận lệnh · $reply")
            }
            scheduleListenRetry(450L)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun beginListening() {
        if (stopping || speechRecognizer == null || listening) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: SecurityException) {
            failAndStop("Không có quyền micro. Hãy cấp quyền micro rồi bật lại.")
        } catch (_: Exception) {
            scheduleListenRetry(1_000L)
        }
    }

    private fun scheduleListenRetry(delayMs: Long) {
        if (stopping) return
        mainHandler.removeCallbacks(restartListening)
        mainHandler.postDelayed(restartListening, delayMs)
    }

    private val restartListening = Runnable { beginListening() }

    private fun runPlaybackCommand(command: NgocSiAiLocalCommand): String {
        val player = musicController ?: return "Trình phát chưa kết nối"
        return runCatching {
            when (command) {
                NgocSiAiLocalCommand.PLAY -> {
                    player.play()
                    "Phát nhạc"
                }
                NgocSiAiLocalCommand.PAUSE -> {
                    player.pause()
                    "Tạm dừng"
                }
                NgocSiAiLocalCommand.NEXT -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        "Chuyển bài"
                    } else "Không có bài tiếp theo"
                }
                NgocSiAiLocalCommand.PREVIOUS -> {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                        "Về bài trước"
                    } else "Không có bài trước"
                }
                NgocSiAiLocalCommand.CURRENT_TRACK -> {
                    val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "Chưa rõ tên bài"
                    if (player.isPlaying) "Đang phát $title" else "Bài hiện tại: $title"
                }
                NgocSiAiLocalCommand.SHUFFLE_ON -> {
                    player.shuffleModeEnabled = true
                    "Bật phát ngẫu nhiên"
                }
                NgocSiAiLocalCommand.SHUFFLE_OFF -> {
                    player.shuffleModeEnabled = false
                    "Tắt phát ngẫu nhiên"
                }
                NgocSiAiLocalCommand.REPEAT_OFF -> {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    "Tắt chế độ lặp"
                }
                NgocSiAiLocalCommand.REPEAT_ALL -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    "Lặp hàng đợi"
                }
                NgocSiAiLocalCommand.REPEAT_ONE -> {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    "Lặp bài hiện tại"
                }
            }
        }.getOrElse { "Không thể thực hiện lệnh này" }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, NgocSiWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NGỌC SĨ AI · nghe từ khóa")
            .setContentText(message)
            .setOngoing(!stopping)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Tắt nghe", stopIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        if (stopping) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NGỌC SĨ AI · nhận lệnh giọng nói",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị khi micro đang chờ từ khóa Ngọc Sĩ"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun failAndStop(message: String) {
        updateNotification(message)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        mainHandler.postDelayed({ stopListeningService() }, 2_500L)
    }

    private fun stopListeningService() {
        if (stopping) return
        stopping = true
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        musicController?.release()
        musicController = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        musicController?.release()
        musicController = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ngocsi.music.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.ngocsi.music.action.STOP_WAKE_WORD"
        const val PREFS_NAME = "ngoc_si_wake_word"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "ngoc_si_wake_word"
        private const val NOTIFICATION_ID = 5871
        private const val STOP_REQUEST_CODE = 5872

        fun start(context: Context) {
            val intent = Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}

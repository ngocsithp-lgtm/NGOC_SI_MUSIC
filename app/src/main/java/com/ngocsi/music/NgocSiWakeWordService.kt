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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.Locale

/**
 * Experimental on-device wake-phrase listener. It intentionally refuses online recognition.
 * Start it only from a visible activity after RECORD_AUDIO permission has been granted.
 */
@UnstableApi
class NgocSiWakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var musicController: MediaController? = null
    private var musicControllerConnecting = false
    // The AI Preview has a separate package. This controller targets the stable app session.
    private var productionMusicController: MediaController? = null
    private var productionMusicControllerConnecting = false
    private var listening = false
    private var stopping = false
    private var awaitingCommand = false
    private var awaitingCommandUntilMillis = 0L
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingVoiceReply: String? = null
    private var speaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = textToSpeech
                if (tts != null) {
                    val vietnameseResult = runCatching {
                        tts.setLanguage(Locale.forLanguageTag("vi-VN"))
                    }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                    ttsReady = vietnameseResult >= TextToSpeech.LANG_AVAILABLE
                    if (!ttsReady) {
                        val fallbackResult = runCatching {
                            tts.setLanguage(Locale.getDefault())
                        }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                        ttsReady = fallbackResult >= TextToSpeech.LANG_AVAILABLE
                    }
                    // Keep wake-word replies on the same user-selected voice as chat.
                    if (NgocSiAiVoiceSettings.applySavedVoice(this@NgocSiWakeWordService, tts) != null) {
                        ttsReady = true
                    }
                }
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { speaking = true }
                    }
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            speaking = false
                            if (awaitingCommand) {
                                // Start the command window after the spoken prompt, not before it.
                                awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
                            }
                            if (!stopping) scheduleListenRetry(350L)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            speaking = false
                            if (awaitingCommand) {
                                awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
                            }
                            if (!stopping) scheduleListenRetry(350L)
                        }
                    }
                })
            }

            val queuedReply = pendingVoiceReply
            if (queuedReply != null) {
                pendingVoiceReply = null
                if (ttsReady) {
                    speakReply(queuedReply)
                } else {
                    updateNotification("Đã xử lý lệnh · không có giọng đọc khả dụng trên máy")
                    if (awaitingCommand) {
                        awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
                    }
                    scheduleListenRetry(650L)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningService()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_VOICE_CHANGED) {
            val updated = applyPreferredVoice()
            if (updated) updateNotification("Đã cập nhật giọng đọc AI")
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
        connectProductionMusicController()
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
        if (musicController != null || musicControllerConnecting) return
        musicControllerConnecting = true
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            musicControllerConnecting = false
            runCatching { future.get() }
                .onSuccess { controller ->
                    if (stopping || speechRecognizer == null) controller.release()
                    else musicController = controller
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun connectProductionMusicController() {
        if (productionMusicController != null || productionMusicControllerConnecting) return
        productionMusicControllerConnecting = true
        val component = ComponentName("com.ngocsi.music", "com.ngocsi.music.MusicService")
        val future = runCatching {
            MediaController.Builder(this, SessionToken(this, component)).buildAsync()
        }.getOrElse {
            productionMusicControllerConnecting = false
            return
        }
        future.addListener({
            productionMusicControllerConnecting = false
            runCatching { future.get() }
                .onSuccess { controller ->
                    if (stopping) controller.release()
                    else productionMusicController = controller
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasActivePlayback(controller: MediaController?): Boolean =
        controller != null && (
            controller.isPlaying ||
                (controller.playWhenReady && controller.playbackState == Player.STATE_BUFFERING)
            )

    private fun stopActivePlayback(): String {
        val production = productionMusicController
        val preview = musicController

        if (production == null && productionMusicControllerConnecting) {
            return "Đang kết nối trình phát NGỌC SĨ MUSIC. Hãy thử lại sau một lát."
        }

        val target = when {
            hasActivePlayback(production) -> production
            hasActivePlayback(preview) -> preview
            preview != null -> preview
            else -> production
        } ?: run {
            connectProductionMusicController()
            return "Chưa kết nối được trình phát. Hãy thử lại sau một lát."
        }

        return runCatching {
            target.pause()
            target.playWhenReady = false
            target.stop()
            if (target === production) {
                "Đã dừng hẳn nhạc đang phát trong NGỌC SĨ MUSIC."
            } else {
                "Đã dừng hẳn nhạc trong NGỌC SĨ AI Preview."
            }
        }.getOrElse {
            "Không thể dừng nhạc lúc này. Hãy thử lại."
        }
    }

    /**
     * Don't lose a direct playback command just because Media3 is still connecting.
     * Navigation commands are routed immediately; live-player commands retry briefly.
     */
    private fun dispatchNgocSiAiCommandWhenReady(
        command: NgocSiAiLocalCommand,
        recognized: String,
        attemptsRemaining: Int = 18
    ) {
        if (stopping) return

        if (
            command == NgocSiAiLocalCommand.STOP &&
            productionMusicController == null &&
            attemptsRemaining > 0
        ) {
            // The first voice command can arrive before the stable app's MediaSession
            // connection has completed. Always initiate/re-initiate the connection and
            // wait briefly instead of telling the user to repeat the stop command.
            connectProductionMusicController()
            mainHandler.postDelayed({
                dispatchNgocSiAiCommandWhenReady(command, recognized, attemptsRemaining - 1)
            }, PLAYER_CONNECT_RETRY_MILLIS)
            return
        }

        if (
            ngocSiAiCommandRequiresPlayerConnection(command) &&
            musicController == null &&
            command != NgocSiAiLocalCommand.STOP &&
            attemptsRemaining > 0
        ) {
            connectMusicController()
            mainHandler.postDelayed({
                dispatchNgocSiAiCommandWhenReady(command, recognized, attemptsRemaining - 1)
            }, PLAYER_CONNECT_RETRY_MILLIS)
            return
        }

        val reply = runAppCommand(command, recognized)
        updateNotification("Đã nghe “$recognized” · $reply")
        speakReply(reply)
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
            val now = System.currentTimeMillis()
            if (awaitingCommand && now > awaitingCommandUntilMillis) {
                awaitingCommand = false
                awaitingCommandUntilMillis = 0L
            }
            val wasAwaitingCommand = awaitingCommand
            // While waiting for the command after “Ngọc Sĩ”, prefer any recognized
            // actionable hypothesis rather than blindly trusting Android's first guess.
            // Outside that short window, still require the explicit wake phrase.
            val recognized = (if (wasAwaitingCommand) {
                selectNgocSiAiActivatedCommandCandidate(phrases)
                    ?: selectNgocSiAiWakePhraseCandidate(phrases)
            } else {
                selectNgocSiAiWakePhraseCandidate(phrases)
            }).orEmpty()
            val command = classifyNgocSiAiWakePhrase(recognized)
                ?: if (wasAwaitingCommand) classifyNgocSiAiActivatedCommand(recognized) else null

            if (command != null) {
                awaitingCommand = false
                awaitingCommandUntilMillis = 0L
                if (musicController == null) {
                    updateNotification("Đã nhận “$recognized” · đang kết nối trình phát")
                    connectMusicController()
                }
                dispatchNgocSiAiCommandWhenReady(command, recognized)
            } else if (isNgocSiAiWakeWordOnly(recognized)) {
                // Two-stage interaction: say "Ngọc Sĩ", then give a playback command.
                // The short window prevents unrelated later speech from triggering playback.
                awaitingCommand = true
                // The TTS listener starts the eight-second deadline once the prompt is done.
                awaitingCommandUntilMillis = Long.MAX_VALUE
                updateNotification("Đã nghe từ khóa “Ngọc Sĩ” · hãy nói lệnh trong 8 giây")
                speakReply("Tôi đang nghe lệnh. Hãy nói lệnh điều khiển ứng dụng.")
                return
            } else if (recognized.isNotBlank()) {
                awaitingCommand = false
                awaitingCommandUntilMillis = 0L
                if (wasAwaitingCommand) {
                    updateNotification("Chưa nhận ra lệnh · hãy nói “Ngọc Sĩ, mở YouTube” hoặc “Ngọc Sĩ, chuyển bài”")
                } else {
                    // Expose the actual speech-recognition result so the user can tell
                    // whether the problem is recognition or command matching.
                    updateNotification("Đã nghe: “${recognized.take(55)}” · cần mở đầu bằng “Ngọc Sĩ”")
                }
            }
            if (command == null) scheduleListenRetry(650L)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun beginListening() {
        if (stopping || speechRecognizer == null || listening || speaking) return
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

    private fun applyPreferredVoice(): Boolean {
        val tts = textToSpeech ?: return false
        val voiceName = NgocSiAiVoiceSettings.selectedVoiceName(this) ?: return false
        val voice = tts.voices?.firstOrNull { it.name == voiceName } ?: return false
        return runCatching { tts.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    private fun speakReply(message: String) {
        val cleanMessage = NgocSiAiSpeechTextRules.cleanForSpeech(message)
        if (stopping || cleanMessage.isBlank()) return
        val tts = textToSpeech
        if (!ttsReady || tts == null) {
            // Keep the cleaned result until asynchronous TextToSpeech initialization finishes.
            pendingVoiceReply = cleanMessage
            updateNotification("$cleanMessage · đang chuẩn bị giọng đọc")
            return
        }
        speaking = true
        val result = runCatching {
            tts.speak(cleanMessage, TextToSpeech.QUEUE_FLUSH, null, "ngoc_si_reply_" + System.currentTimeMillis())
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            speaking = false
            if (awaitingCommand) {
                awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
            }
            updateNotification("$cleanMessage · không phát được giọng nói")
            scheduleListenRetry(650L)
        }
    }

    /**
     * Routes wake-word commands through the same destinations as the AI chat.
     * Navigation is handled before requiring a queue, so opening Drive/YouTube/TV,
     * changing library views, or setting a timer does not depend on a song playing.
     */
    private fun launchMainAppRoute(
        destination: String,
        response: String,
        trackQuery: String? = null
    ): String {
        return runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("pro_destination", destination)
                trackQuery?.let { putExtra("ai_track_query", it) }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
            response
        }.getOrElse {
            "Chưa thể mở thư viện từ nền. Hãy mở NGỌC SĨ MUSIC rồi lặp lại lệnh."
        }
    }
    private fun runAppCommand(command: NgocSiAiLocalCommand, originalText: String): String {
        if (command == NgocSiAiLocalCommand.HELP) {
            return "Tôi có thể điều khiển nhạc điện thoại và YouTube riêng biệt; phát bài theo tên; tìm nhạc trên Jamendo, Audius và YouTube; mở thư viện, Drive, từng đài Radio, kênh TV; tạo/phát playlist; điều chỉnh phát ngẫu nhiên, lặp, hẹn giờ và lưu video yêu thích/xem sau."
        }

        val hasSavedYouTubeVideo = getSharedPreferences("ngoc_si_music", Context.MODE_PRIVATE)
            .getString("youtube_last_played_video_id", null)
            .isNullOrBlank()
            .not()
        val destination = when (command) {
            NgocSiAiLocalCommand.OPEN_HOME -> "home"
            NgocSiAiLocalCommand.OPEN_LIBRARY -> "library"
            NgocSiAiLocalCommand.OPEN_FAVORITES -> "favorites"
            NgocSiAiLocalCommand.OPEN_ALBUMS -> "albums"
            NgocSiAiLocalCommand.OPEN_FOLDERS -> "folders"
            NgocSiAiLocalCommand.OPEN_ONLINE -> "online"
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC -> "search_online"
            NgocSiAiLocalCommand.OPEN_YOUTUBE,
            NgocSiAiLocalCommand.SEARCH_YOUTUBE -> "youtube"
            NgocSiAiLocalCommand.OPEN_DRIVE -> "drive"
            NgocSiAiLocalCommand.OPEN_RADIO -> "radio"
            NgocSiAiLocalCommand.PLAY_RADIO_STATION -> "radio_station"
            NgocSiAiLocalCommand.OPEN_TV -> "tv"
            NgocSiAiLocalCommand.OPEN_TV_CHANNEL -> "tv_channel"
            NgocSiAiLocalCommand.CREATE_PLAYLIST -> "create_playlist"
            NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST -> "play_playlist"
            NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST -> "add_current_to_playlist"
            NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST -> "add_queue_to_playlist"
            NgocSiAiLocalCommand.RESUME_YOUTUBE,
            NgocSiAiLocalCommand.PAUSE_YOUTUBE,
            NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO,
            NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO,
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON,
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF,
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF,
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL,
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE,
            NgocSiAiLocalCommand.YOUTUBE_FAVORITE,
            NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER ->
                if (hasSavedYouTubeVideo) "youtube_command" else "youtube"
            NgocSiAiLocalCommand.OPEN_PLAYLISTS -> "playlists"
            NgocSiAiLocalCommand.OPEN_QUEUE -> "queue"
            NgocSiAiLocalCommand.OPEN_PLAYER -> "player"
            NgocSiAiLocalCommand.OPEN_SETTINGS -> "settings"
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER -> "sleep_timer"
            NgocSiAiLocalCommand.SLEEP_TIMER_15 -> "sleep_timer_15"
            NgocSiAiLocalCommand.SLEEP_TIMER_30 -> "sleep_timer_30"
            NgocSiAiLocalCommand.SLEEP_TIMER_45 -> "sleep_timer_45"
            NgocSiAiLocalCommand.SLEEP_TIMER_60 -> "sleep_timer_60"
            NgocSiAiLocalCommand.SLEEP_TIMER_90 -> "sleep_timer_90"
            NgocSiAiLocalCommand.SLEEP_TIMER_120 -> "sleep_timer_120"
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER -> "sleep_timer_off"
            else -> null
        }

        if (destination != null) {
            val query = if (command == NgocSiAiLocalCommand.SEARCH_YOUTUBE) {
                extractNgocSiAiYoutubeQuery(originalText)
                    ?: return "Hãy nói rõ nội dung cần tìm, ví dụ: Ngọc Sĩ, tìm YouTube nhạc Trịnh Công Sơn."
            } else null
            val onlineQuery = if (command == NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC) {
                extractNgocSiAiOnlineQuery(originalText)
                    ?: return "Hãy nói nội dung cần tìm, ví dụ: Ngọc Sĩ, tìm nhạc Trịnh Công Sơn."
            } else null
            val radioQuery = if (command == NgocSiAiLocalCommand.PLAY_RADIO_STATION) {
                extractNgocSiAiRadioStationQuery(originalText)
                    ?: return "Hãy nói tên đài, ví dụ: Ngọc Sĩ, phát Radio VOV3."
            } else null
            val tvQuery = if (command == NgocSiAiLocalCommand.OPEN_TV_CHANNEL) {
                extractNgocSiAiTvChannelQuery(originalText)
                    ?: return "Hãy nói tên kênh, ví dụ: Ngọc Sĩ, mở VTV3."
            } else null
            val playlistQuery = when (command) {
                NgocSiAiLocalCommand.CREATE_PLAYLIST ->
                    extractNgocSiAiPlaylistQuery(originalText, create = true)
                        ?: return "Hãy nói tên playlist muốn tạo, ví dụ: Ngọc Sĩ, tạo playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST ->
                    extractNgocSiAiPlaylistQuery(originalText, create = false)
                        ?: return "Hãy nói tên playlist muốn phát, ví dụ: Ngọc Sĩ, phát playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST ->
                    extractNgocSiAiAddToPlaylistQuery(originalText)
                        ?: return "Hãy nói rõ playlist đích, ví dụ: Ngọc Sĩ, thêm bài đang phát vào playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST ->
                    extractNgocSiAiQueueToPlaylistQuery(originalText)
                        ?: return "Hãy nói rõ playlist đích, ví dụ: Ngọc Sĩ, thêm hàng đợi vào playlist Nhạc Trịnh."
                else -> null
            }
            val youtubeAction = when (command) {
                NgocSiAiLocalCommand.RESUME_YOUTUBE -> "play"
                NgocSiAiLocalCommand.PAUSE_YOUTUBE -> "pause"
                NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO -> "next"
                NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO -> "previous"
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON -> "shuffle_on"
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF -> "shuffle_off"
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF -> "repeat_off"
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL -> "repeat_all"
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE -> "repeat_one"
                NgocSiAiLocalCommand.YOUTUBE_FAVORITE -> "favorite"
                NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER -> "watch_later"
                else -> null
            }
            val missingYouTubeHistory = !hasSavedYouTubeVideo && command in setOf(
                NgocSiAiLocalCommand.RESUME_YOUTUBE,
                NgocSiAiLocalCommand.PAUSE_YOUTUBE,
                NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO,
                NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO,
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON,
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF,
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF,
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL,
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE,
                NgocSiAiLocalCommand.YOUTUBE_FAVORITE,
                NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER
            )
            val response = if (missingYouTubeHistory) {
                "Chưa có video YouTube gần đây để điều khiển. Tôi đã mở YouTube; hãy tìm và phát video trước."
            } else when (command) {
                NgocSiAiLocalCommand.SEARCH_YOUTUBE -> "Đang tìm trên YouTube: $query."
                NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC -> "Đang tìm $onlineQuery trên các nguồn nhạc trực tuyến."
                NgocSiAiLocalCommand.OPEN_HOME -> "Đã gửi lệnh mở Trang chủ."
                NgocSiAiLocalCommand.OPEN_LIBRARY -> "Đã gửi lệnh mở Thư viện."
                NgocSiAiLocalCommand.OPEN_FAVORITES -> "Đã gửi lệnh mở nhạc Yêu thích."
                NgocSiAiLocalCommand.OPEN_ALBUMS -> "Đã gửi lệnh mở Album."
                NgocSiAiLocalCommand.OPEN_FOLDERS -> "Đã gửi lệnh mở Thư mục."
                NgocSiAiLocalCommand.OPEN_ONLINE -> "Đã gửi lệnh mở các nguồn Online."
                NgocSiAiLocalCommand.OPEN_YOUTUBE -> "Đã gửi lệnh mở YouTube."
                NgocSiAiLocalCommand.OPEN_DRIVE -> "Đã gửi lệnh mở Google Drive."
                NgocSiAiLocalCommand.OPEN_RADIO -> "Đã gửi lệnh mở Radio."
                NgocSiAiLocalCommand.PLAY_RADIO_STATION -> "Đang mở đài $radioQuery."
                NgocSiAiLocalCommand.OPEN_TV -> "Đã gửi lệnh mở TV."
                NgocSiAiLocalCommand.OPEN_TV_CHANNEL -> "Đang mở kênh $tvQuery."
                NgocSiAiLocalCommand.CREATE_PLAYLIST -> "Đang tạo playlist $playlistQuery."
                NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST -> "Đang tìm playlist $playlistQuery."
                NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST -> "Đang thêm bài đang phát vào playlist $playlistQuery."
                NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST -> "Đang lưu hàng đợi vào playlist $playlistQuery."
                NgocSiAiLocalCommand.RESUME_YOUTUBE -> if (hasSavedYouTubeVideo) {
                    "Đang tiếp tục video YouTube."
                } else {
                    "Chưa có video YouTube gần đây để tiếp tục. Tôi đã mở YouTube; hãy nói “Tìm YouTube” kèm tên bài hát hoặc video."
                }
                NgocSiAiLocalCommand.PAUSE_YOUTUBE -> "Đang tạm dừng video YouTube."
                NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO -> "Đang chuyển video YouTube tiếp theo."
                NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO -> "Đang chuyển video YouTube trước."
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON -> "Đang bật phát ngẫu nhiên YouTube."
                NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF -> "Đang tắt phát ngẫu nhiên YouTube."
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF -> "Đang tắt lặp YouTube."
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL -> "Đang lặp hàng đợi YouTube."
                NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE -> "Đang lặp video YouTube."
                NgocSiAiLocalCommand.YOUTUBE_FAVORITE -> "Đang thêm video YouTube vào Yêu thích."
                NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER -> "Đang lưu video YouTube xem sau."
                NgocSiAiLocalCommand.OPEN_PLAYLISTS -> "Đã gửi lệnh mở Danh sách phát."
                NgocSiAiLocalCommand.OPEN_QUEUE -> "Đã gửi lệnh mở Hàng đợi."
                NgocSiAiLocalCommand.OPEN_PLAYER -> "Đã gửi lệnh mở Trình phát."
                NgocSiAiLocalCommand.OPEN_SETTINGS -> "Đã gửi lệnh mở Cài đặt."
                NgocSiAiLocalCommand.OPEN_SLEEP_TIMER -> "Đã gửi lệnh mở Hẹn giờ."
                NgocSiAiLocalCommand.SLEEP_TIMER_15 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 15 phút."
                NgocSiAiLocalCommand.SLEEP_TIMER_30 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 30 phút."
                NgocSiAiLocalCommand.SLEEP_TIMER_45 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 45 phút."
                NgocSiAiLocalCommand.SLEEP_TIMER_60 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 60 phút."
                NgocSiAiLocalCommand.SLEEP_TIMER_90 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 90 phút."
                NgocSiAiLocalCommand.SLEEP_TIMER_120 -> "Đã gửi lệnh hẹn giờ tắt nhạc sau 120 phút."
                NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER -> "Đã gửi lệnh hủy hẹn giờ."
                else -> "Đã gửi lệnh điều hướng."
            }
            return runCatching {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("pro_destination", destination)
                    query?.let { putExtra("ai_youtube_query", it) }
                    onlineQuery?.let { putExtra("ai_online_query", it) }
                    radioQuery?.let { putExtra("ai_radio_station", it) }
                    tvQuery?.let { putExtra("ai_tv_channel", it) }
                    playlistQuery?.let { putExtra("ai_playlist_query", it) }
                    youtubeAction?.let { putExtra("ai_youtube_action", it) }
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(intent)
                response
            }.getOrElse {
                "Chưa thể mở chức năng này từ nền. Hãy mở NGỌC SĨ MUSIC rồi lặp lại lệnh."
            }
        }

        if (command == NgocSiAiLocalCommand.STOP) return stopActivePlayback()

        val connectedPlayer = musicController
        if (command == NgocSiAiLocalCommand.PLAY_TRACK &&
            (connectedPlayer == null || connectedPlayer.mediaItemCount == 0)
        ) {
            val query = extractNgocSiAiTrackQuery(originalText)
                ?: return "Hãy nói rõ tên bài, ví dụ: “Ngọc Sĩ, phát bài hát Diễm xưa”."
            return launchMainAppRoute(
                destination = "play_track",
                response = "Đang tìm và phát bài $query trong thư viện.",
                trackQuery = query
            )
        }
        if (command == NgocSiAiLocalCommand.PLAY &&
            (connectedPlayer == null || connectedPlayer.mediaItemCount == 0)
        ) {
            return launchMainAppRoute(
                destination = "play_local",
                response = "Đang mở thư viện và phát nhạc trên điện thoại."
            )
        }
        val player = connectedPlayer
            ?: return "Trình phát chưa kết nối. Hãy mở NGỌC SĨ MUSIC một lần rồi thử lại."

        if (command == NgocSiAiLocalCommand.PLAY_TRACK) {
            val query = extractNgocSiAiTrackQuery(originalText)
                ?: return "Hãy nói rõ tên bài, ví dụ: “Ngọc Sĩ, phát bài hát Diễm xưa”."
            val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
            val exactIndex = (0 until player.mediaItemCount).firstOrNull { index ->
                normalizeNgocSiAiVoicePhrase(
                    player.getMediaItemAt(index).mediaMetadata.title?.toString().orEmpty()
                ) == normalizedQuery
            }
            val matchIndex = exactIndex ?: (0 until player.mediaItemCount).firstOrNull { index ->
                val item = player.getMediaItemAt(index)
                val title = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.title?.toString().orEmpty())
                val artist = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.artist?.toString().orEmpty())
                title.contains(normalizedQuery) || artist.contains(normalizedQuery)
            }
            if (matchIndex == null) {
                return launchMainAppRoute(
                    destination = "play_track",
                    response = "Đang tìm bài $query trong thư viện.",
                    trackQuery = query
                )
            }
            player.seekTo(matchIndex, 0L)
            player.play()
            val title = player.getMediaItemAt(matchIndex).mediaMetadata.title?.toString()
                ?.takeIf { it.isNotBlank() } ?: query
            return "Đang phát $title."
        }
        if (command == NgocSiAiLocalCommand.PLAY &&
            player.mediaItemCount > 0 && player.currentMediaItem == null
        ) {
            player.seekToDefaultPosition()
            player.prepare()
            player.play()
            return "Đang bắt đầu phát từ đầu hàng đợi."
        }
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) {
            return when (command) {
                NgocSiAiLocalCommand.PLAY,
                NgocSiAiLocalCommand.PLAY_SECOND,
                NgocSiAiLocalCommand.NEXT,
                NgocSiAiLocalCommand.PREVIOUS,
                NgocSiAiLocalCommand.CURRENT_TRACK ->
                    "Hàng đợi đang trống. Hãy mở Thư viện và chọn bài nhạc trước."
                else -> "Chưa có bài nhạc trong hàng đợi để thực hiện lệnh này."
            }
        }

        return runCatching {
            when (command) {
                NgocSiAiLocalCommand.PLAY -> {
                    player.play()
                    "Đã gửi lệnh phát nhạc"
                }
                NgocSiAiLocalCommand.PLAY_SECOND -> {
                    if (player.mediaItemCount > 1) {
                        player.seekTo(1, 0L)
                        player.play()
                        "Đang phát bài thứ hai"
                    } else "Hàng đợi chưa có bài thứ hai"
                }
                NgocSiAiLocalCommand.PAUSE -> {
                    player.pause()
                    "Đã tạm dừng nhạc"
                }
                NgocSiAiLocalCommand.NEXT -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        "Đã chuyển bài tiếp theo"
                    } else "Không có bài tiếp theo"
                }
                NgocSiAiLocalCommand.PREVIOUS -> {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                        "Đã quay lại bài trước"
                    } else "Không có bài trước"
                }
                NgocSiAiLocalCommand.CURRENT_TRACK -> {
                    val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "Chưa rõ tên bài"
                    val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString()
                        ?.takeIf { it.isNotBlank() }
                    val status = if (player.isPlaying) "Đang phát" else "Bài hiện tại"
                    if (artist == null) "$status: $title" else "$status: $title — $artist"
                }
                NgocSiAiLocalCommand.SHUFFLE_ON -> {
                    player.shuffleModeEnabled = true
                    "Đã bật phát ngẫu nhiên"
                }
                NgocSiAiLocalCommand.SHUFFLE_OFF -> {
                    player.shuffleModeEnabled = false
                    "Đã tắt phát ngẫu nhiên"
                }
                NgocSiAiLocalCommand.REPEAT_OFF -> {
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    "Đã tắt chế độ lặp"
                }
                NgocSiAiLocalCommand.REPEAT_ALL -> {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    "Đã bật lặp hàng đợi"
                }
                NgocSiAiLocalCommand.REPEAT_ONE -> {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    "Đã bật lặp bài hiện tại"
                }
                else -> "Lệnh này chưa có thao tác trực tiếp. Hãy mở NGỌC SĨ AI để được hướng dẫn."
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
        awaitingCommand = false
        awaitingCommandUntilMillis = 0L
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        musicController?.release()
        musicController = null
        productionMusicController?.release()
        productionMusicController = null
        runCatching { textToSpeech?.stop() }
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        ttsReady = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        awaitingCommand = false
        awaitingCommandUntilMillis = 0L
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        musicController?.release()
        musicController = null
        productionMusicController?.release()
        productionMusicController = null
        runCatching { textToSpeech?.stop() }
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        ttsReady = false
        pendingVoiceReply = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        super.onDestroy()
    }

    companion object {
        private const val PLAYER_CONNECT_RETRY_MILLIS = 300L
        const val ACTION_START = "com.ngocsi.music.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.ngocsi.music.action.STOP_WAKE_WORD"
        const val ACTION_VOICE_CHANGED = "com.ngocsi.music.action.WAKE_WORD_VOICE_CHANGED"
        const val PREFS_NAME = "ngoc_si_wake_word"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "ngoc_si_wake_word"
        private const val NOTIFICATION_ID = 5871
        private const val STOP_REQUEST_CODE = 5872
        private const val COMMAND_WINDOW_MILLIS = 8_000L

        fun start(context: Context) {
            val intent = Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_STOP))
        }

        /** Refreshes the selected TTS voice only when the wake-word service is already running. */
        fun updateVoice(context: Context) {
            context.startService(
                Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_VOICE_CHANGED)
            )
        }
    }
}

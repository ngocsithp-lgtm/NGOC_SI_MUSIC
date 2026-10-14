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
import java.io.ByteArrayOutputStream
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Experimental on-device wake-phrase listener. It intentionally refuses online recognition.
 * Start it only from a visible activity after RECORD_AUDIO permission has been granted.
 */
@UnstableApi
class NgocSiWakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var answeringQuestion = false
    // Once "AI" wakes the assistant, keep a hands-free conversation session open until idle.
    private var conversationActive = false
    private var conversationSpeechStarted = false
    private val conversationHistory = mutableListOf<NgocSiAiMessage>()
    private var speechRecognizer: SpeechRecognizer? = null
    private var musicController: MediaController? = null
    private var musicControllerConnecting = false
    // The AI Preview has a separate package. This controller targets the stable app session.
    private var productionMusicController: MediaController? = null
    private var productionMusicControllerConnecting = false
    private var listening = false
    // Prevent overlapping startListening calls while Android is still preparing the recognizer.
    private var recognitionStarting = false
    private var partialRecognitionCandidates: List<String> = emptyList()
    private var stopping = false
    private var awaitingCommand = false
    private var awaitingCommandUntilMillis = 0L
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingVoiceReply: String? = null
    private var speaking = false
    private val currentAudioBuffer = ByteArrayOutputStream()
    private var enrollmentSamplesRemaining = 0
    private var enrollmentAudioFailures = 0
    private var enrollmentPromptPending = false
    private val enrollmentFeatures = mutableListOf<FloatArray>()

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
                            enrollmentPromptPending = false
                            if (awaitingCommand) {
                                // Keep the original short wake-prompt window for backwards compatibility.
                                awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
                            }
                            if (conversationActive) scheduleConversationTimeout()
                            if (!stopping) scheduleListenRetry(350L)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            speaking = false
                            enrollmentPromptPending = false
                            if (awaitingCommand) {
                                awaitingCommandUntilMillis = System.currentTimeMillis() + COMMAND_WINDOW_MILLIS
                            }
                            if (conversationActive) scheduleConversationTimeout()
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
                    if (conversationActive) scheduleConversationTimeout()
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
        if (intent?.action == ACTION_ENROLL_OWNER_VOICE) {
            enrollmentSamplesRemaining = OWNER_VOICE_ENROLLMENT_SAMPLES
            enrollmentAudioFailures = 0
            enrollmentFeatures.clear()
            currentAudioBuffer.reset()
            enrollmentPromptPending = true
            updateNotification("Bắt đầu đăng ký giọng nói · 0/3 mẫu")
            if (speechRecognizer != null) {
                mainHandler.removeCallbacks(restartListening)
                mainHandler.removeCallbacks(partialRecognitionFallback)
                partialRecognitionCandidates = emptyList()
                currentAudioBuffer.reset()
                runCatching { speechRecognizer?.cancel() }
                speakReply(enrollmentPrompt())
                return START_NOT_STICKY
            }
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
            if (enrollmentSamplesRemaining > 0) {
                speakReply(enrollmentPrompt())
            } else {
                beginListening()
            }
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

    private fun preferredPlaybackController(): MediaController? {
        val production = productionMusicController
        val preview = musicController
        return when (
            selectNgocSiAiPlaybackTarget(
                productionConnected = production != null,
                productionActive = hasActivePlayback(production),
                productionHasItems = (production?.mediaItemCount ?: 0) > 0,
                previewConnected = preview != null,
                previewActive = hasActivePlayback(preview),
                previewHasItems = (preview?.mediaItemCount ?: 0) > 0
            )
        ) {
            NgocSiAiPlaybackTarget.PRODUCTION -> production
            NgocSiAiPlaybackTarget.PREVIEW -> preview
            null -> null
        }
    }

    private fun stopActivePlayback(): String {
        val production = productionMusicController
        val preview = musicController

        // Do not stop an idle Preview queue when the active stable player's session
        // could not be reached. A failed connection must never be reported as success.
        if (production == null && productionMusicControllerConnecting) {
            return "Đang kết nối trình phát NGỌC SĨ MUSIC. Chưa thể xác nhận đã dừng nhạc."
        }

        val target = when (
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = production != null,
                productionActive = hasActivePlayback(production),
                previewConnected = preview != null,
                previewActive = hasActivePlayback(preview)
            )
        ) {
            NgocSiAiPlaybackTarget.PRODUCTION -> production
            NgocSiAiPlaybackTarget.PREVIEW -> preview
            null -> null
        } ?: run {
            connectProductionMusicController()
            return "Chưa kết nối được trình phát NGỌC SĨ MUSIC; nhạc chưa được dừng. Hãy mở ứng dụng chính rồi thử lại."
        }

        return runCatching {
            target.pause()
            target.playWhenReady = false
            target.stop()
            if (target === production) {
                "Đã dừng hẳn nhạc đang phát trong NGỌC SĨ MUSIC."
            } else {
                "Đã dừng hẳn nhạc đang phát trong NGỌC SĨ AI Preview."
            }
        }.getOrElse {
            "Không thể dừng nhạc lúc này. Nhạc chưa được xác nhận là đã dừng; hãy thử lại."
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
            command != NgocSiAiLocalCommand.STOP &&
            productionMusicController == null &&
            (productionMusicControllerConnecting || musicController == null) &&
            attemptsRemaining > 0
        ) {
            // Prefer the stable app's live queue when its session is still binding.
            // An already-connected empty Preview player must not win just because it
            // became available a little earlier than the production controller.
            connectMusicController()
            connectProductionMusicController()
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
            recognitionStarting = false
            mainHandler.removeCallbacks(recognitionStartWatchdog)
            listening = true
            updateNotification(
                if (conversationActive) "Đang nghe cuộc trò chuyện · nói tự nhiên, không cần gọi AI lại"
                else "Đang nghe từ khóa “AI” · chỉ nhận dạng trên thiết bị"
            )
        }

        override fun onBeginningOfSpeech() {
            conversationSpeechStarted = true
            if (conversationActive) cancelConversationTimeout()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer == null || stopping) return
            // Android does not guarantee this callback on every recognizer implementation.
            // The voice lock therefore fails closed if usable PCM is not provided.
            val remaining = MAX_CAPTURED_AUDIO_BYTES - currentAudioBuffer.size()
            if (remaining > 0) currentAudioBuffer.write(buffer, 0, minOf(buffer.size, remaining))
        }

        override fun onEndOfSpeech() {
            listening = false
            // Some Android offline recognizers deliver useful partial text but never call
            // onResults. Give the final callback a short chance, then submit the best phrase.
            if (partialRecognitionCandidates.isNotEmpty()) {
                mainHandler.removeCallbacks(partialRecognitionFallback)
                mainHandler.postDelayed(partialRecognitionFallback, PARTIAL_AFTER_END_MILLIS)
            }
        }

        override fun onError(error: Int) {
            listening = false
            recognitionStarting = false
            mainHandler.removeCallbacks(recognitionStartWatchdog)
            mainHandler.removeCallbacks(partialRecognitionFallback)
            val candidates = partialRecognitionCandidates
            partialRecognitionCandidates = emptyList()
            val speechWasInProgress = conversationSpeechStarted
            conversationSpeechStarted = false
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
            // Use any text already captured instead of silently discarding it on a late error.
            if (candidates.isNotEmpty()) {
                processRecognitionPhrases(candidates)
                return
            }
            if (conversationActive && speechWasInProgress) scheduleConversationTimeout()
            scheduleListenRetry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_000L else 350L)
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(recognitionStartWatchdog)
            mainHandler.removeCallbacks(partialRecognitionFallback)
            recognitionStarting = false
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            partialRecognitionCandidates = emptyList()
            processRecognitionPhrases(phrases)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val candidates = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (candidates.isEmpty() || stopping) return
            partialRecognitionCandidates = candidates
            // A trailing partial result should not leave the microphone loop stuck forever.
            mainHandler.removeCallbacks(partialRecognitionFallback)
            mainHandler.postDelayed(partialRecognitionFallback, PARTIAL_IDLE_FALLBACK_MILLIS)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private val recognitionStartWatchdog = Runnable {
        if (stopping || !recognitionStarting) return@Runnable
        recognitionStarting = false
        listening = false
        runCatching { speechRecognizer?.cancel() }
        updateNotification("Dịch vụ nghe phản hồi chậm · đang tự khởi động lại")
        scheduleListenRetry(800L)
    }

    private val partialRecognitionFallback = Runnable {
        if (stopping || partialRecognitionCandidates.isEmpty()) return@Runnable
        val candidates = partialRecognitionCandidates
        partialRecognitionCandidates = emptyList()
        recognitionStarting = false
        listening = false
        mainHandler.removeCallbacks(recognitionStartWatchdog)
        // Cancel before processing to avoid a late final result being handled twice.
        runCatching { speechRecognizer?.cancel() }
        processRecognitionPhrases(candidates)
    }

    private fun processRecognitionPhrases(phrases: List<String>) {
        listening = false
        recognitionStarting = false
        conversationSpeechStarted = false
        val capturedAudio = currentAudioBuffer.toByteArray()
        currentAudioBuffer.reset()
        if (enrollmentSamplesRemaining > 0) {
            handleOwnerVoiceEnrollment(capturedAudio)
            return
        }
        if (NgocSiVoiceProfile.isLockEnabled(this) && phrases.any { it.isNotBlank() }) {
            val profile = NgocSiVoiceProfile.loadProfile(this)
            val sample = NgocSiVoiceProfile.extractFeatures(this, capturedAudio)
            val rejectionMessage = when {
                profile == null -> "Đã bỏ qua: không mở được hồ sơ giọng nói đã đăng ký"
                sample == null -> "Đã bỏ qua: không lấy được âm thanh để xác minh giọng chủ máy"
                !NgocSiVoiceProfile.matches(profile, sample) ->
                    "Đã bỏ qua câu nói: giọng không khớp hồ sơ đã đăng ký"
                else -> null
            }
            if (rejectionMessage != null) {
                awaitingCommand = false
                awaitingCommandUntilMillis = 0L
                updateNotification(rejectionMessage)
                scheduleListenRetry(350L)
                return
            }
        }
        if (conversationActive) cancelConversationTimeout()
        val now = System.currentTimeMillis()
        if (awaitingCommand && now > awaitingCommandUntilMillis) {
            awaitingCommand = false
            awaitingCommandUntilMillis = 0L
        }
        val wasAwaitingCommand = awaitingCommand || conversationActive
        // Once awake, prefer an implemented command across all recognition candidates.
        // Otherwise preserve the best natural-language candidate for conversation.
        val recognized = (if (wasAwaitingCommand) {
            selectNgocSiAiActivatedCommandCandidate(phrases)
                ?: phrases.firstOrNull { it.isNotBlank() }?.trim()
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
            // Keep the hands-free session active until idle; later turns don't need the wake word.
            if (!conversationActive) conversationHistory.clear()
            conversationActive = true
            conversationSpeechStarted = false
            cancelConversationTimeout()
            awaitingCommand = true
            awaitingCommandUntilMillis = Long.MAX_VALUE
            updateNotification("Đã nghe từ khóa “AI” · có thể trò chuyện liên tục")
            speakReply("Vâng, tôi nghe đây. Bạn cứ nói tiếp, không cần gọi AI lại.")
            return
        } else if (recognized.isNotBlank()) {
            awaitingCommand = false
            awaitingCommandUntilMillis = 0L
            if (wasAwaitingCommand) {
                // Ordinary questions go to the same local-first conversation path as chat.
                answerConversationalQuestion(recognized)
                return
            } else {
                updateNotification("Đã nghe: “${recognized.take(55)}” · cần mở đầu bằng “AI”")
            }
        }
        if (recognized.isBlank() && conversationActive) scheduleConversationTimeout()
        if (command == null) scheduleListenRetry(350L)
    }

    private fun answerConversationalQuestion(question: String) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isBlank() || stopping) {
            if (!stopping) {
                if (conversationActive) scheduleConversationTimeout()
                scheduleListenRetry(650L)
            }
            return
        }
        answeringQuestion = true
        cancelConversationTimeout()
        updateNotification("Đã nghe câu hỏi · đang tạo câu trả lời…")
        aiScope.launch {
            val client = NgocSiAiRemoteClient(applicationContext)
            val previousAssistantReply = conversationHistory
                .lastOrNull { it.role == "assistant" }
                ?.content
            // Match the in-app chat path: answer familiar exchanges locally first so
            // greetings, follow-ups, and quota questions do not consume Gemini requests.
            val localFallback = localConversationFallback(cleanQuestion, previousAssistantReply)
            val userMessage = NgocSiAiMessage(role = "user", content = cleanQuestion)
            // Preserve recent turns so follow-up questions have context in either mode.
            val requestHistory = conversationHistory.toMutableList()
            while (requestHistory.size > MAX_CONVERSATION_MESSAGES - 2) {
                requestHistory.removeAt(0)
            }
            requestHistory.add(userMessage)

            var remoteAnswer: String? = null
            var remoteError: String? = null
            if (localFallback == null) {
                if (!client.isConfigured) {
                    remoteError =
                        "Tôi nghe được câu hỏi, nhưng chat trực tuyến chưa được cấu hình trong bản Preview này. Tôi vẫn có thể thực hiện các lệnh điều khiển ứng dụng được hỗ trợ."
                } else {
                    try {
                        remoteAnswer = client.send(requestHistory).answer
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        remoteError = error.message?.takeIf { it.isNotBlank() }
                            ?: "Tôi đã nghe câu hỏi nhưng hiện chưa lấy được câu trả lời. Hãy thử lại sau."
                    }
                }
            }
            if (stopping) return@launch

            val errorFallback = if (remoteAnswer.isNullOrBlank() && remoteError != null) {
                localConversationFallbackAfterRemoteError(
                    userText = cleanQuestion,
                    previousAssistantReply = previousAssistantReply,
                    remoteError = remoteError
                )
            } else null
            val answer = localFallback
                ?: remoteAnswer?.trim()?.takeIf { it.isNotBlank() }
                ?: errorFallback
                ?: remoteError?.takeIf { it.isNotBlank() }
                ?: "Tôi đã nghe câu hỏi nhưng hiện chưa lấy được câu trả lời. Hãy thử lại sau."
            // Keep successful Gemini turns and safe local replies for natural follow-ups;
            // never put a raw transport/quota error into the conversation history.
            val keepTurnInHistory =
                localFallback != null || !remoteAnswer.isNullOrBlank() || errorFallback != null
            if (keepTurnInHistory) {
                conversationHistory.clear()
                conversationHistory.addAll(requestHistory)
                conversationHistory.add(NgocSiAiMessage(role = "assistant", content = answer))
                while (conversationHistory.size > MAX_CONVERSATION_MESSAGES) {
                    conversationHistory.removeAt(0)
                }
            }
            answeringQuestion = false
            val replySource = if (localFallback != null) {
                "Trả lời tại máy · không dùng hạn mức Gemini"
            } else {
                "Câu trả lời"
            }
            updateNotification("$replySource: ${answer.take(100)}")
            speakReply(answer)
        }
    }

    private fun handleOwnerVoiceEnrollment(pcm: ByteArray) {
        val sample = NgocSiVoiceProfile.extractFeatures(pcm)
        if (sample == null) {
            enrollmentAudioFailures++
            if (enrollmentAudioFailures >= MAX_ENROLLMENT_AUDIO_FAILURES) {
                enrollmentSamplesRemaining = 0
                enrollmentFeatures.clear()
                enrollmentPromptPending = true
                updateNotification(
                    "Không lấy được mẫu âm thanh từ dịch vụ nhận dạng · đăng ký đã dừng"
                )
                speakReply(
                    "Tôi không lấy được mẫu âm thanh để đăng ký. Có thể dịch vụ nhận dạng trên máy không cung cấp dữ liệu âm thanh. " +
                        "Tôi đã dừng đăng ký; hồ sơ giọng cũ, nếu có, vẫn được giữ nguyên."
                )
                return
            }
            enrollmentPromptPending = true
            updateNotification(
                "Chưa lấy đủ âm thanh · hãy đọc câu dài hơn, rõ ràng và gần micro"
            )
            speakReply(enrollmentPrompt())
            return
        }
        enrollmentAudioFailures = 0
        enrollmentFeatures.add(sample)
        enrollmentSamplesRemaining--
        val completed = enrollmentFeatures.size
        if (enrollmentSamplesRemaining > 0) {
            enrollmentPromptPending = true
            updateNotification("Đã ghi mẫu giọng " + completed + "/3 · tiếp tục đọc câu tiếp theo")
            speakReply(enrollmentPrompt())
            return
        }

        val profile = NgocSiVoiceProfile.combine(enrollmentFeatures)
        val saved = profile != null && NgocSiVoiceProfile.saveProfile(this, profile)
        enrollmentFeatures.clear()
        if (!saved) {
            NgocSiVoiceProfile.setLockEnabled(this, false)
            updateNotification("Chưa lưu được hồ sơ giọng nói · hãy đăng ký lại")
            speakReply("Tôi chưa lưu được hồ sơ giọng nói. Hãy thử đăng ký lại.")
        } else {
            updateNotification("Đã đăng ký giọng nói · xác minh người nói đã bật trên Preview")
            speakReply(
                "Đã đăng ký giọng nói của bạn. Từ giờ tôi sẽ kiểm tra mẫu giọng trước khi xử lý câu nói. " +
                    "Đây là bộ lọc thử nghiệm và có thể nhầm khi môi trường ồn."
            )
        }
    }

    private fun enrollmentPrompt(): String {
        val sampleNumber = (OWNER_VOICE_ENROLLMENT_SAMPLES - enrollmentSamplesRemaining + 1)
            .coerceIn(1, OWNER_VOICE_ENROLLMENT_SAMPLES)
        val phrase = when (sampleNumber) {
            1 -> "Tôi đang đăng ký giọng nói của mình."
            2 -> "AI, hãy mở thư viện nhạc của tôi."
            else -> "AI, hãy giúp tôi điều khiển nhạc."
        }
        return "Mẫu giọng " + sampleNumber + " trên 3. Hãy đọc rõ câu: " + phrase
    }

    private fun cancelConversationTimeout() {
        mainHandler.removeCallbacks(conversationTimeout)
    }

    private fun scheduleConversationTimeout(delayMs: Long = CONVERSATION_IDLE_TIMEOUT_MILLIS) {
        if (stopping || !conversationActive) return
        mainHandler.removeCallbacks(conversationTimeout)
        mainHandler.postDelayed(conversationTimeout, delayMs)
    }

    private val conversationTimeout = object : Runnable {
        override fun run() {
            if (stopping || !conversationActive) return
            if (speaking || answeringQuestion || conversationSpeechStarted) {
                mainHandler.postDelayed(this, CONVERSATION_TIMEOUT_RECHECK_MILLIS)
                return
            }
            conversationActive = false
            conversationSpeechStarted = false
            awaitingCommand = false
            awaitingCommandUntilMillis = 0L
            conversationHistory.clear()
            updateNotification("Phiên trò chuyện đã kết thúc do im lặng · nói “AI” để gọi lại")
        }
    }

    private fun beginListening() {
        if (stopping || answeringQuestion || speechRecognizer == null ||
            recognitionStarting || listening || speaking || enrollmentPromptPending
        ) return
        try {
            val recognizer = speechRecognizer ?: return
            mainHandler.removeCallbacks(partialRecognitionFallback)
            partialRecognitionCandidates = emptyList()
            currentAudioBuffer.reset()
            recognitionStarting = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Finish quiet, short commands sooner while retaining offline recognition.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            recognizer.startListening(intent)
            mainHandler.removeCallbacks(recognitionStartWatchdog)
            mainHandler.postDelayed(recognitionStartWatchdog, RECOGNITION_START_TIMEOUT_MILLIS)
        } catch (_: SecurityException) {
            recognitionStarting = false
            failAndStop("Không có quyền micro. Hãy cấp quyền micro rồi bật lại.")
        } catch (_: Exception) {
            recognitionStarting = false
            scheduleListenRetry(650L)
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
            if (conversationActive) scheduleConversationTimeout()
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
                    ?: return "Hãy nói rõ nội dung cần tìm, ví dụ: AI, tìm YouTube nhạc Trịnh Công Sơn."
            } else null
            val onlineQuery = if (command == NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC) {
                extractNgocSiAiOnlineQuery(originalText)
                    ?: return "Hãy nói nội dung cần tìm, ví dụ: AI, tìm nhạc Trịnh Công Sơn."
            } else null
            val radioQuery = if (command == NgocSiAiLocalCommand.PLAY_RADIO_STATION) {
                extractNgocSiAiRadioStationQuery(originalText)
                    ?: return "Hãy nói tên đài, ví dụ: AI, phát Radio VOV3."
            } else null
            val tvQuery = if (command == NgocSiAiLocalCommand.OPEN_TV_CHANNEL) {
                extractNgocSiAiTvChannelQuery(originalText)
                    ?: return "Hãy nói tên kênh, ví dụ: AI, mở VTV3."
            } else null
            val playlistQuery = when (command) {
                NgocSiAiLocalCommand.CREATE_PLAYLIST ->
                    extractNgocSiAiPlaylistQuery(originalText, create = true)
                        ?: return "Hãy nói tên playlist muốn tạo, ví dụ: AI, tạo playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST ->
                    extractNgocSiAiPlaylistQuery(originalText, create = false)
                        ?: return "Hãy nói tên playlist muốn phát, ví dụ: AI, phát playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST ->
                    extractNgocSiAiAddToPlaylistQuery(originalText)
                        ?: return "Hãy nói rõ playlist đích, ví dụ: AI, thêm bài đang phát vào playlist Nhạc Trịnh."
                NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST ->
                    extractNgocSiAiQueueToPlaylistQuery(originalText)
                        ?: return "Hãy nói rõ playlist đích, ví dụ: AI, thêm hàng đợi vào playlist Nhạc Trịnh."
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

        val connectedPlayer = preferredPlaybackController()
        if (command == NgocSiAiLocalCommand.PLAY_TRACK &&
            (connectedPlayer == null || connectedPlayer.mediaItemCount == 0)
        ) {
            val query = extractNgocSiAiTrackQuery(originalText)
                ?: return "Hãy nói rõ tên bài, ví dụ: “AI, phát bài hát Diễm xưa”."
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
                ?: return "Hãy nói rõ tên bài, ví dụ: “AI, phát bài hát Diễm xưa”."
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
                description = "Hiển thị khi micro đang chờ từ khóa AI"
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
        recognitionStarting = false
        partialRecognitionCandidates = emptyList()
        currentAudioBuffer.reset()
        enrollmentSamplesRemaining = 0
        enrollmentPromptPending = false
        enrollmentFeatures.clear()
        conversationActive = false
        conversationSpeechStarted = false
        conversationHistory.clear()
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
        answeringQuestion = false
        conversationActive = false
        conversationSpeechStarted = false
        conversationHistory.clear()
        aiScope.cancel()
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
        private const val PLAYER_CONNECT_RETRY_MILLIS = 180L
        private const val OWNER_VOICE_ENROLLMENT_SAMPLES = 3
        private const val MAX_ENROLLMENT_AUDIO_FAILURES = 3
        private const val MAX_CAPTURED_AUDIO_BYTES = 160_000
        const val ACTION_START = "com.ngocsi.music.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.ngocsi.music.action.STOP_WAKE_WORD"
        const val ACTION_VOICE_CHANGED = "com.ngocsi.music.action.WAKE_WORD_VOICE_CHANGED"
        const val ACTION_ENROLL_OWNER_VOICE = "com.ngocsi.music.action.ENROLL_OWNER_VOICE"
        const val PREFS_NAME = "ngoc_si_wake_word"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "ngoc_si_wake_word"
        private const val NOTIFICATION_ID = 5871
        private const val STOP_REQUEST_CODE = 5872
        private const val COMMAND_WINDOW_MILLIS = 8_000L
        private const val RECOGNITION_START_TIMEOUT_MILLIS = 8_000L
        private const val PARTIAL_IDLE_FALLBACK_MILLIS = 3_500L
        private const val PARTIAL_AFTER_END_MILLIS = 1_200L
        private const val CONVERSATION_IDLE_TIMEOUT_MILLIS = 30_000L
        private const val CONVERSATION_TIMEOUT_RECHECK_MILLIS = 1_000L
        private const val MAX_CONVERSATION_MESSAGES = 12

        fun start(context: Context) {
            val intent = Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_STOP))
        }

        fun enrollOwnerVoice(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_ENROLL_OWNER_VOICE)
            )
        }

        /** Refreshes the selected TTS voice only when the wake-word service is already running. */
        fun updateVoice(context: Context) {
            context.startService(
                Intent(context, NgocSiWakeWordService::class.java).setAction(ACTION_VOICE_CHANGED)
            )
        }
    }
}

package com.ngocsi.music

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class AiPreviewMessage(val isUser: Boolean, val text: String)

private const val AI_CHAT_PREFS_NAME = "ngoc_si_ai_chat_history"
private const val AI_CHAT_MESSAGES_KEY = "chat_messages_v1"
private const val AI_CHAT_CONVERSATION_KEY = "api_conversation_v1"
private const val AI_VOICE_REPLY_KEY = "voice_replies_enabled_v1"
private const val MAX_SAVED_AI_UI_MESSAGES = 60
private const val MAX_SAVED_AI_MESSAGE_CHARS = 4_000

private fun defaultAiGreeting(isOnlineConfigured: Boolean) = AiPreviewMessage(
    false,
    if (isOnlineConfigured) {
        "Xin chào! NGỌC SĨ AI đang ở chế độ kiểm thử trực tuyến. Chỉ gửi câu hỏi khi bạn nhấn nút gửi."
    } else {
        "Xin chào! Tôi là NGỌC SĨ AI. Đây là bản xem trước giao diện; AI trực tuyến chưa được cấu hình."
    }
)

private fun loadAiPreviewMessages(
    preferences: SharedPreferences,
    isOnlineConfigured: Boolean
): List<AiPreviewMessage> {
    val stored = preferences.getString(AI_CHAT_MESSAGES_KEY, null)
        ?: return listOf(defaultAiGreeting(isOnlineConfigured))
    val decoded = runCatching {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").take(MAX_SAVED_AI_MESSAGE_CHARS)
                if (text.isNotBlank()) add(AiPreviewMessage(item.optBoolean("isUser"), text))
            }
        }.takeLast(MAX_SAVED_AI_UI_MESSAGES)
    }.getOrDefault(emptyList())
    return decoded.ifEmpty { listOf(defaultAiGreeting(isOnlineConfigured)) }
}

private fun saveAiPreviewMessages(
    preferences: SharedPreferences,
    messages: List<AiPreviewMessage>
) {
    val array = JSONArray()
    messages.takeLast(MAX_SAVED_AI_UI_MESSAGES).forEach { message ->
        array.put(
            JSONObject()
                .put("isUser", message.isUser)
                .put("text", message.text.take(MAX_SAVED_AI_MESSAGE_CHARS))
        )
    }
    preferences.edit().putString(AI_CHAT_MESSAGES_KEY, array.toString()).apply()
}

private fun loadAiApiConversation(preferences: SharedPreferences): List<NgocSiAiMessage> {
    val stored = preferences.getString(AI_CHAT_CONVERSATION_KEY, null) ?: return emptyList()
    val decoded = runCatching {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val content = item.optString("content").trim()
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    add(NgocSiAiMessage(role, content.take(MAX_SAVED_AI_MESSAGE_CHARS)))
                }
            }
        }
    }.getOrDefault(emptyList())
    return restoreCompletedNgocSiAiConversation(decoded)
}

private fun saveAiApiConversation(
    preferences: SharedPreferences,
    messages: List<NgocSiAiMessage>
) {
    val bounded = messages
        .takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        .dropWhile { it.role == "assistant" }
    val array = JSONArray()
    bounded.forEach { message ->
        array.put(
            JSONObject()
                .put("role", message.role)
                .put("content", message.content.take(MAX_SAVED_AI_MESSAGE_CHARS))
        )
    }
    preferences.edit().putString(AI_CHAT_CONVERSATION_KEY, array.toString()).apply()
}

@UnstableApi
class NgocSiAiActivity : ComponentActivity() {
    private var recognizedSpeech by mutableStateOf("")
    private var recognizedSpeechShouldSend by mutableStateOf(false)
    private var wakeWordListening by mutableStateOf(false)
    private var musicController: MediaController? = null
    private var deviceQueueLoadStarted = false
    private var responseTts: TextToSpeech? = null
    private var responseTtsInitialized = false
    private var responseTtsReady = false
    private var pendingVoiceReply: String? = null
    private var pendingVoiceReplyForce = false
    private var voiceRepliesEnabled by mutableStateOf(true)
    private val wakeWordStatusHandler = Handler(Looper.getMainLooper())
    private val wakeWordStatusChecker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val enabled = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
            val wasListening = wakeWordListening
            wakeWordListening = enabled
            if (enabled) {
                wakeWordStatusHandler.postDelayed(this, 1_000L)
            } else if (wasListening) {
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Nghe từ khóa đã tự tắt. Máy có thể chưa có gói nhận dạng tiếng Việt offline; hãy dùng nút micro để ra lệnh.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val phrase = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (result.resultCode == RESULT_OK && phrase.isNotBlank()) {
                // Route all recognized speech through the same allowlisted command/chat handler.
                // Local playback commands work without Firebase; other speech gets an explicit
                // explanation if online AI has not been configured.
                recognizedSpeech = phrase
                recognizedSpeechShouldSend = true
            } else {
                val audioPermissionGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val message = when {
                    !audioPermissionGranted ->
                        "Chưa có quyền micro. Hãy vào Cài đặt > Ứng dụng > NGỌC SĨ AI Preview > Quyền > Micro và chọn Cho phép."
                    result.resultCode == RESULT_CANCELED ->
                        "Chưa nhận được câu nói. Hãy nhấn micro, nói rõ sau tiếng bíp và thử lại. Nếu không mở được nhận dạng giọng nói, hãy cập nhật ứng dụng Google và dịch vụ Speech Services."
                    else ->
                        "Không nhận dạng được giọng nói. Hãy kiểm tra quyền micro, kết nối mạng và thử lại."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

    private val voiceInputPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceInput() else Toast.makeText(
                this,
                "Cần quyền micro để nhận lệnh giọng nói. Hãy cấp quyền rồi nhấn micro lần nữa.",
                Toast.LENGTH_LONG
            ).show()
        }

    private val musicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadDeviceMusicIntoQueue()
            } else {
                Toast.makeText(
                    this,
                    "Chưa cấp quyền đọc nhạc trên thiết bị. Chat AI vẫn dùng được; lệnh phát nhạc cần quyền này.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val wakeWordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                try {
                    NgocSiWakeWordService.start(this)
                    wakeWordListening = true
                    monitorWakeWordStatus()
                } catch (_: Exception) {
                    Toast.makeText(this, "Không thể bật nghe từ khóa. Hãy thử lại.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Cần quyền micro để nghe từ khóa Ngọc Sĩ.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onResume() {
        super.onResume()
        wakeWordListening = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
        if (wakeWordListening) monitorWakeWordStatus()
    }

    private fun monitorWakeWordStatus() {
        wakeWordStatusHandler.removeCallbacks(wakeWordStatusChecker)
        wakeWordStatusHandler.postDelayed(wakeWordStatusChecker, 1_000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        responseTts = TextToSpeech(this) { status ->
            responseTtsInitialized = true
            if (status == TextToSpeech.SUCCESS) {
                val tts = responseTts
                if (tts != null) {
                    val vietnameseResult = runCatching {
                        tts.setLanguage(Locale("vi", "VN"))
                    }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                    responseTtsReady = vietnameseResult >= TextToSpeech.LANG_AVAILABLE
                    if (!responseTtsReady) {
                        val fallbackResult = runCatching {
                            tts.setLanguage(Locale.getDefault())
                        }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                        responseTtsReady = fallbackResult >= TextToSpeech.LANG_AVAILABLE
                    }
                }
            }
            val queuedReply = pendingVoiceReply
            if (queuedReply != null) {
                val forceQueuedReply = pendingVoiceReplyForce
                pendingVoiceReply = null
                pendingVoiceReplyForce = false
                if (responseTtsReady) {
                    speakVoiceReply(queuedReply, force = forceQueuedReply)
                } else {
                    Toast.makeText(
                        this,
                        "Chưa có giọng đọc khả dụng. Hãy vào cài đặt Văn bản thành giọng nói và tải dữ liệu tiếng Việt.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        val aiClient = NgocSiAiRemoteClient(applicationContext)
        val aiChatPreferences = getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE)
        wakeWordListening = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
        voiceRepliesEnabled = getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE)
            .getBoolean(AI_VOICE_REPLY_KEY, true)
        connectMusicController()
        setContent {
            NgocSiAiPreviewScreen(
                isOnlineConfigured = aiClient.isConfigured,
                chatPreferences = aiChatPreferences,
                onSendOnline = { history -> aiClient.send(history) },
                onLocalCommand = { command -> runLocalCommand(command) },
                onVoiceReply = { message -> speakVoiceReply(message) },
                onSpeakMessage = { message -> speakVoiceReply(message, force = true) },
                voiceRepliesEnabled = voiceRepliesEnabled,
                onToggleVoiceReplies = { toggleVoiceReplies() },
                recognizedSpeech = recognizedSpeech,
                recognizedSpeechShouldSend = recognizedSpeechShouldSend,
                onSpeechConsumed = {
                    recognizedSpeech = ""
                    recognizedSpeechShouldSend = false
                },
                onBack = { finish() },
                onVoice = { startVoiceInput() },
                isWakeWordListening = wakeWordListening,
                onToggleWakeWord = { toggleWakeWordListening() }
            )
        }
        requestMusicPermissionIfNeeded()
    }

    private fun requestMusicPermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadDeviceMusicIntoQueue()
        } else {
            musicPermissionLauncher.launch(permission)
        }
    }

    /**
     * Loads a queue only into this isolated Preview app's own Media3 service.
     * It never modifies or controls the production NGỌC SĨ MUSIC package.
     */
    private fun loadDeviceMusicIntoQueue() {
        if (deviceQueueLoadStarted) return
        val controller = musicController ?: return
        if (controller.mediaItemCount > 0) return

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        deviceQueueLoadStarted = true
        lifecycleScope.launch {
            try {
                val localItems = withContext(Dispatchers.IO) { queryDeviceMusicItems() }
                if (isFinishing || isDestroyed) {
                    deviceQueueLoadStarted = false
                    return@launch
                }

                val activeController = musicController
                if (activeController == null || activeController.mediaItemCount > 0) {
                    deviceQueueLoadStarted = false
                    return@launch
                }
                if (localItems.isEmpty()) {
                    deviceQueueLoadStarted = false
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Không tìm thấy tệp nhạc cục bộ. Bạn vẫn có thể dùng chat nếu AI trực tuyến đã cấu hình.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                activeController.setMediaItems(localItems)
                activeController.prepare()
                deviceQueueLoadStarted = false
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Đã nạp ${localItems.size} bài nhạc trên điện thoại. Hãy nói “Phát nhạc” để bắt đầu.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (cancelled: CancellationException) {
                deviceQueueLoadStarted = false
                throw cancelled
            } catch (_: SecurityException) {
                deviceQueueLoadStarted = false
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Android chưa cho phép đọc thư viện nhạc. Hãy cấp quyền rồi mở lại NGỌC SĨ AI.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                deviceQueueLoadStarted = false
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Không tải được thư viện nhạc trên máy. Hãy thử lại.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDeviceMusicItems(): List<MediaItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val items = mutableListOf<MediaItem>()

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?: "Không có tên"
                val artist = cursor.getString(artistColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?: "Nghệ sĩ không rõ"
                val album = cursor.getString(albumColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    .orEmpty()
                val uri = ContentUris.withAppendedId(collection, id)
                val metadata = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
                items += MediaItem.Builder()
                    .setMediaId(uri.toString())
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
        }
        return items
    }

    private fun connectMusicController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { connectedController ->
                    if (isFinishing || isDestroyed) {
                        connectedController.release()
                    } else {
                        musicController = connectedController
                        loadDeviceMusicIntoQueue()
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runLocalCommand(command: NgocSiAiLocalCommand): String {
        val player = musicController
            ?: return "Trình phát đang khởi động hoặc chưa kết nối. Hãy thử lại sau một lát."
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
                return "Chưa có quyền đọc nhạc. Hãy cấp quyền Âm nhạc và âm thanh cho NGỌC SĨ AI Preview trong Cài đặt ứng dụng."
            }
            if (!deviceQueueLoadStarted) {
                loadDeviceMusicIntoQueue()
                return "Đang kiểm tra thư viện nhạc trên điện thoại. Hãy thử lại sau khi thông báo nạp nhạc xuất hiện."
            }
            return "Thư viện nhạc đang được nạp. Hãy đợi một chút rồi thử lại."
        }

        return when (command) {
            NgocSiAiLocalCommand.CURRENT_TRACK -> {
                val item = player.currentMediaItem
                val title = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Chưa rõ tên bài hát"
                val artist = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
                val status = when {
                    player.isPlaying -> "Đang phát"
                    player.playbackState == androidx.media3.common.Player.STATE_BUFFERING &&
                        player.playWhenReady -> "Đang tải"
                    player.playbackState == androidx.media3.common.Player.STATE_ENDED -> "Đã phát hết"
                    else -> "Đang tạm dừng"
                }
                if (artist == null) "$status: $title" else "$status: $title — $artist"
            }
            NgocSiAiLocalCommand.PLAY -> {
                player.play()
                "Đã gửi lệnh phát nhạc đến trình phát."
            }
            NgocSiAiLocalCommand.PAUSE -> {
                player.pause()
                "Đã gửi lệnh tạm dừng nhạc."
            }
            NgocSiAiLocalCommand.NEXT -> {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    "Đang chuyển sang bài tiếp theo."
                } else {
                    "Không có bài tiếp theo trong hàng đợi hiện tại."
                }
            }
            NgocSiAiLocalCommand.PREVIOUS -> {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                    "Đang chuyển về bài trước."
                } else {
                    "Không có bài trước trong hàng đợi hiện tại."
                }
            }
            NgocSiAiLocalCommand.SHUFFLE_ON -> {
                player.shuffleModeEnabled = true
                "Đã bật phát ngẫu nhiên cho hàng đợi hiện tại."
            }
            NgocSiAiLocalCommand.SHUFFLE_OFF -> {
                player.shuffleModeEnabled = false
                "Đã tắt phát ngẫu nhiên."
            }
            NgocSiAiLocalCommand.REPEAT_OFF -> {
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                "Đã tắt chế độ lặp."
            }
            NgocSiAiLocalCommand.REPEAT_ALL -> {
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                "Đã bật lặp toàn bộ hàng đợi."
            }
            NgocSiAiLocalCommand.REPEAT_ONE -> {
                player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                "Đã bật lặp bài hiện tại."
            }
        }
    }

    private fun speakVoiceReply(message: String, force: Boolean = false) {
        if (!force && !voiceRepliesEnabled) return
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return

        val tts = responseTts
        if (!responseTtsInitialized || tts == null) {
            // TextToSpeech initializes asynchronously. Queue the response instead of losing it.
            pendingVoiceReply = cleanMessage
            pendingVoiceReplyForce = force
            return
        }
        if (!responseTtsReady) {
            Toast.makeText(
                this,
                "Chưa có giọng đọc khả dụng. Hãy vào cài đặt Văn bản thành giọng nói và tải dữ liệu tiếng Việt.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val result = runCatching {
            tts.speak(
                cleanMessage,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ngoc_si_activity_reply_${System.currentTimeMillis()}"
            )
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(this, "Không phát được giọng nói. Hãy kiểm tra cài đặt Văn bản thành giọng nói.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        wakeWordStatusHandler.removeCallbacksAndMessages(null)
        musicController?.release()
        musicController = null
        runCatching { responseTts?.stop() }
        runCatching { responseTts?.shutdown() }
        responseTts = null
        responseTtsInitialized = false
        responseTtsReady = false
        pendingVoiceReply = null
        super.onDestroy()
    }

    private fun toggleVoiceReplies() {
        voiceRepliesEnabled = !voiceRepliesEnabled
        getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(AI_VOICE_REPLY_KEY, voiceRepliesEnabled)
            .apply()
        if (voiceRepliesEnabled) {
            speakVoiceReply("Đã bật đọc câu trả lời bằng giọng nói.")
        } else {
            runCatching { responseTts?.stop() }
            Toast.makeText(this, "Đã tắt đọc câu trả lời bằng giọng nói.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleWakeWordListening() {
        if (wakeWordListening) {
            NgocSiWakeWordService.stop(this)
            wakeWordListening = false
            wakeWordStatusHandler.removeCallbacks(wakeWordStatusChecker)
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                NgocSiWakeWordService.start(this)
                wakeWordListening = true
                monitorWakeWordStatus()
            } catch (_: Exception) {
                Toast.makeText(this, "Không thể bật nghe từ khóa. Hãy thử lại.", Toast.LENGTH_LONG).show()
            }
        } else {
            wakeWordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            voiceInputPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói nội dung bạn muốn nhập")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Điện thoại chưa có dịch vụ nhận dạng giọng nói. Hãy cập nhật ứng dụng Google hoặc Speech Services by Google rồi thử lại.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Thiếu quyền micro. Hãy cấp quyền micro cho NGỌC SĨ AI Preview.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Không thể mở nhận dạng giọng nói. Hãy kiểm tra quyền micro và cập nhật dịch vụ Google.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun NgocSiAiPreviewScreen(
    isOnlineConfigured: Boolean,
    chatPreferences: SharedPreferences,
    onSendOnline: suspend (List<NgocSiAiMessage>) -> NgocSiAiReply,
    onLocalCommand: (NgocSiAiLocalCommand) -> String,
    onVoiceReply: (String) -> Unit,
    onSpeakMessage: (String) -> Unit,
    voiceRepliesEnabled: Boolean,
    onToggleVoiceReplies: () -> Unit,
    recognizedSpeech: String,
    recognizedSpeechShouldSend: Boolean,
    onSpeechConsumed: () -> Unit,
    onBack: () -> Unit,
    onVoice: () -> Unit,
    isWakeWordListening: Boolean,
    onToggleWakeWord: () -> Unit
) {
    val messages = remember {
        mutableStateListOf<AiPreviewMessage>().apply {
            addAll(loadAiPreviewMessages(chatPreferences, isOnlineConfigured))
        }
    }
    var input by remember { mutableStateOf("") }
    val conversation = remember {
        mutableStateListOf<NgocSiAiMessage>().apply {
            addAll(loadAiApiConversation(chatPreferences))
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var quotaNote by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val messageListState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun persistChatState() {
        saveAiPreviewMessages(chatPreferences, messages)
        saveAiApiConversation(chatPreferences, conversation)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            messageListState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || isSending) return
        messages.add(AiPreviewMessage(true, clean))
        if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
        input = ""

        val localCommand = classifyNgocSiAiLocalCommand(clean)
        if (localCommand != null) {
            val commandReply = onLocalCommand(localCommand)
            messages.add(AiPreviewMessage(false, commandReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            persistChatState()
            onVoiceReply(commandReply)
            return
        }

        if (!isOnlineConfigured) {
            val offlineReply =
                "Tôi đã nhận lệnh. Bản này hiện chỉ thực hiện được các lệnh điều khiển nhạc đã hỗ trợ; AI trả lời tự do chưa kết nối máy chủ Firebase/Gemini. Tin nhắn không được gửi ra ngoài và không phát sinh phí AI."
            messages.add(AiPreviewMessage(false, offlineReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            persistChatState()
            onVoiceReply(offlineReply)
            return
        }

        val userTurn = NgocSiAiMessage(role = "user", content = clean)
        conversation.add(userTurn)
        while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
            conversation.removeAt(0)
            if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
        }
        // Persist the visible user message immediately, but only persist API context
        // after a successful reply so interrupted requests cannot create broken turns.
        saveAiPreviewMessages(chatPreferences, messages)
        var history = conversation.takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        if (history.firstOrNull()?.role == "assistant") history = history.drop(1)
        isSending = true
        coroutineScope.launch {
            try {
                val reply = onSendOnline(history.toList())
                conversation.add(NgocSiAiMessage(role = "assistant", content = reply.answer))
                messages.add(AiPreviewMessage(false, reply.answer))
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
                onVoiceReply(reply.answer)
                val userRemaining = reply.remainingToday?.let { "Còn $it lượt/tài khoản hôm nay" }
                val globalRemaining = reply.remainingGlobalToday?.let { "Còn $it lượt toàn hệ thống hôm nay" }
                quotaNote = listOfNotNull(userRemaining, globalRemaining).joinToString(" • ")
            } catch (cancelled: CancellationException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                persistChatState()
                throw cancelled
            } catch (error: NgocSiAiRemoteException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                val errorReply = error.message ?: "Không thể kết nối NGỌC SĨ AI. Vui lòng thử lại."
                messages.add(AiPreviewMessage(false, errorReply))
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
                onVoiceReply(errorReply)
            } catch (_: Exception) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                val errorReply = "Có lỗi khi gửi yêu cầu AI. Hãy kiểm tra kết nối và cấu hình Firebase."
                messages.add(AiPreviewMessage(false, errorReply))
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
                onVoiceReply(errorReply)
            } finally {
                isSending = false
            }
        }
    }

    LaunchedEffect(recognizedSpeech, recognizedSpeechShouldSend) {
        if (recognizedSpeech.isNotBlank()) {
            val phrase = recognizedSpeech.trim()
            val shouldSubmitRecognizedSpeech = recognizedSpeechShouldSend
            onSpeechConsumed()
            if (shouldSubmitRecognizedSpeech) {
                // Tap-to-talk is an explicit user action: execute only allowlisted local
                // playback commands, or send the question online when configured.
                sendMessage(phrase)
            } else {
                input = listOf(input.trim(), phrase)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .take(2_000)
            }
        }
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Xóa lịch sử chat?") },
            text = {
                Text(
                    "Lịch sử chat được lưu trên điện thoại này. Xóa sẽ gỡ các tin nhắn đã lưu và bắt đầu cuộc trò chuyện mới."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        messages.clear()
                        messages.add(defaultAiGreeting(isOnlineConfigured))
                        conversation.clear()
                        input = ""
                        quotaNote = ""
                        persistChatState()
                        showClearConfirm = false
                    }
                ) {
                    Text("Xóa", color = Color(0xFFFFA0A0))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearConfirm = false }) {
                    Text("Hủy", color = Color(0xFFD9DCE5))
                }
            },
            containerColor = Color(0xFF171B24),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFD9DCE5)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090D))
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1018))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clickable(onClick = onBack),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1A1E2A)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("‹", color = Color.White, fontSize = 30.sp)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "NGỌC SĨ AI",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "TRỢ LÝ CỦA BẠN",
                    color = Color(0xFF8DEEFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }
            Surface(
                modifier = Modifier.clickable(enabled = !isSending) {
                    showClearConfirm = true
                },
                shape = RoundedCornerShape(50),
                color = Color(0xFF242033),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A334A))
            ) {
                Text(
                    "XÓA",
                    color = Color(0xFFFFB0B0),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                modifier = Modifier.clickable(onClick = onToggleVoiceReplies),
                shape = RoundedCornerShape(50),
                color = if (voiceRepliesEnabled) Color(0xFF123329) else Color(0xFF242033),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (voiceRepliesEnabled) Color(0xFF39C98A) else Color(0xFF3A334A)
                )
            ) {
                Text(
                    if (voiceRepliesEnabled) "🔊" else "🔇",
                    color = if (voiceRepliesEnabled) Color(0xFF71E6B2) else Color(0xFFB9C4D6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF242033)
            ) {
                Text(
                    if (isOnlineConfigured) "AI TEST" else "PREVIEW",
                    color = Color(0xFFC7B5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(onClick = onToggleWakeWord),
            shape = RoundedCornerShape(14.dp),
            color = if (isWakeWordListening) Color(0xFF123329) else Color(0xFF171421),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isWakeWordListening) Color(0xFF39C98A) else Color(0xFF39304F)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isWakeWordListening) "🎙" else "🎤", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isWakeWordListening) "ĐANG NGHE: “NGỌC SĨ”" else "BẬT NGHE TỪ KHÓA “NGỌC SĨ”",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isWakeWordListening) "Nhận lệnh offline · tắt bằng cách chạm vào đây hoặc thông báo" else "Bật một lần, sau đó nói “Ngọc Sĩ, chuyển bài”",
                        color = Color(0xFFB9C4D6),
                        fontSize = 10.sp
                    )
                }
                Text(if (isWakeWordListening) "TẮT" else "BẬT", color = if (isWakeWordListening) Color(0xFF71E6B2) else Color(0xFF8DEEFF), fontWeight = FontWeight.Black, fontSize = 10.sp)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF171421),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF39304F))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✦", color = Color(0xFFB99AFF), fontSize = 19.sp)
                Spacer(Modifier.width(9.dp))
                Text(
                    if (isOnlineConfigured) {
                        "Chế độ trực tuyến • Câu nói qua micro sẽ gửi tới máy chủ AI sau khi nhận dạng. Không đọc thông tin nhạy cảm."
                    } else {
                        "Bản xem trước • Chưa cấu hình Firebase • Không phát sinh phí AI"
                    },
                    color = Color(0xFFC7C1D8),
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }

        LazyColumn(
            state = messageListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (!message.isUser) {
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = Color(0xFF29203F)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✦", color = Color(0xFFB99AFF), fontSize = 17.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.fillMaxWidth(0.84f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (message.isUser) 18.dp else 5.dp,
                                bottomEnd = if (message.isUser) 5.dp else 18.dp
                            ),
                            color = if (message.isUser) Color(0xFF25213A) else Color(0xFF141821),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (message.isUser) Color(0xFF44376B) else Color(0xFF252B37)
                            )
                        ) {
                            Text(
                                message.text,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                                color = if (message.isUser) Color(0xFFF0ECFF) else Color(0xFFD9DCE5),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                        if (!message.isUser) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    modifier = Modifier.clickable { onSpeakMessage(message.text) },
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFF171B25),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                                ) {
                                    Text(
                                        "🔊 ĐỌC",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = Color(0xFFB9C4D6),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1016))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "GỢI Ý THỬ",
                color = Color(0xFF777F91),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val suggestions = (if (isOnlineConfigured) {
                    listOf("Bạn có thể giúp gì?", "Giải thích bằng tiếng Việt dễ hiểu", "Tóm tắt nội dung này", "Lên kế hoạch cho tôi", "Viết nội dung giúp tôi")
                } else emptyList()) + listOf(
                    "Phát nhạc", "Tạm dừng nhạc", "Bài tiếp theo", "Bài trước",
                    "Đang phát bài gì?", "Phát ngẫu nhiên", "Tắt phát ngẫu nhiên",
                    "Lặp hàng đợi", "Lặp một bài", "Tắt chế độ lặp"
                )
                suggestions.forEach { suggestion ->
                    Surface(
                        modifier = Modifier.clickable { input = suggestion },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF171B25),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = Color(0xFFD9D6E8),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF171B24))
                    .padding(start = 13.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(2_000) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 13.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF8DEEFF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage(input) }),
                    singleLine = false,
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.isBlank()) {
                                Text(
                                    "Nhập tin nhắn...",
                                    color = Color(0xFF777F91),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onVoice),
                    shape = CircleShape,
                    color = Color(0xFF242033)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎙", fontSize = 17.sp)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = !isSending) { sendMessage(input) },
                    shape = CircleShape,
                    color = if (isSending) Color(0xFF515661) else Color(0xFF8DEEFF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (isSending) "…" else "↑", color = Color(0xFF061018), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    isSending -> "Đang gửi yêu cầu đến máy chủ NGỌC SĨ AI…"
                    isOnlineConfigured -> quotaNote.ifBlank { "Nhấn GỬI để hỏi, hoặc dùng micro để gửi câu hỏi tự động sau khi nhận dạng." }
                    else -> "Bản xem trước chạy cục bộ; lịch sử được lưu trên điện thoại."
                },
                color = Color(0xFF666D7D),
                fontSize = 9.sp
            )
        }
    }
}

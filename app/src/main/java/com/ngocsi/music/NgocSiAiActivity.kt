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
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import java.text.Normalizer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

internal fun localConversationFallback(
    userText: String,
    previousAssistantReply: String? = null
): String? {
    val normalized = Normalizer.normalize(userText.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun matchesAny(vararg phrases: String): Boolean =
        phrases.any { normalized == it || normalized.startsWith("$it ") }

    // Quick everyday exchanges stay on-device and never consume Gemini requests.
    when {
        normalized in setOf(
            "chao", "xin chao", "chao ban", "xin chao ban", "chao ai",
            "chao buoi sang", "xin chao buoi sang", "chao buoi chieu",
            "xin chao buoi chieu", "chao buoi toi", "xin chao buoi toi",
            "good morning", "good evening", "hello", "hi"
        ) ->
            return "Chào bạn. Mình đang nghe đây. Bạn muốn trò chuyện hay cần mình điều khiển nhạc trong ứng dụng?"

        normalized in setOf(
            "cam on", "cam on ban", "cam on nhe", "cam on ban nhieu",
            "cam on rat nhieu", "minh cam on", "toi cam on ban",
            "thanks", "thank you"
        ) ->
            return "Không có gì đâu. Bạn cứ nói điều bạn cần, mình sẽ hỗ trợ trong khả năng của mình."

        normalized in setOf("xin loi", "minh xin loi", "toi xin loi", "sorry") ->
            return "Không sao đâu. Mình vẫn ở đây để cùng bạn xử lý việc cần làm."

        normalized in setOf("ok", "oke", "duoc", "duoc roi", "roi", "hieu roi", "hay do", "tot") ->
            return "Ừ, mình theo ý bạn. Khi cần đi tiếp, cứ nói tự nhiên nhé."

        normalized in setOf(
            "ban lam duoc gi", "ban co the lam gi", "ai lam duoc gi",
            "ngoc si ai lam duoc gi", "ban ho tro duoc gi"
        ) ->
            return "Mình có thể xử lý các lệnh được hỗ trợ như phát, tạm dừng hoặc dừng hẳn nhạc, chuyển bài, mở YouTube, Radio, TV và Google Drive. Mình cũng có thể trò chuyện, giải thích và cùng bạn nghĩ ý tưởng khi AI trực tuyến còn hạn mức."

        normalized in setOf("ban ten gi", "ten ban la gi", "ban la ai", "ban la ai vay") ->
            return "Mình là NGỌC SĨ AI, trợ lý tích hợp trong NGỌC SĨ MUSIC."

        normalized in setOf("ban co phai nguoi that khong", "ban la nguoi that a") ->
            return "Mình là AI, không phải con người. Mình có thể trò chuyện và hỗ trợ bạn, nhưng sẽ nói rõ khi không biết hoặc không thể thực hiện việc gì."

        normalized in setOf(
            "ban khoe khong", "ban co khoe khong", "ban co on khong",
            "hom nay ban the nao", "hom nay ban ra sao", "ban the nao",
            "ban dang lam gi", "co ai o do khong"
        ) ->
            return "Mình sẵn sàng hỗ trợ bạn đây. Hôm nay bạn muốn nghe nhạc hay cùng mình giải quyết việc gì?"

        matchesAny(
            "toi buon", "minh buon", "toi thay buon", "minh thay buon",
            "toi co don", "minh co don", "toi thay co don",
            "toi ap luc", "minh ap luc", "toi that vong", "minh that vong",
            "toi chan nan", "minh chan nan", "hom nay khong vui"
        ) ->
            return "Nghe như hôm nay bạn đang không ổn. Mình ở đây để lắng nghe, không phán xét. Điều gì đang khiến bạn nặng lòng nhất?"

        matchesAny("toi met", "minh met", "met qua", "toi kiem suc", "minh kien suc") ->
            return "Có vẻ bạn đang khá mệt. Mình không muốn vội đưa lời khuyên; bạn muốn kể điều gì làm mình mệt nhất, hay muốn nghỉ một chút rồi tính tiếp?"

        matchesAny("toi vui", "minh vui", "vui qua", "hom nay vui", "toi dang vui") ->
            return "Nghe vui đấy. Có chuyện gì tốt vừa xảy ra mà bạn muốn kể mình nghe không?"

        normalized in setOf(
            "toi muon tam su", "minh muon tam su", "hay tam su voi toi",
            "noi chuyen voi toi", "nghe toi noi", "toi muon ke chuyen",
            "minh muon ke chuyen", "toi can nguoi lang nghe"
        ) ->
            return "Được chứ, bạn cứ kể theo cách tự nhiên nhất. Mình sẽ nghe trước; bạn muốn mình chỉ lắng nghe hay cùng tìm cách giải quyết?"

        matchesAny("ban co hieu toi", "ban hieu toi", "ban co lang nghe toi") ->
            return "Mình sẽ cố gắng hiểu từ điều bạn kể và bám sát ngữ cảnh. Nếu mình hiểu lệch, bạn cứ sửa thẳng cho mình nhé."

        matchesAny("ban co the suy luan", "ban co biet suy luan", "ban co thong minh") ->
            return "Khi Gemini trực tuyến còn hoạt động, mình có thể phân tích ngữ cảnh, so sánh các khả năng và đề xuất cách làm. Khi hết hạn mức, chế độ cục bộ chỉ xử lý lệnh và một số hội thoại quen thuộc; mình sẽ không giả vờ đã phân tích được câu hỏi phức tạp."

        matchesAny("tai sao ai het han muc", "sao ai het han muc", "tai sao bi loi 429", "sao chat bi 429") ||
            (normalized.contains("han muc") && (normalized.contains("gemini") || normalized.contains("ai"))) ->
            return "Hạn mức do Google áp dụng theo model và dự án, không phải do lệnh nhạc trên điện thoại. Mình không bật thanh toán và sẽ không gửi lặp yêu cầu khi máy chủ báo hết hạn mức. Các lệnh điều khiển cục bộ vẫn dùng được."

        normalized in setOf("tiep tuc", "tiep di", "lam tiep", "noi tiep", "ke tiep", "noi tiep di") -> {
            val previous = previousAssistantReply?.trim().orEmpty()
            return if (previous.isNotBlank()) {
                "Mình theo mạch vừa trao đổi nhé. Ý gần nhất là: “" + previous.take(220) + "”. Khi chat trực tuyến đang bị giới hạn, mình không muốn tự đoán thêm rồi làm sai ý bạn; bạn nói phần muốn đào sâu, mình sẽ bám đúng phần đó."
            } else {
                "Mình sẵn sàng tiếp tục. Bạn nhắc ngắn gọn chủ đề đang làm dở để mình không đoán sai ý nhé."
            }
        }
    }

    val asksForAppDirection =
        (normalized.contains("chuyen nghiep") || normalized.contains("pro hon") ||
            normalized.contains("nang cap") || normalized.contains("cai thien")) &&
        (normalized.contains("app") || normalized.contains("ung dung")) &&
        (normalized.contains("bat dau") || normalized.contains("tu dau") ||
            normalized.contains("nen lam gi") || normalized.contains("nen uu tien"))
    if (!asksForAppDirection) return null

    return "Mình đề xuất bắt đầu bằng một đợt kiểm tra chất lượng trước khi thêm tính năng mới. " +
        "Ưu tiên theo thứ tự: (1) sửa lỗi phát nhạc và lệnh giọng nói để câu nói tự nhiên được hiểu đúng; " +
        "(2) làm giao diện gọn, dễ nhìn và thống nhất; (3) kiểm tra YouTube, phát nền và màn hình khóa; " +
        "(4) kiểm tra Radio, Google Drive và danh sách phát; (5) sau cùng mới bổ sung tính năng mới. " +
        "Bước đầu tiên hợp lý nhất là kiểm thử từng lệnh AI bằng câu nói thực tế, ghi lại câu nào không hiểu rồi sửa bộ nhận diện. " +
        "Mình chưa thay đổi bản PRO ổn định."
}
private fun defaultAiGreeting(isOnlineConfigured: Boolean) = AiPreviewMessage(
    false,
    if (isOnlineConfigured) {
        "Chào bạn, mình là Ngọc Sĩ AI. Cứ nói chuyện tự nhiên với mình nhé."
    } else {
        "Chào bạn, mình là Ngọc Sĩ AI. Mình vẫn nhận lệnh điều khiển ứng dụng; trò chuyện tự do cần kết nối AI."
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
    // Show live partial transcription so voice chat visibly responds while the user speaks.
    private var partialSpeechTranscript by mutableStateOf("")
    private var wakeWordListening by mutableStateOf(false)
    private var voiceRecognizer: SpeechRecognizer? = null
    private var voiceRecognitionInProgress by mutableStateOf(false)
    private var voiceRetryCount = 0
    private var activeVoiceIntent: Intent? = null
    private val voiceRetryHandler = Handler(Looper.getMainLooper())
    private var musicController: MediaController? = null
    // The Preview package is isolated from production; this optional controller lets
    // voice commands stop the active stable app player without modifying its APK.
    private var productionMusicController: MediaController? = null
    private var productionMusicControllerConnecting = false
    private var deviceQueueLoadStarted = false
    // Preserve the explicit "play" request if the device library is still loading.
    private var pendingPlayAfterQueueLoad = false
    private var pendingQueueStartIndexAfterLoad: Int? = null
    private var pendingTrackQueryAfterQueueLoad: String? = null
    private var responseTts: TextToSpeech? = null
    private var responseTtsInitialized = false
    private var responseTtsReady = false
    private var pendingVoiceReply: String? = null
    private var pendingVoiceReplyForce = false
    private var voiceRepliesEnabled by mutableStateOf(true)
    private var availableTtsVoices by mutableStateOf<List<NgocSiAiVoiceOption>>(emptyList())
    private var selectedTtsVoiceName by mutableStateOf("")
    private val wakeWordStatusHandler = Handler(Looper.getMainLooper())
    // Some Android speech services start successfully but never deliver partial/final text.
    // Recover by opening the system speech UI instead of leaving the microphone silently stuck.
    private val speechFallbackHandler = Handler(Looper.getMainLooper())
    private var speechFallbackUsed = false
    private val speechNoTranscriptWatchdog = object : Runnable {
        override fun run() {
            if (!voiceRecognitionInProgress || isFinishing || isDestroyed || speechFallbackUsed) return
            if (partialSpeechTranscript.isNotBlank()) return
            speechFallbackUsed = true
            val intent = activeVoiceIntent
            runCatching { voiceRecognizer?.cancel() }
            runCatching { voiceRecognizer?.destroy() }
            voiceRecognizer = null
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            if (intent != null) {
                runCatching { speechLauncher.launch(intent) }
                    .onFailure {
                        Toast.makeText(
                            this@NgocSiAiActivity,
                            "Dịch vụ nghe chưa trả về chữ. Hãy cập nhật ứng dụng Google hoặc Speech Services rồi thử lại.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }
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

    private val inAppSpeechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    voiceRecognitionInProgress = true
                    partialSpeechTranscript = ""
                }
            }
        }

        override fun onBeginningOfSpeech() {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    partialSpeechTranscript = ""
                    // Give the direct recognition service a chance, then fall back if it
                    // produces no transcript at all. This watchdog never submits speech itself.
                    speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                    speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
                }
            }
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    voiceRecognitionInProgress = false
                    partialSpeechTranscript = ""
                    return@runOnUiThread
                }
                speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                partialSpeechTranscript = ""
                val canRetry = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (canRetry && voiceRetryCount < 1 && activeVoiceIntent != null) {
                    voiceRetryCount += 1
                    voiceRetryHandler.postDelayed({
                        if (isFinishing || isDestroyed) {
                            voiceRecognitionInProgress = false
                            return@postDelayed
                        }
                        val recognizer = voiceRecognizer
                        val retryIntent = activeVoiceIntent
                        if (recognizer != null && retryIntent != null) {
                            runCatching { recognizer.startListening(retryIntent) }
                                .onSuccess {
                                    speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
                                }
                                .onFailure {
                                    voiceRecognitionInProgress = false
                                    partialSpeechTranscript = ""
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Chưa nghe rõ. Hãy nhấn micro và nói gần điện thoại hơn.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        } else {
                            voiceRecognitionInProgress = false
                        }
                    }, 250L)
                    return@runOnUiThread
                }
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Không thu được âm thanh. Hãy kiểm tra micro và thử lại."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Ứng dụng chưa được cấp quyền micro. Hãy cho phép quyền Micro trong Cài đặt."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        "Dịch vụ nhận dạng tiếng Việt không kết nối được. Hãy kiểm tra Internet hoặc thử lại."
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Chưa nghe rõ câu lệnh. Hãy nói gần micro, rõ ràng rồi nhấn micro thử lại."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Chưa phát hiện tiếng nói. Hãy nhấn micro rồi bắt đầu nói ngay."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Dịch vụ nhận dạng đang bận. Đợi một chút rồi thử lại."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Dịch vụ nhận dạng trên máy chưa hỗ trợ tiếng Việt. Hãy cập nhật ứng dụng Google hoặc Speech Services."
                    else ->
                        "Nhận dạng giọng nói gặp lỗi (mã $error). Hãy thử lại hoặc cập nhật dịch vụ Google."
                }
                Toast.makeText(this@NgocSiAiActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            // Prefer a recognized candidate that matches an implemented music command;
            // otherwise keep Android's highest-confidence candidate.
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: candidates.firstOrNull { classifyNgocSiAiLocalCommand(it) != null }
                ?: candidates.firstOrNull().orEmpty()
            runOnUiThread {
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                voiceRetryCount = 0
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (phrase.isBlank()) {
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Chưa nhận được câu lệnh. Hãy nhấn micro và nói lại.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Đã nhận giọng nói: ${phrase.take(100)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Use the same allowlisted command/chat path as typed input.
                    recognizedSpeech = phrase
                    recognizedSpeechShouldSend = true
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results are only a live preview. Never execute a command or send
            // chat to Gemini until onResults returns the complete utterance.
            val candidates = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: candidates.firstOrNull { classifyNgocSiAiLocalCommand(it) != null }
                ?: candidates.firstOrNull().orEmpty()            runOnUiThread {
                if (!isFinishing && !isDestroyed && voiceRecognitionInProgress) {
                    partialSpeechTranscript = phrase.take(180)
                    if (phrase.isNotBlank()) {
                        speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                    }
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private val speechLauncher: androidx.activity.result.ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
            speechFallbackUsed = false
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            val candidates = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            // If the system speech UI was used as a fallback, accept ordinary
            // conversational speech too—not only recognized local music commands.
            // Otherwise voice chat appears to do nothing for normal questions.
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: selectNgocSiAiLocalCommandCandidate(candidates)
                ?: candidates.firstOrNull().orEmpty().trim()
            if (result.resultCode == RESULT_OK && phrase.isNotBlank()) {
                // Confirm recognition immediately so a user can distinguish microphone/
                // speech-service problems from command-routing or AI-backend problems.
                Toast.makeText(
                    this,
                    "Đã nhận giọng nói: ${phrase.take(100)}",
                    Toast.LENGTH_SHORT
                ).show()
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
                Toast.makeText(this, "Cần quyền micro để nghe từ khóa AI.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onResume() {
        super.onResume()
        refreshTtsVoiceOptions()
        wakeWordListening = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
        if (wakeWordListening) monitorWakeWordStatus()
    }

    private fun refreshTtsVoiceOptions() {
        val tts = responseTts ?: return
        availableTtsVoices = NgocSiAiVoiceSettings.availableOptions(tts.voices.orEmpty())
        selectedTtsVoiceName = NgocSiAiVoiceSettings.selectedVoiceName(this)
            ?.takeIf { saved -> availableTtsVoices.any { it.name == saved } }
            ?: tts.voice?.name.orEmpty()
    }

    private fun selectAiVoice(name: String) {
        val tts = responseTts
        val voice = tts?.voices?.firstOrNull { it.name == name }
        if (tts == null || voice == null) {
            Toast.makeText(this, "Không tìm thấy giọng đọc này trên điện thoại.", Toast.LENGTH_LONG).show()
            refreshTtsVoiceOptions()
            return
        }
        val result = runCatching { tts.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Không đổi được giọng đọc. Hãy thử một giọng khác.", Toast.LENGTH_LONG).show()
            return
        }
        NgocSiAiVoiceSettings.saveSelectedVoiceName(this, name)
        selectedTtsVoiceName = name
        responseTtsReady = true
        if (wakeWordListening) NgocSiWakeWordService.updateVoice(this)
        // Play a short preview so the user can compare installed voices.
        speakVoiceReply("Xin chào, tôi là Ngọc Sĩ AI. Đây là giọng đọc bạn vừa chọn.", force = true)
    }

    private fun manageTtsVoices() {
        runCatching {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }.onFailure {
            Toast.makeText(
                this,
                "Hãy mở Cài đặt điện thoại, tìm mục Văn bản thành giọng nói và tải thêm giọng đọc.",
                Toast.LENGTH_LONG
            ).show()
        }
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
                    // TTS errors can arrive asynchronously after speak() returned SUCCESS.
                    // Surface them to the user instead of silently dropping spoken replies.
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Điện thoại không phát được giọng đọc. Hãy kiểm tra cài đặt Văn bản thành giọng nói và âm lượng.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Lỗi giọng đọc (mã $errorCode). Hãy kiểm tra dữ liệu tiếng Việt trong cài đặt Văn bản thành giọng nói.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    })
                    val vietnameseResult = runCatching {
                        tts.setLanguage(Locale.forLanguageTag("vi-VN"))
                    }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                    responseTtsReady = vietnameseResult >= TextToSpeech.LANG_AVAILABLE
                    if (!responseTtsReady) {
                        val fallbackResult = runCatching {
                            tts.setLanguage(Locale.getDefault())
                        }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                        responseTtsReady = fallbackResult >= TextToSpeech.LANG_AVAILABLE
                    }
                    // Apply the chosen engine voice after selecting a supported language.
                    selectedTtsVoiceName = NgocSiAiVoiceSettings.applySavedVoice(this@NgocSiAiActivity, tts)
                        ?: tts.voice?.name.orEmpty()
                    availableTtsVoices = NgocSiAiVoiceSettings.availableOptions(tts.voices.orEmpty())
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
        connectProductionMusicController()
        setContent {
            NgocSiAiPreviewScreen(
                isOnlineConfigured = aiClient.isConfigured,
                chatPreferences = aiChatPreferences,
                onSendOnline = { history -> aiClient.send(history) },
                onLocalCommand = { command, phrase -> runLocalCommand(command, phrase) },
                onVoiceReply = { message -> speakVoiceReply(message) },
                onSpeakMessage = { message -> speakVoiceReply(message, force = true) },
                voiceRepliesEnabled = voiceRepliesEnabled,
                onToggleVoiceReplies = { toggleVoiceReplies() },
                voiceOptions = availableTtsVoices,
                selectedVoiceName = selectedTtsVoiceName,
                onVoiceSelected = { name -> selectAiVoice(name) },
                onManageVoices = { manageTtsVoices() },
                recognizedSpeech = recognizedSpeech,
                recognizedSpeechShouldSend = recognizedSpeechShouldSend,
                partialSpeechTranscript = partialSpeechTranscript,
                isVoiceListening = voiceRecognitionInProgress,
                onSpeechConsumed = {
                    recognizedSpeech = ""
                    recognizedSpeechShouldSend = false
                    partialSpeechTranscript = ""
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
                    pendingPlayAfterQueueLoad = false
                    pendingQueueStartIndexAfterLoad = null
                    pendingTrackQueryAfterQueueLoad = null
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
                val pendingTrackQuery = pendingTrackQueryAfterQueueLoad
                pendingTrackQueryAfterQueueLoad = null
                val targetTrackIndex = pendingTrackQuery?.let { query ->
                    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
                    val exact = localItems.indexOfFirst { item ->
                        normalizeNgocSiAiVoicePhrase(item.mediaMetadata.title?.toString().orEmpty()) == normalizedQuery
                    }
                    if (exact >= 0) exact else localItems.indexOfFirst { item ->
                        val title = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.title?.toString().orEmpty())
                        val artist = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.artist?.toString().orEmpty())
                        title.contains(normalizedQuery) || artist.contains(normalizedQuery)
                    }.takeIf { it >= 0 }
                }
                val shouldStartPlayback = if (pendingTrackQuery != null) {
                    targetTrackIndex != null
                } else {
                    pendingPlayAfterQueueLoad
                }
                val requestedStartIndex = targetTrackIndex ?: pendingQueueStartIndexAfterLoad ?: 0
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                val canStartPlayback = shouldStartPlayback && requestedStartIndex in localItems.indices
                if (canStartPlayback) {
                    activeController.seekTo(requestedStartIndex, 0L)
                    activeController.play()
                }
                val startedTitle = localItems.getOrNull(requestedStartIndex)?.mediaMetadata?.title?.toString().orEmpty()
                Toast.makeText(
                    this@NgocSiAiActivity,
                    when {
                        targetTrackIndex != null && canStartPlayback -> "Đã nạp thư viện và đang phát: $startedTitle."
                        targetTrackIndex == null && canStartPlayback && requestedStartIndex == 1 -> "Đã nạp ${localItems.size} bài và đang phát bài thứ hai."
                        targetTrackIndex == null && canStartPlayback -> "Đã nạp ${localItems.size} bài và bắt đầu phát nhạc."
                        pendingTrackQuery != null -> "Đã nạp thư viện nhưng không tìm thấy bài “$pendingTrackQuery”."
                        shouldStartPlayback -> "Đã nạp thư viện nhưng danh sách chưa có bài thứ hai để phát."
                        else -> "Đã nạp ${localItems.size} bài nhạc trên điện thoại."
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (cancelled: CancellationException) {
                deviceQueueLoadStarted = false
                throw cancelled
            } catch (_: SecurityException) {
                deviceQueueLoadStarted = false
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                pendingTrackQueryAfterQueueLoad = null
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Android chưa cho phép đọc thư viện nhạc. Hãy cấp quyền rồi mở lại NGỌC SĨ AI.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                deviceQueueLoadStarted = false
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                pendingTrackQueryAfterQueueLoad = null
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
                .onSuccess { connectedController ->
                    if (isFinishing || isDestroyed) {
                        connectedController.release()
                    } else {
                        productionMusicController = connectedController
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasActivePlayback(controller: MediaController?): Boolean =
        controller != null && (
            controller.isPlaying ||
                (controller.playWhenReady &&
                    controller.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
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

    private fun stopActiveMusicFromAi(): String {
        val production = productionMusicController
        val preview = musicController

        // Never stop an idle Preview queue as a false substitute for the stable player's
        // session. If the stable session is unavailable, report that stopping was not done.
        if (production == null && productionMusicControllerConnecting) {            return "Đang kết nối trình phát NGỌC SĨ MUSIC. Nhạc chưa được xác nhận là đã dừng."
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

    private fun runYouTubeControl(action: String, response: String): String {
        val hasSavedVideo = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .getString("youtube_last_played_video_id", null)
            .isNullOrBlank()
            .not()
        if (!hasSavedVideo) {
            return openAppDestination(
                "youtube",
                "Chưa có video YouTube gần đây để điều khiển. Tôi đã mở YouTube; hãy tìm và phát video trước."
            )
        }
        return openAppDestination("youtube_command", response, youtubeAction = action)
    }

    private fun runLocalCommand(command: NgocSiAiLocalCommand, originalText: String = ""): String {
        when (command) {
            NgocSiAiLocalCommand.HELP -> return "Tôi có thể điều khiển nhạc trên điện thoại và YouTube riêng biệt; mở Thư viện, Yêu thích, Album, Thư mục, Drive, từng đài Radio và kênh TV; tìm nhạc trên Jamendo, Audius và YouTube; tạo/phát playlist theo tên; mở Hàng đợi, Trình phát, Cài đặt và hẹn giờ."
            NgocSiAiLocalCommand.RESUME_YOUTUBE -> return runYouTubeControl("play", "Đang tiếp tục video YouTube.")
            NgocSiAiLocalCommand.PAUSE_YOUTUBE -> return runYouTubeControl("pause", "Đang tạm dừng video YouTube.")
            NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO -> return runYouTubeControl("next", "Đang chuyển sang video YouTube tiếp theo.")
            NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO -> return runYouTubeControl("previous", "Đang chuyển về video YouTube trước.")
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON -> return runYouTubeControl("shuffle_on", "Đang bật phát ngẫu nhiên YouTube.")
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF -> return runYouTubeControl("shuffle_off", "Đang tắt phát ngẫu nhiên YouTube.")
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF -> return runYouTubeControl("repeat_off", "Đang tắt chế độ lặp YouTube.")
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL -> return runYouTubeControl("repeat_all", "Đang bật lặp hàng đợi YouTube.")
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE -> return runYouTubeControl("repeat_one", "Đang bật lặp video YouTube.")
            NgocSiAiLocalCommand.YOUTUBE_FAVORITE -> return runYouTubeControl("favorite", "Đang lưu video YouTube vào Yêu thích.")
            NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER -> return runYouTubeControl("watch_later", "Đang lưu video YouTube vào Xem sau.")
            NgocSiAiLocalCommand.OPEN_HOME -> return openAppDestination("home", "Đang mở trang chủ.")
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC -> {
                val query = extractNgocSiAiOnlineQuery(originalText)
                    ?: return "Hãy nói nội dung cần tìm, ví dụ: “Tìm nhạc Trịnh Công Sơn”."
                return openAppDestination(
                    "search_online",
                    "Đang tìm “$query” trên các nguồn nhạc trực tuyến.",
                    onlineSearchQuery = query
                )
            }
            NgocSiAiLocalCommand.SEARCH_YOUTUBE -> {
                val query = extractNgocSiAiYoutubeQuery(originalText)
                    ?: return "Hãy nói tên nội dung cần tìm, ví dụ: “Tìm YouTube nhạc Trịnh Công Sơn”."
                return openAppDestination("youtube", "Đang tìm trên YouTube: $query", query)
            }
            NgocSiAiLocalCommand.PLAY_RADIO_STATION -> {
                val query = extractNgocSiAiRadioStationQuery(originalText)
                    ?: return "Hãy nói rõ tên đài, ví dụ: “Phát Radio VOV3” hoặc “Mở Radio VOH FM 95.6”."
                return openAppDestination(
                    "radio_station",
                    "Đang mở đài $query.",
                    radioStationQuery = query
                )
            }
            NgocSiAiLocalCommand.OPEN_TV_CHANNEL -> {
                val query = extractNgocSiAiTvChannelQuery(originalText)
                    ?: return "Hãy nói tên kênh, ví dụ: “Mở VTV3” hoặc “Mở kênh HTV7”."
                return openAppDestination(
                    "tv_channel",
                    "Đang mở kênh $query.",
                    tvChannelQuery = query
                )
            }
            NgocSiAiLocalCommand.CREATE_PLAYLIST -> {
                val query = extractNgocSiAiPlaylistQuery(originalText, create = true)
                    ?: return "Hãy nói tên playlist, ví dụ: “Tạo playlist Nhạc Trịnh”."
                return openAppDestination(
                    "create_playlist",
                    "Đang tạo playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST -> {
                val query = extractNgocSiAiPlaylistQuery(originalText, create = false)
                    ?: return "Hãy nói tên playlist muốn phát, ví dụ: “Phát playlist Nhạc Trịnh”."
                return openAppDestination(
                    "play_playlist",
                    "Đang tìm playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST -> {
                val query = extractNgocSiAiAddToPlaylistQuery(originalText)
                    ?: return "Hãy nói rõ playlist đích, ví dụ: “Thêm bài đang phát vào playlist Nhạc Trịnh”."
                return openAppDestination(
                    "add_current_to_playlist",
                    "Đang thêm bài đang phát vào playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST -> {
                val query = extractNgocSiAiQueueToPlaylistQuery(originalText)
                    ?: return "Hãy nói playlist đích, ví dụ: “Thêm hàng đợi vào playlist Nhạc Trịnh”."
                return openAppDestination(
                    "add_queue_to_playlist",
                    "Đang lưu hàng đợi vào playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.OPEN_LIBRARY -> return openAppDestination("library", "Đang mở Thư viện nhạc.")
            NgocSiAiLocalCommand.OPEN_FAVORITES -> return openAppDestination("favorites", "Đang mở nhạc Yêu thích.")
            NgocSiAiLocalCommand.OPEN_ALBUMS -> return openAppDestination("albums", "Đang mở danh sách Album.")
            NgocSiAiLocalCommand.OPEN_FOLDERS -> return openAppDestination("folders", "Đang mở Thư mục nhạc.")
            NgocSiAiLocalCommand.OPEN_ONLINE -> return openAppDestination("online", "Đang mở các nguồn Online.")
            NgocSiAiLocalCommand.OPEN_YOUTUBE -> return openAppDestination("youtube", "Đang mở YouTube.")
            NgocSiAiLocalCommand.OPEN_DRIVE -> return openAppDestination("drive", "Đang mở Google Drive.")
            NgocSiAiLocalCommand.OPEN_RADIO -> return openAppDestination("radio", "Đang mở Radio.")
            NgocSiAiLocalCommand.OPEN_TV -> return openAppDestination("tv", "Đang mở Truyền hình.")
            NgocSiAiLocalCommand.OPEN_PLAYLISTS -> return openAppDestination("playlists", "Đang mở danh sách phát.")
            NgocSiAiLocalCommand.OPEN_QUEUE -> return openAppDestination("queue", "Đang mở hàng đợi phát.")
            NgocSiAiLocalCommand.OPEN_PLAYER -> return openAppDestination("player", "Đang mở trình phát.")
            NgocSiAiLocalCommand.OPEN_SETTINGS -> return openAppDestination("settings", "Đang mở Cài đặt.")
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER -> return openAppDestination("sleep_timer", "Đang mở hẹn giờ tắt nhạc.")
            NgocSiAiLocalCommand.SLEEP_TIMER_15 -> return openAppDestination("sleep_timer_15", "Đã đặt hẹn giờ tắt nhạc sau 15 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_30 -> return openAppDestination("sleep_timer_30", "Đã đặt hẹn giờ tắt nhạc sau 30 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_45 -> return openAppDestination("sleep_timer_45", "Đã đặt hẹn giờ tắt nhạc sau 45 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_60 -> return openAppDestination("sleep_timer_60", "Đã đặt hẹn giờ tắt nhạc sau 60 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_90 -> return openAppDestination("sleep_timer_90", "Đã đặt hẹn giờ tắt nhạc sau 90 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_120 -> return openAppDestination("sleep_timer_120", "Đã đặt hẹn giờ tắt nhạc sau 120 phút.")
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER -> return openAppDestination("sleep_timer_off", "Đã yêu cầu tắt hẹn giờ.")
            else -> Unit
        }

        if (command == NgocSiAiLocalCommand.STOP) return stopActiveMusicFromAi()

        val player = preferredPlaybackController()
            ?: return "Trình phát đang khởi động hoặc chưa kết nối. Hãy thử lại sau một lát."
        if (command != NgocSiAiLocalCommand.STOP &&
            (player.mediaItemCount == 0 || player.currentMediaItem == null)
        ) {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
                return "Chưa có quyền đọc nhạc. Hãy cấp quyền Âm nhạc và âm thanh cho NGỌC SĨ AI Preview trong Cài đặt ứng dụng."
            }
            // Preserve an explicit play request while the device library is loading.
            val requestedTrack = if (command == NgocSiAiLocalCommand.PLAY_TRACK) {
                extractNgocSiAiTrackQuery(originalText)
            } else null
            pendingTrackQueryAfterQueueLoad = requestedTrack
            pendingPlayAfterQueueLoad = command == NgocSiAiLocalCommand.PLAY ||
                command == NgocSiAiLocalCommand.PLAY_SECOND || requestedTrack != null
            pendingQueueStartIndexAfterLoad = if (command == NgocSiAiLocalCommand.PLAY_SECOND) 1 else if (command == NgocSiAiLocalCommand.PLAY) 0 else null
            if (!deviceQueueLoadStarted) {
                loadDeviceMusicIntoQueue()
                return when {
                    requestedTrack != null -> "Đang nạp thư viện; tôi sẽ tìm và phát bài $requestedTrack ngay khi sẵn sàng."
                    command == NgocSiAiLocalCommand.PLAY -> "Đang nạp thư viện nhạc; tôi sẽ tự phát ngay khi nạp xong."
                    else -> "Đang kiểm tra thư viện nhạc trên điện thoại. Hãy thử lại sau khi nạp xong."
                }
            }
            return if (requestedTrack != null) {
                "Thư viện đang nạp; tôi sẽ tìm bài $requestedTrack khi sẵn sàng."
            } else if (command == NgocSiAiLocalCommand.PLAY) {
                "Thư viện đang nạp; tôi sẽ tự phát nhạc ngay khi sẵn sàng."
            } else {
                "Thư viện nhạc đang được nạp. Hãy đợi một chút rồi thử lại."
            }
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
            NgocSiAiLocalCommand.PLAY_TRACK -> {
                val query = extractNgocSiAiTrackQuery(originalText)
                    ?: return "Hãy nói tên bài, ví dụ: “Phát bài hát Diễm xưa”."
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
                    "Không tìm thấy “$query” trong hàng đợi hiện tại. Hãy mở Thư viện hoặc thêm bài vào hàng đợi trước."
                } else {
                    player.seekTo(matchIndex, 0L)
                    player.play()
                    val title = player.getMediaItemAt(matchIndex).mediaMetadata.title?.toString()
                        ?.takeIf { it.isNotBlank() } ?: query
                    "Đang phát $title."
                }
            }
            NgocSiAiLocalCommand.PLAY_SECOND -> {
                if (player.mediaItemCount > 1) {
                    player.seekTo(1, 0L)
                    player.play()
                    "Đang phát bài thứ hai trong hàng đợi."
                } else {
                    "Hàng đợi hiện tại chưa có bài thứ hai."
                }
            }
            NgocSiAiLocalCommand.PLAY -> {
                player.play()
                "Đã gửi lệnh phát nhạc đến trình phát."
            }
            NgocSiAiLocalCommand.PAUSE -> {
                player.pause()
                "Đã gửi lệnh tạm dừng nhạc."
            }
            NgocSiAiLocalCommand.STOP -> {
                // Explicitly disable playback before stopping so the service cannot
                // continue playing due to a stale playWhenReady state.
                player.pause()
                player.playWhenReady = false
                player.stop()
                "Đã dừng hẳn nhạc. Hàng đợi vẫn được giữ để bạn có thể phát lại."
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
            else -> "Yêu cầu này cần mở mục chức năng tương ứng trong ứng dụng."
        }
    }

    private fun openAppDestination(
        destination: String,
        response: String,
        youtubeSearchQuery: String? = null,
        radioStationQuery: String? = null,
        tvChannelQuery: String? = null,
        playlistQuery: String? = null,
        youtubeAction: String? = null,
        onlineSearchQuery: String? = null
    ): String {
        return runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra("pro_destination", destination)
                    youtubeSearchQuery?.let { putExtra("ai_youtube_query", it) }
                    radioStationQuery?.let { putExtra("ai_radio_station", it) }
                    tvChannelQuery?.let { putExtra("ai_tv_channel", it) }
                    playlistQuery?.let { putExtra("ai_playlist_query", it) }
                    youtubeAction?.let { putExtra("ai_youtube_action", it) }
                    onlineSearchQuery?.let { putExtra("ai_online_query", it) }
                    // Bring the existing main app to the front and deliver its navigation event.
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            response
        }.getOrElse {
            "Chưa mở được chức năng trong ứng dụng. Hãy mở chức năng đó từ trang chủ."
        }
    }

    private fun speakVoiceReply(message: String, force: Boolean = false) {
        if (!force && !voiceRepliesEnabled) return
        val cleanMessage = NgocSiAiSpeechTextRules.cleanForSpeech(message)
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
        productionMusicController?.release()
        productionMusicController = null
        runCatching { responseTts?.stop() }
        runCatching { voiceRecognizer?.cancel() }
        runCatching { voiceRecognizer?.destroy() }
        voiceRecognizer = null
        voiceRecognitionInProgress = false
        voiceRetryHandler.removeCallbacksAndMessages(null)
        speechFallbackHandler.removeCallbacksAndMessages(null)
        activeVoiceIntent = null
        runCatching { responseTts?.shutdown() }
        responseTts = null
        responseTtsInitialized = false
        responseTtsReady = false
        pendingVoiceReply = null
        partialSpeechTranscript = ""
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Android 13+ offers a latency-oriented formatting mode for real-time dictation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY
                )
            }
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Nói tiếng Việt, ví dụ: Phát nhạc, mở YouTube, mở Drive, mở Yêu thích hoặc hẹn giờ"
            )
        }
        if (voiceRecognitionInProgress) {
            Toast.makeText(this, "Micro đang nghe. Hãy nói lệnh hoặc đợi kết thúc.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            speechFallbackUsed = false
            speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                // Keep the Android/Google speech activity as a fallback on devices
                // that do not expose a RecognitionService directly to the app.
                voiceRecognitionInProgress = true
                partialSpeechTranscript = ""
                speechLauncher.launch(intent)
                return
            }
            val recognizer = voiceRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(inAppSpeechListener)
                voiceRecognizer = it
            }
            voiceRecognitionInProgress = true
            partialSpeechTranscript = ""
            voiceRetryCount = 0
            activeVoiceIntent = intent
            Toast.makeText(this, "Đang nghe… hãy nói rõ câu lệnh, không cần nói quá nhanh.", Toast.LENGTH_SHORT).show()
            recognizer.startListening(intent)
            speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
        } catch (_: SecurityException) {
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            Toast.makeText(this, "Thiếu quyền micro. Hãy cấp quyền micro cho NGỌC SĨ AI Preview.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            // If the direct service cannot start, fall back to Android's speech UI.
            runCatching {
                runCatching { voiceRecognizer?.destroy() }
                voiceRecognizer = null
                speechLauncher.launch(intent)
            }.onFailure {
                Toast.makeText(
                    this,
                    "Không mở được dịch vụ nhận dạng giọng nói. Hãy cập nhật ứng dụng Google hoặc Speech Services by Google.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
@Composable
private fun NgocSiAiPreviewScreen(
    isOnlineConfigured: Boolean,
    chatPreferences: SharedPreferences,
    onSendOnline: suspend (List<NgocSiAiMessage>) -> NgocSiAiReply,
    onLocalCommand: (NgocSiAiLocalCommand, String) -> String,
    onVoiceReply: (String) -> Unit,
    onSpeakMessage: (String) -> Unit,
    voiceRepliesEnabled: Boolean,
    onToggleVoiceReplies: () -> Unit,
    voiceOptions: List<NgocSiAiVoiceOption>,
    selectedVoiceName: String,
    onVoiceSelected: (String) -> Unit,
    onManageVoices: () -> Unit,
    recognizedSpeech: String,
    recognizedSpeechShouldSend: Boolean,
    partialSpeechTranscript: String,
    isVoiceListening: Boolean,
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
    var showVoicePicker by remember { mutableStateOf(false) }
    val messageListState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun persistChatState() {
        saveAiPreviewMessages(chatPreferences, messages)
        saveAiApiConversation(chatPreferences, conversation)
    }

    LaunchedEffect(messages.size, isSending) {
        val finalVisibleIndex = messages.lastIndex + if (isSending) 1 else 0
        if (finalVisibleIndex >= 0) {
            messageListState.animateScrollToItem(finalVisibleIndex)
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        // All allowlisted on-device commands and short local replies stay responsive
        // while an unrelated Gemini request is still in flight.
        val explicitStop = isNgocSiAiExplicitStopPhrase(clean)
        val localCommand = if (explicitStop) {
            NgocSiAiLocalCommand.STOP
        } else {
            classifyNgocSiAiLocalCommand(clean)
        }
        val localReply = if (localCommand == null) {
            localConversationFallback(clean, messages.lastOrNull { !it.isUser }?.text)
        } else null
        if (isSending && localCommand == null && localReply == null) return

        messages.add(AiPreviewMessage(true, clean))
        if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
        input = ""
        if (localCommand != null) {
            val commandReply = onLocalCommand(localCommand, clean)
            messages.add(AiPreviewMessage(false, commandReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            // Preserve local turns in future model context only when no earlier request
            // is in flight; never interleave them inside an unfinished user/assistant pair.
            if (!isSending) {
                conversation.add(NgocSiAiMessage(role = "user", content = clean))
                conversation.add(NgocSiAiMessage(role = "assistant", content = commandReply))
                while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                    conversation.removeAt(0)
                    if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
                }
                saveAiApiConversation(chatPreferences, conversation)
            }
            if (isSending) {
                // Do not persist an unfinished remote user turn when a local command
                // interrupts the wait; the in-flight result will complete or roll it back.
                saveAiPreviewMessages(chatPreferences, messages)
            } else {
                persistChatState()
            }
            onVoiceReply(commandReply)
            return
        }

        // Keep predictable greetings/help local and preserve them for later context without
        // spending an online request.
        if (localReply != null) {
            messages.add(AiPreviewMessage(false, localReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            if (!isSending) {
                conversation.add(NgocSiAiMessage(role = "user", content = clean))
                conversation.add(NgocSiAiMessage(role = "assistant", content = localReply))
                while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                    conversation.removeAt(0)
                    if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
                }
                saveAiApiConversation(chatPreferences, conversation)
            }
            if (isSending) {
                saveAiPreviewMessages(chatPreferences, messages)
            } else {
                persistChatState()
            }
            onVoiceReply(localReply)
            return
        }

        if (!isOnlineConfigured) {
            val offlineReply =
                "Tôi đã nhận tin nhắn. Khi chưa kết nối máy chủ AI, tôi vẫn có thể điều khiển nhạc, mở các mục chính của ứng dụng và đặt hẹn giờ bằng lệnh được hỗ trợ. Chatbot trả lời tự do chưa bật; tin nhắn này không được gửi ra ngoài và không phát sinh phí AI."
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
                quotaNote = if (reply.usedFallbackModel) {
                    "Đã tự chuyển sang Gemini 3.8 Flash dự phòng do model chính bị giới hạn."
                } else {
                    listOfNotNull(userRemaining, globalRemaining).joinToString(" • ")
                }
            } catch (cancelled: CancellationException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                persistChatState()
                throw cancelled
            } catch (error: NgocSiAiRemoteException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                val errorReply = error.message ?: "Không thể kết nối NGỌC SĨ AI. Vui lòng thử lại."
                val fallbackReply = localConversationFallback(
                    clean,
                    messages.dropLast(1).lastOrNull { !it.isUser }?.text
                )
                val visibleReply = fallbackReply ?: errorReply
                val quotaNotice = ngocSiAiQuotaStatusNotice(errorReply)
                quotaNote = when {
                    fallbackReply != null && quotaNotice != null ->
                        "Chat trực tuyến đang bị giới hạn; đã trả lời dự phòng trên máy, không phát sinh phí."
                    fallbackReply != null ->
                        "Đã trả lời dự phòng trên máy do kết nối AI gặp lỗi; không phát sinh phí."
                    else -> quotaNotice.orEmpty()
                }
                messages.add(AiPreviewMessage(false, visibleReply))
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
                onVoiceReply(visibleReply)
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

    if (showVoicePicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("Chọn giọng đọc của NGỌC SĨ AI") },
            text = {
                Column(Modifier.heightIn(max = 360.dp)) {
                    if (voiceOptions.isEmpty()) {
                        Text(
                            "Điện thoại chưa cung cấp danh sách giọng đọc. Bạn có thể mở phần quản lý giọng đọc để cài thêm.",
                            color = NgocSiVisuals.TextSecondary,
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn {
                            items(voiceOptions, key = { it.name }) { voice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onVoiceSelected(voice.name)
                                            showVoicePicker = false
                                        }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            voice.label,
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        if (voice.languageTag.startsWith("vi")) {
                                            Text(
                                                "Ưu tiên cho tiếng Việt",
                                                color = Color(0xFF71E6B2),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    androidx.compose.material3.RadioButton(
                                        selected = selectedVoiceName == voice.name,
                                        onClick = {
                                            onVoiceSelected(voice.name)
                                            showVoicePicker = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showVoicePicker = false }) {
                    Text("Đóng", color = NgocSiVisuals.TextSecondary)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onManageVoices) {
                    Text("Quản lý giọng đọc", color = NgocSiVisuals.Secondary)
                }
            },
            containerColor = NgocSiVisuals.SurfaceSoft,
            titleContentColor = Color.White,
            textContentColor = NgocSiVisuals.TextSecondary
        )
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
                    Text("Hủy", color = NgocSiVisuals.TextSecondary)
                }
            },
            containerColor = NgocSiVisuals.SurfaceSoft,
            titleContentColor = Color.White,
            textContentColor = NgocSiVisuals.TextSecondary
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NgocSiVisuals.Background)
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NgocSiVisuals.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(44.dp)
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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "TRỢ LÝ CỦA BẠN",
                    color = NgocSiVisuals.Secondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }
            Surface(
                modifier = Modifier.clickable(enabled = !isSending) {
                    showClearConfirm = true
                },
                shape = RoundedCornerShape(50),
                color = NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A334A))
            ) {
                Text(
                    "XÓA",
                    color = Color(0xFFFFB0B0),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                modifier = Modifier.clickable(onClick = onToggleVoiceReplies),
                shape = RoundedCornerShape(50),
                color = if (voiceRepliesEnabled) Color(0xFF123329) else NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (voiceRepliesEnabled) Color(0xFF39C98A) else Color(0xFF3A334A)
                )
            ) {
                Text(
                    if (voiceRepliesEnabled) "🔊" else "🔇",
                    color = if (voiceRepliesEnabled) Color(0xFF71E6B2) else NgocSiVisuals.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = NgocSiVisuals.SurfaceRaised
            ) {
                Text(
                    if (isOnlineConfigured) "AI TEST" else "PREVIEW",
                    color = Color(0xFFC7B5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        val selectedVoiceLabel = voiceOptions.firstOrNull { it.name == selectedVoiceName }?.label
            ?: "Giọng mặc định của điện thoại"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1.08f)
                    .clickable(onClick = onToggleWakeWord),
                shape = RoundedCornerShape(18.dp),
                color = if (isWakeWordListening) Color(0xFF123329) else NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isWakeWordListening) Color(0xFF286653) else NgocSiVisuals.Border
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = if (isWakeWordListening) Color(0xFF1B4A3C) else Color(0xFF28213D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (isWakeWordListening) "●" else "◉",
                                color = if (isWakeWordListening) NgocSiVisuals.Success else NgocSiVisuals.Primary,
                                fontSize = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ĐIỀU KHIỂN GIỌNG NÓI",
                            color = NgocSiVisuals.TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.35.sp,
                            maxLines = 1
                        )
                        Text(
                            if (isWakeWordListening) "Đang nghe từ khóa" else "Đánh thức “AI”",
                            color = NgocSiVisuals.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(0.92f)
                    .clickable { showVoicePicker = true },
                shape = RoundedCornerShape(18.dp),
                color = NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = Color(0xFF28213D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("♫", color = NgocSiVisuals.Primary, fontSize = 19.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "GIỌNG ĐỌC",
                            color = NgocSiVisuals.TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.35.sp
                        )
                        Text(
                            selectedVoiceLabel,
                            color = NgocSiVisuals.Text,
                            fontSize = 11.sp,
                            maxLines = 1,                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("›", color = NgocSiVisuals.Secondary, fontSize = 22.sp)
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            shape = RoundedCornerShape(14.dp),
            color = NgocSiVisuals.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isOnlineConfigured) NgocSiVisuals.Success else NgocSiVisuals.TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isOnlineConfigured) "NGỌC SĨ AI • TRỰC TUYẾN" else "NGỌC SĨ AI • BẢN XEM TRƯỚC",
                        color = NgocSiVisuals.Text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            quotaNote.isNotBlank() -> quotaNote
                            isOnlineConfigured -> "Câu hỏi tự do có thể chịu giới hạn theo hạn mức Gemini."
                            else -> "Lệnh nhạc cơ bản chạy tại máy; chat tự do đang tắt."
                        },
                        color = NgocSiVisuals.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        LazyColumn(
            state = messageListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (!message.isUser) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = Color(0xFF29203F)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✦", color = NgocSiVisuals.Primary, fontSize = 17.sp)
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
                            color = if (message.isUser) Color(0xFF25213A) else NgocSiVisuals.SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (message.isUser) Color(0xFF44376B) else NgocSiVisuals.Border
                            )
                        ) {
                            Text(
                                message.text,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                                color = if (message.isUser) Color(0xFFF0ECFF) else NgocSiVisuals.TextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 21.sp
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
                                    color = NgocSiVisuals.SurfaceSoft,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                                ) {
                                    Text(
                                        "🔊 ĐỌC",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = NgocSiVisuals.TextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isSending) {
                item(key = "assistant-thinking") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF28213D)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✦", color = NgocSiVisuals.Primary, fontSize = 17.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = NgocSiVisuals.SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
                        ) {
                            Text(
                                "Đang xử lý yêu cầu…",
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                color = NgocSiVisuals.TextSecondary,
                                fontSize = 12.sp
                            )
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
            if (isVoiceListening) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF17231F),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C5148))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (partialSpeechTranscript.isBlank()) "ĐANG NGHE TIẾNG VIỆT"
                                else "ĐANG NHẬN GIỌNG NÓI",
                                color = Color(0xFF86E0BD),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                partialSpeechTranscript.ifBlank { "Hãy nói tự nhiên; bản ghi sẽ hiện tại đây." },
                                color = Color(0xFFE0EDE8),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Text(
                "GỢI Ý THỬ",
                color = Color(0xFF9299AA),
                fontSize = 10.sp,
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
                val suggestions = listOf(
                    "Bạn làm được gì?",
                    "Tôi muốn app của mình chuyên nghiệp hơn nhưng chưa biết nên bắt đầu từ đâu.",
                    "Phát nhạc",
                    "Tạm dừng nhạc",
                    "Mở YouTube",
                    "Bài tiếp theo",
                    "Mở Google Drive",
                    "Mở Radio",
                    "Hẹn giờ 30 phút"
                )
                suggestions.forEach { suggestion ->
                    Surface(
                        modifier = Modifier.clickable { input = suggestion },
                        shape = RoundedCornerShape(50),
                        color = NgocSiVisuals.SurfaceSoft,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = Color(0xFFE5E2F0),
                            fontSize = 11.sp,
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
                    .background(NgocSiVisuals.SurfaceSoft)
                    .border(1.dp, NgocSiVisuals.Border, RoundedCornerShape(20.dp))
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
                        fontSize = 14.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NgocSiVisuals.Secondary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage(input) }),
                    singleLine = false,
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.isBlank()) {
                                Text(
                                    "Nhập tin nhắn...",
                                    color = Color(0xFF9299AA),
                                    fontSize = 14.sp
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
                    color = NgocSiVisuals.SurfaceRaised
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
                    color = if (isSending) Color(0xFF515661) else NgocSiVisuals.Secondary
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
                color = Color(0xFF9299AA),
                fontSize = 10.sp
            )
        }
    }
}
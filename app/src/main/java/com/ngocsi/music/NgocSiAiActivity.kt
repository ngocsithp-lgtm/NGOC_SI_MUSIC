package com.ngocsi.music

import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
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
        }.takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
    }.getOrDefault(emptyList())
    val bounded = decoded.dropWhile { it.role == "assistant" }
    return if (bounded.firstOrNull()?.role == "user") bounded else emptyList()
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

class NgocSiAiActivity : ComponentActivity() {
    private var recognizedSpeech by mutableStateOf("")

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            recognizedSpeech = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aiClient = NgocSiAiRemoteClient(applicationContext)
        val aiChatPreferences = getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE)
        setContent {
            NgocSiAiPreviewScreen(
                isOnlineConfigured = aiClient.isConfigured,
                chatPreferences = aiChatPreferences,
                onSendOnline = { history -> aiClient.send(history) },
                recognizedSpeech = recognizedSpeech,
                onSpeechConsumed = { recognizedSpeech = "" },
                onBack = { finish() },
                onVoice = { startVoiceInput() }
            )
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói nội dung bạn muốn nhập")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Thiết bị chưa hỗ trợ nhập giọng nói.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Không thể mở nhập giọng nói. Hãy thử lại.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun NgocSiAiPreviewScreen(
    isOnlineConfigured: Boolean,
    chatPreferences: SharedPreferences,
    onSendOnline: suspend (List<NgocSiAiMessage>) -> NgocSiAiReply,
    recognizedSpeech: String,
    onSpeechConsumed: () -> Unit,
    onBack: () -> Unit,
    onVoice: () -> Unit
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

    LaunchedEffect(recognizedSpeech) {
        if (recognizedSpeech.isNotBlank()) {
            input = listOf(input.trim(), recognizedSpeech.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .take(2_000)
            onSpeechConsumed()
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || isSending) return
        messages.add(AiPreviewMessage(true, clean))
        if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
        input = ""

        if (!isOnlineConfigured) {
            messages.add(
                AiPreviewMessage(
                    false,
                    "Đã nhận nội dung trong bản xem trước. Firebase/Gemini chưa được cấu hình cho bản này, nên tin nhắn chưa được gửi lên máy chủ và không phát sinh phí AI."
                )
            )
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            persistChatState()
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
                val userRemaining = reply.remainingToday?.let { "Còn $it lượt/tài khoản hôm nay" }
                val globalRemaining = reply.remainingGlobalToday?.let { "Còn $it lượt toàn hệ thống hôm nay" }
                quotaNote = listOfNotNull(userRemaining, globalRemaining).joinToString(" • ")
            } catch (cancelled: CancellationException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                persistChatState()
                throw cancelled
            } catch (error: NgocSiAiRemoteException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                messages.add(
                    AiPreviewMessage(
                        false,
                        error.message ?: "Không thể kết nối NGỌC SĨ AI. Vui lòng thử lại."
                    )
                )
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
            } catch (_: Exception) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                messages.add(
                    AiPreviewMessage(
                        false,
                        "Có lỗi khi gửi yêu cầu AI. Hãy kiểm tra kết nối và cấu hình Firebase."
                    )
                )
                if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                persistChatState()
            } finally {
                isSending = false
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
                        "Chế độ kiểm thử trực tuyến • Tin nhắn gửi tới máy chủ AI. Không nhập thông tin nhạy cảm."
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
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.84f),
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
                    if (message.isUser) Spacer(Modifier.width(2.dp))
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
                listOf("Xin chào NGỌC SĨ AI", "Giúp tôi tìm nhạc", "Điều khiển bằng giọng nói").forEach { suggestion ->
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
                    isOnlineConfigured -> quotaNote.ifBlank { "Tin nhắn chỉ được gửi khi bạn nhấn nút gửi." }
                    else -> "Bản xem trước chạy cục bộ; lịch sử được lưu trên điện thoại."
                },
                color = Color(0xFF666D7D),
                fontSize = 9.sp
            )
        }
    }
}

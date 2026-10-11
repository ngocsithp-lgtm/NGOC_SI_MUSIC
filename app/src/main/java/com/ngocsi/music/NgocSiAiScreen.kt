package com.ngocsi.music

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import java.util.Locale
import kotlinx.coroutines.launch

private data class NgocSiAiMessage(val fromUser: Boolean, val text: String)

@Composable
fun NgocSiAiScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages = remember {
        mutableStateListOf(
            NgocSiAiMessage(
                false,
                "Xin chào! Tôi là NGỌC SĨ AI. Bạn có thể nhập hoặc bấm Mic để nói tiếng Việt. Tôi có thể đọc câu trả lời thành tiếng."
            )
        )
    }
    var prompt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speech = remember {
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) {
                // Use the device's Vietnamese voice when one is installed.
                // The device may fall back to its default voice if Vietnamese is unavailable.
            }
        }
    }
    DisposableEffect(speech) {
        onDispose {
            speech.stop()
            speech.shutdown()
        }
    }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            prompt = spoken
            errorText = null
        } else if (result.resultCode == android.app.Activity.RESULT_OK) {
            errorText = "Chưa nhận được giọng nói. Hãy thử nói rõ hơn."
        }
    }
    val chat = remember {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3.5-flash-lite")
            .startChat()
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF08090D)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF141724)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("✦ NGỌC SĨ AI", color = Color(0xFFCDBEFF), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("Trợ lý AI trực tuyến • cần Internet", color = Color(0xFFB9BBC8), fontSize = 12.sp)
                Text("Giọng nói dùng dịch vụ nhận dạng/đọc có sẵn trên điện thoại. Không gửi mật khẩu hoặc thông tin riêng tư.", color = Color(0xFF85899A), fontSize = 11.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages) { _, message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (message.fromUser) Color(0xFF352A57) else Color(0xFF171B25),
                        modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.88f else 0.96f)
                    ) {
                        Text(
                            message.text,
                            modifier = Modifier.padding(12.dp),
                            color = Color(0xFFF1F1F7),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            if (isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("NGỌC SĨ AI đang trả lời…", color = Color(0xFFB9BBC8), fontSize = 12.sp)
                    }
                }
            }
        }

        errorText?.let {
            Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(2000) },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                placeholder = { Text("Nhập câu hỏi hoặc bấm Mic…") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 4
            )
            Button(
                onClick = {
                    errorText = null
                    try {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Hãy nói câu hỏi cho NGỌC SĨ AI")
                        }
                        speechLauncher.launch(intent)
                    } catch (_: Exception) {
                        errorText = "Điện thoại chưa có dịch vụ nhận dạng giọng nói. Hãy cài/bật dịch vụ Google Speech rồi thử lại."
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Mic")
            }
            Button(
                onClick = {
                    val question = prompt.trim()
                    if (question.isNotEmpty() && !isLoading) {
                        prompt = ""
                        errorText = null
                        messages.add(NgocSiAiMessage(true, question))
                        isLoading = true
                        scope.launch {
                            try {
                                val answer = chat.sendMessage(question).text
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { "AI chưa trả về nội dung. Hãy thử hỏi lại." }
                                messages.add(NgocSiAiMessage(false, answer))
                                if (ttsReady) {
                                    speech.language = Locale.forLanguageTag("vi-VN")
                                    speech.speak(answer.take(3500), TextToSpeech.QUEUE_FLUSH, null, "ngocsi-ai-answer")
                                }
                            } catch (e: Exception) {
                                errorText = when {
                                    e.message.orEmpty().contains("app check", true) ->
                                        "Chưa xác minh được App Check. Cần đăng ký debug token trong Firebase Console."
                                    e.message.orEmpty().contains("429") || e.message.orEmpty().contains("quota", true) ->
                                        "Đã chạm hạn mức hiện tại. Vui lòng đợi rồi thử lại."
                                    else -> "Không kết nối được AI. Kiểm tra Internet, cấu hình Firebase và quyền App Check rồi thử lại."
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = prompt.isNotBlank() && !isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Gửi")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = { speech.stop() },
                enabled = ttsReady
            ) { Text("Dừng đọc") }
            Spacer(Modifier.weight(1f))
        }
    }
}

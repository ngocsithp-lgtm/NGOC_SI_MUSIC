package com.ngocsi.music

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class AiPreviewMessage(val isUser: Boolean, val text: String)

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
        setContent {
            NgocSiAiPreviewScreen(
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
        runCatching { speechLauncher.launch(intent) }
    }
}

@Composable
private fun NgocSiAiPreviewScreen(
    recognizedSpeech: String,
    onSpeechConsumed: () -> Unit,
    onBack: () -> Unit,
    onVoice: () -> Unit
) {
    val messages = remember {
        mutableStateListOf(
            AiPreviewMessage(
                false,
                "Xin chào! Tôi là NGỌC SĨ AI. Đây là bản xem trước giao diện; AI trực tuyến chưa được kết nối."
            )
        )
    }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(recognizedSpeech) {
        if (recognizedSpeech.isNotBlank()) {
            input = listOf(input.trim(), recognizedSpeech.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
            onSpeechConsumed()
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        messages.add(AiPreviewMessage(true, clean))
        messages.add(
            AiPreviewMessage(
                false,
                "Đã nhận nội dung trong bản xem trước. Chức năng trả lời AI trực tuyến chưa được bật, nên tin nhắn này chưa được gửi tới Gemini và chưa phát sinh phí AI."
            )
        )
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090D))
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
                shape = RoundedCornerShape(50),
                color = Color(0xFF242033)
            ) {
                Text(
                    "PREVIEW",
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
                    "Bản thử giao diện • Chưa kết nối AI • Không phát sinh phí AI",
                    color = Color(0xFFC7C1D8),
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }
        }

        LazyColumn(
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
                        modifier = Modifier.clickable { sendMessage(suggestion) },
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
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 13.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF8DEEFF)),
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
                        .size(40.dp)
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
                        .size(40.dp)
                        .clickable { sendMessage(input) },
                    shape = CircleShape,
                    color = Color(0xFF8DEEFF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("↑", color = Color(0xFF061018), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "Bản xem trước chỉ giữ tin nhắn trong màn hình hiện tại.",
                color = Color(0xFF666D7D),
                fontSize = 9.sp
            )
        }
    }
}

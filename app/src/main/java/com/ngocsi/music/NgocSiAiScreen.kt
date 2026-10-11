package com.ngocsi.music

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.launch

private data class NgocSiAiMessage(val fromUser: Boolean, val text: String)

@Composable
fun NgocSiAiScreen(modifier: Modifier = Modifier) {
    val messages = remember {
        mutableStateListOf(
            NgocSiAiMessage(
                false,
                "Xin chào! Tôi là NGỌC SĨ AI. Bạn có thể hỏi tôi bằng tiếng Việt. Tôi sẽ trả lời dựa trên thông tin mình có và nói rõ khi chưa chắc chắn."
            )
        )
    }
    var prompt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chat = remember {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-2.5-flash-lite")
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
                Text("Không gửi mật khẩu hoặc thông tin riêng tư. Câu trả lời có thể chưa chính xác.", color = Color(0xFF85899A), fontSize = 11.sp)
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
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                placeholder = { Text("Nhập câu hỏi…") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 4
            )
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
    }
}

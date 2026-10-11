package com.ngocsi.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class AiPreviewMessage(val text: String, val fromUser: Boolean)

/**
 * Preview-only Compose UI. Not registered as an Activity and not connected to
 * a network service, microphone, Gemini, or app-control actions.
 */
@Composable
fun NgocSiAiPreviewScreen() {
    var input by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            AiPreviewMessage(
                "Đây là giao diện xem trước NGỌC SĨ AI. AI trực tuyến chưa được kết nối nên chưa thể trả lời thực tế hoặc thực hiện thao tác trong ứng dụng.",
                false
            )
        )
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1020)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                "NGỌC SĨ AI",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Trợ lý thông minh · Bản xem trước",
                color = Color(0xFFB7C3DD),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202B43)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("CHƯA KẾT NỐI AI TRỰC TUYẾN", color = Color(0xFFFFD180), fontWeight = FontWeight.Bold)
                    Text(
                        "Tin nhắn thử chỉ được lưu tạm trong màn hình này. Chưa gửi dữ liệu ra ngoài.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (item.fromUser) Color(0xFF315CBB) else Color(0xFF1C2740),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(0.88f)
                        ) {
                            Text(item.text, Modifier.padding(12.dp), color = Color.White)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 2000) input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tin nhắn") },
                placeholder = { Text("Nhập để thử giao diện…") },
                maxLines = 4
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val value = input.trim()
                    if (value.isNotEmpty()) {
                        messages.add(AiPreviewMessage(value, true))
                        messages.add(
                            AiPreviewMessage(
                                "Đã nhận nội dung trong bản xem trước. Chưa gửi tới Gemini vì backend và đăng nhập an toàn chưa được cấu hình.",
                                false
                            )
                        )
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Thử giao diện")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Điều khiển bằng giọng nói sẽ được tích hợp sau khi hoàn tất bảo mật.",
                color = Color(0xFFB7C3DD),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

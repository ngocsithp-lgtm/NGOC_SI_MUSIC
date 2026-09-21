package com.ngocsi.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Black = Color(0xFF0B0B0B)
private val Card = Color(0xFF17171D)
private val White = Color(0xFFF5F5F5)
private val Gray = Color(0xFF9A9AA3)
private val Accent = Color(0xFFB58CFF)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NgocSiMusicApp()
        }
    }
}

@Composable
fun NgocSiMusicApp() {

    var playing by remember {
        mutableStateOf(false)
    }

    var query by remember {
        mutableStateOf("")
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Card,
            primary = Accent,
            onBackground = White,
            onSurface = White
        )
    ) {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Black
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 20.dp,
                        vertical = 18.dp
                    )
            ) {

                Text(
                    text = "NGỌC SĨ MUSIC",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "BLACK GLASS • MUSIC PLAYER",
                    fontSize = 11.sp,
                    color = Gray
                )

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Tìm kiếm bài hát...",
                            color = Gray
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Card,
                        focusedContainerColor = Card,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Accent
                    )
                )

                Spacer(
                    modifier = Modifier.height(26.dp)
                )

                Text(
                    text = "ĐANG PHÁT",
                    color = Gray,
                    fontSize = 13.sp
                )

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(
                            Card,
                            RoundedCornerShape(28.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        text = "🎵",
                        fontSize = 72.sp
                    )
                }

                Spacer(
                    modifier = Modifier.height(18.dp)
                )

                Text(
                    text = "Chưa chọn bài hát",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Thư viện của bạn",
                    color = Gray,
                    fontSize = 14.sp
                )

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                LinearProgressIndicator(
                    progress = {
                        0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Card
                )

                Spacer(
                    modifier = Modifier.height(18.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = "↶",
                        fontSize = 30.sp
                    )

                    Text(
                        text = "◀",
                        fontSize = 30.sp
                    )

                    Button(
                        onClick = {
                            playing = !playing
                        },
                        shape = RoundedCornerShape(50.dp)
                    ) {

                        Text(
                            text = if (playing) "Ⅱ" else "▶",
                            fontSize = 25.sp
                        )
                    }

                    Text(
                        text = "▶",
                        fontSize = 30.sp
                    )

                    Text(
                        text = "↷",
                        fontSize = 30.sp
                    )
                }

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {

                    Text(
                        text = "⌂  Trang chủ"
                    )

                    Text(
                        text = "♫  Thư viện",
                        color = Gray
                    )

                    Text(
                        text = "♡  Yêu thích",
                        color = Gray
                    )
                }
            }
        }
    }
}

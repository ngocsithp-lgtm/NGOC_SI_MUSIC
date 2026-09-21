package com.ngocsi.music

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MusicScreen()
        }
    }

    @Composable
    private fun MusicScreen() {
        var isPlaying by remember { mutableStateOf(false) }

        val mediaPlayer = remember {
            MediaPlayer.create(
                this@MainActivity,
                R.raw.viber_message
            )
        }

        DisposableEffect(mediaPlayer) {
            mediaPlayer?.setOnCompletionListener {
                isPlaying = false
                mediaPlayer.seekTo(0)
            }

            onDispose {
                mediaPlayer?.release()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101014)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    text = "NGỌC SĨ MUSIC",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "MUSIC PLAYER",
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(40.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1C1C22))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "🎵",
                        fontSize = 64.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "viber_message.mp3",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isPlaying) "Đang phát nhạc" else "Sẵn sàng phát",
                        color = Color(0xFFBBBBBB),
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Button(
                            onClick = {
                                mediaPlayer?.let {
                                    if (it.isPlaying) {
                                        it.pause()
                                        isPlaying = false
                                    } else {
                                        it.start()
                                        isPlaying = true
                                    }
                                }
                            },
                            enabled = mediaPlayer != null,
                            modifier = Modifier
                                .height(54.dp)
                                .weight(1f),
                            shape = RoundedCornerShape(27.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6750A4)
                            )
                        ) {
                            Text(
                                text = if (isPlaying) {
                                    "⏸  TẠM DỪNG"
                                } else {
                                    "▶  PHÁT NHẠC"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))

                        Button(
                            onClick = {
                                mediaPlayer?.let {
                                    if (it.isPlaying) {
                                        it.pause()
                                    }

                                    it.seekTo(0)
                                    isPlaying = false
                                }
                            },
                            enabled = mediaPlayer != null,
                            modifier = Modifier
                                .height(54.dp)
                                .weight(0.55f),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF303038)
                            )
                        ) {
                            Text(
                                text = "⏹",
                                fontSize = 20.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                Text(
                    text = "NGỌC SĨ MUSIC • VERSION 1.0",
                    color = Color(0xFF66666F),
                    fontSize = 12.sp
                )
            }
        }
    }
}

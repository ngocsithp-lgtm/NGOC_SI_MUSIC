package com.ngocsi.music

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("NGỌC SĨ MUSIC")

            Spacer(modifier = Modifier.height(12.dp))

            Text("🎵 viber_message.mp3")

            Spacer(modifier = Modifier.height(20.dp))

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
                enabled = mediaPlayer != null
            ) {
                Text(
                    if (isPlaying) {
                        "⏸ Tạm dừng"
                    } else {
                        "▶ Phát nhạc"
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                enabled = mediaPlayer != null
            ) {
                Text("⏹ Dừng")
            }

            if (mediaPlayer == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Không tải được viber_message.mp3")
            }
        }
    }
}

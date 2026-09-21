package com.ngocsithp.ngocmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MusicScreen()
        }
    }

    private fun playMusic() {
        val intent = Intent(this, MusicService::class.java)
        intent.action = MusicService.ACTION_PLAY
        startService(intent)
    }

    private fun pauseMusic() {
        val intent = Intent(this, MusicService::class.java)
        intent.action = MusicService.ACTION_PAUSE
        startService(intent)
    }

    private fun stopMusic() {
        val intent = Intent(this, MusicService::class.java)
        intent.action = MusicService.ACTION_STOP
        startService(intent)
    }

    @Composable
    fun MusicScreen() {

        Column {

            Button(
                onClick = {
                    playMusic()
                }
            ) {
                Text("▶ Phát nhạc")
            }

            Button(
                onClick = {
                    pauseMusic()
                }
            ) {
                Text("⏸ Tạm dừng")
            }

            Button(
                onClick = {
                    stopMusic()
                }
            ) {
                Text("⏹ Dừng")
            }
        }
    }
}

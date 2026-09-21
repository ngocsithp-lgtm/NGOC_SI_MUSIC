package com.ngocsi.music

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectToMusicService()

        setContent {
            MusicScreen()
        }
    }

    private fun connectToMusicService() {

        val sessionToken = SessionToken(
            this,
            ComponentName(
                this,
                MusicService::class.java
            )
        )

        val controllerFuture =
            MediaController.Builder(
                this,
                sessionToken
            ).buildAsync()

        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()

                val mediaItem = MediaItem.fromUri(
                    "android.resource://$packageName/raw/viber_message"
                )

                mediaController?.setMediaItem(mediaItem)
                mediaController?.prepare()
            },
            mainExecutor
        )
    }

    private fun playMusic() {
        mediaController?.play()
    }

    private fun pauseMusic() {
        mediaController?.pause()
    }

    private fun stopMusic() {
        mediaController?.stop()
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

    override fun onDestroy() {

        mediaController?.release()
        mediaController = null

        super.onDestroy()
    }
}

package com.ngocsi.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MusicScreen()
        }
    }

    @Composable
    fun MusicScreen() {

        Column {

            Button(
                onClick = {
                    // Phần điều khiển Media3 sẽ kết nối ở bước tiếp theo
                }
            ) {
                Text("▶ Phát nhạc")
            }

            Button(
                onClick = {
                    // Tạm dừng
                }
            ) {
                Text("⏸ Tạm dừng")
            }

            Button(
                onClick = {
                    // Dừng
                }
            ) {
                Text("⏹ Dừng")
            }
        }
    }
}

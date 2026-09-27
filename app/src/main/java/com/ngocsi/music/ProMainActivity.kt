package com.ngocsi.music

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * New PRO shell for the rebuilt information architecture.
 * MainActivity remains available as the proven feature surface during migration.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProMainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var isPlaying by mutableStateOf(false)
    private var title by mutableStateOf("Chưa phát nhạc")
    private var artist by mutableStateOf("NGỌC SĨ MUSIC")
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            syncProgress()
        }

        override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
            title = metadata.title?.toString().orEmpty().ifBlank { "Đang phát" }
            artist = metadata.artist?.toString().orEmpty().ifBlank { "NGỌC SĨ MUSIC" }
            syncProgress()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            mediaItem?.mediaMetadata?.let {
                title = it.title?.toString().orEmpty().ifBlank { "Đang phát" }
                artist = it.artist?.toString().orEmpty().ifBlank { "NGỌC SĨ MUSIC" }
            }
            syncProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            syncProgress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectController()
        setContent {
            ProShell(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                onMusic = { openLegacy("library") },
                onYouTube = { openLegacy("youtube") },
                onDrive = { openLegacy("drive") },
                onRadio = { openLegacy("radio") },
                onTv = { openLegacy("tv") },
                onMap = { openLegacy("map") },
                onPlaylists = { openLegacy("playlists") },
                onQueue = { openLegacy("queue") },
                onSettings = { openLegacy("settings") },
                onFullPlayer = { openLegacy("player") },
                onPrevious = {
                    controller?.let {
                        if (it.currentPosition > 3_000L) it.seekTo(0L)
                        else it.seekToPreviousMediaItem()
                        it.play()
                    }
                },
                onTogglePlayback = {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                },
                onNext = {
                    controller?.let {
                        it.seekToNextMediaItem()
                        it.play()
                    }
                },
                onSeek = { target ->
                    controller?.seekTo(target.coerceAtLeast(0L))
                    syncProgress()
                },
                onProgressTick = { syncProgress() }
            )
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching {
                controller = future.get()
                controller?.addListener(listener)
                controller?.let {
                    syncProgress()
                    isPlaying = it.isPlaying
                    it.currentMediaItem?.mediaMetadata?.let { md ->
                        title = md.title?.toString().orEmpty().ifBlank { "Đang phát" }
                        artist = md.artist?.toString().orEmpty().ifBlank { "NGỌC SĨ MUSIC" }
                    }
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun syncProgress() {
        controller?.let {
            position = it.currentPosition.coerceAtLeast(0L)
            duration = it.duration.takeIf { value -> value > 0L } ?: 0L
            isPlaying = it.isPlaying
        }
    }

    private fun openLegacy(destination: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("pro_destination", destination)
            }
        )
    }

    override fun onDestroy() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

private data class ProFeature(
    val icon: String,
    val title: String,
    val subtitle: String,
    val action: () -> Unit
)

@Composable
private fun ProShell(
    title: String,
    artist: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onMusic: () -> Unit,
    onYouTube: () -> Unit,
    onDrive: () -> Unit,
    onRadio: () -> Unit,
    onTv: () -> Unit,
    onMap: () -> Unit,
    onPlaylists: () -> Unit,
    onQueue: () -> Unit,
    onSettings: () -> Unit,
    onFullPlayer: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onProgressTick: () -> Unit
) {
    LaunchedEffect(isPlaying, duration) {
        while (isActive && isPlaying) {
            delay(500L)
            onProgressTick()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF07080C),
            surface = Color(0xFF11131A),
            primary = Color(0xFFB18CFF),
            secondary = Color(0xFF7DD3FC)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color(0xFF07080C)
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("NGỌC SĨ MUSIC", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                        Text(
                            "MUSIC • VIDEO • RADIO • TV • MAP",
                            color = Color(0xFF8E90A0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("PRO", color = Color(0xFFCDBAFF), fontWeight = FontWeight.Black, fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171922))
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clickable(onClick = onFullPlayer),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF342650)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(if (isPlaying) "⏸" else "♫", color = Color.White, fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(artist, color = Color(0xFF9698A8), fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    if (duration > 0L) {
                        Slider(
                            value = position.coerceIn(0L, duration).toFloat(),
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..duration.toFloat(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatProTime(position), color = Color(0xFF858794), fontSize = 11.sp)
                            Text(formatProTime(duration), color = Color(0xFF858794), fontSize = 11.sp)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏮", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable(onClick = onPrevious))
                        Text(
                            if (isPlaying) "⏸" else "▶",
                            color = Color(0xFFCDBAFF),
                            fontSize = 28.sp,
                            modifier = Modifier.clickable(onClick = onTogglePlayback)
                        )
                        Text("⏭", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable(onClick = onNext))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("KHÁM PHÁ", color = Color(0xFF999BA9), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))

                val features = listOf(
                    ProFeature("♫", "Nhạc", "Thiết bị & thư viện", onMusic),
                    ProFeature("▶", "YouTube", "Tìm kiếm & phát", onYouTube),
                    ProFeature("☁", "Google Drive", "Thư mục & bài hát", onDrive),
                    ProFeature("◉", "Radio", "Đài Việt Nam", onRadio),
                    ProFeature("▣", "TV", "Nguồn truyền hình", onTv),
                    ProFeature("⌖", "Bản đồ", "Bản đồ & tìm kiếm", onMap),
                    ProFeature("♬", "Playlist", "Yêu thích & danh sách", onPlaylists),
                    ProFeature("☷", "Hàng đợi", "Danh sách đang phát", onQueue),
                    ProFeature("⚙", "Cài đặt", "Hệ thống & phát nền", onSettings)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(features) { feature ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = feature.action),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF12141B))
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    color = Color(0xFF29213D)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(feature.icon, color = Color(0xFFD8C9FF), fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(feature.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(feature.subtitle, color = Color(0xFF858794), fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onFullPlayer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("MỞ TRÌNH PHÁT ĐẦY ĐỦ")
                }
            }
        }
    }
}

private fun formatProTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

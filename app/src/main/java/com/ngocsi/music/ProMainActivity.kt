package com.ngocsi.music

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Brush
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
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
            background = Color(0xFF050711),
            surface = Color(0xFF10131E),
            primary = Color(0xFF62E8FF),
            secondary = Color(0xFFB995FF)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color(0xFF050711)
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "NGỌC SĨ MUSIC",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "ÂM NHẠC • VIDEO • RADIO • TV • MAP",
                            color = Color(0xFF8A90A6),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF122E39),
                        modifier = Modifier.border(
                            1.dp,
                            Color(0xFF2C7080),
                            RoundedCornerShape(50)
                        )
                    ) {
                        Text(
                            "● PRO",
                            color = Color(0xFF7FEAFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onFullPlayer),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF101C3A),
                                        Color(0xFF15234C),
                                        Color(0xFF21163D)
                                    )
                                ),
                                RoundedCornerShape(26.dp)
                            )
                            .padding(18.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(66.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF203D66)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (isPlaying) "⏸" else "♫",
                                            color = Color(0xFFBDF9FF),
                                            fontSize = 27.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.size(13.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "ĐANG PHÁT",
                                        color = Color(0xFF75E8FF),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        title,
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 2
                                    )
                                    Text(
                                        artist,
                                        color = Color(0xFFA9AFBF),
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            if (duration > 0L) {
                                Spacer(Modifier.height(8.dp))
                                Slider(
                                    value = position.coerceIn(0L, duration).toFloat(),
                                    onValueChange = { onSeek(it.toLong()) },
                                    valueRange = 0f..duration.toFloat(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatProTime(position), color = Color(0xFF858CA0), fontSize = 10.sp)
                                    Text(formatProTime(duration), color = Color(0xFF858CA0), fontSize = 10.sp)
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⏮", color = Color.White, fontSize = 23.sp, modifier = Modifier.clickable(onClick = onPrevious))
                                Text(
                                    if (isPlaying) "⏸" else "▶",
                                    color = Color(0xFF7CEBFF),
                                    fontSize = 31.sp,
                                    modifier = Modifier.clickable(onClick = onTogglePlayback)
                                )
                                Text("⏭", color = Color.White, fontSize = 23.sp, modifier = Modifier.clickable(onClick = onNext))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSettings),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0C1720),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFF1E4650)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = Color(0xFF123340)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("◉", color = Color(0xFF7FEAFF), fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "PHÁT NỀN • KHÓA MÀN HÌNH",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "Media3 • MediaSession • điều khiển từ thông báo và tai nghe",
                                color = Color(0xFF788B95),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            "›",
                            color = Color(0xFF6EEBFF),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "TRUY CẬP NHANH",
                    color = Color(0xFF8F96AA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(7.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(
                        listOf(
                            ProQuick("♫", "Nhạc", onMusic),
                            ProQuick("▶", "YouTube", onYouTube),
                            ProQuick("◉", "Radio", onRadio),
                            ProQuick("▣", "TV", onTv)
                        )
                    ) { quick ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF111827),
                            modifier = Modifier
                                .border(1.dp, Color(0xFF25334A), RoundedCornerShape(18.dp))
                                .clickable(onClick = quick.action)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(quick.icon, color = Color(0xFF85EDFF), fontSize = 17.sp)
                                Spacer(Modifier.size(7.dp))
                                Text(quick.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(13.dp))
                Text(
                    "THƯ VIỆN & TIỆN ÍCH",
                    color = Color(0xFF8F96AA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(7.dp))

                val features = listOf(
                    ProFeature("☁", "Google Drive", "Thư mục & bài hát", onDrive),
                    ProFeature("⌖", "Bản đồ", "Bản đồ & tìm kiếm", onMap),
                    ProFeature("♬", "Playlist", "Yêu thích & danh sách", onPlaylists),
                    ProFeature("☷", "Hàng đợi", "Đang chờ phát", onQueue),
                    ProFeature("◫", "Trình phát", "Điều khiển đầy đủ", onFullPlayer),
                    ProFeature("⚙", "Cài đặt", "Phát nền • hẹn giờ", onSettings)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(features) { feature ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = feature.action),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1320))
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(13.dp),
                                    color = Color(0xFF162840)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(feature.icon, color = Color(0xFF89EEFF), fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        feature.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        feature.subtitle,
                                        color = Color(0xFF7F879B),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = onFullPlayer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF55DDF4),
                        contentColor = Color(0xFF041018)
                    )
                ) {
                    Text("MỞ TRÌNH PHÁT ĐẦY ĐỦ", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private data class ProQuick(
    val icon: String,
    val title: String,
    val action: () -> Unit
)

private fun formatProTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

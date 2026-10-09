package com.ngocsi.music

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * New PRO shell for the rebuilt information architecture.
 * MainActivity remains available as the proven feature surface during migration.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProMainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var controllerConnecting = false
    private var isPlaying by mutableStateOf(false)
    private var title by mutableStateOf("Chưa phát nhạc")
    private var artist by mutableStateOf("NGỌC SĨ MUSIC")
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var artworkUri by mutableStateOf<android.net.Uri?>(null)

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
        // Keep the PRO shell independent from Media3/service startup.
        // The controller is connected lazily on the first playback/seek action,
        // so a service or codec problem cannot block the launcher screen.
        setContent {
            ProShell(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                artworkUri = artworkUri,
                onMusic = { openLegacy("library") },
                onYouTube = { openLegacy("youtube") },
                onDrive = { openLegacy("drive") },
                onRadio = { openLegacy("radio") },
                onTv = { openLegacy("tv") },
                onPlaylists = { openLegacy("playlists") },
                onQueue = { openLegacy("queue") },
                onSettings = { openLegacy("settings") },
                onFullPlayer = { openLegacy("player") },
                onPrevious = {
                    withController { c ->
                        if (c.currentPosition > 3_000L) c.seekTo(0L)
                        else c.seekToPreviousMediaItem()
                        c.play()
                    }
                },
                onTogglePlayback = {
                    withController { c ->
                        if (c.isPlaying) c.pause() else c.play()
                    }
                },
                onNext = {
                    withController { c ->
                        c.seekToNextMediaItem()
                        c.play()
                    }
                },
                onSeek = { target ->
                    withController { c ->
                        c.seekTo(target.coerceAtLeast(0L))
                        syncProgress()
                    }
                },
                onProgressTick = { syncProgress() }
            )
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val existing = controller
        if (existing != null) {
            action(existing)
            return
        }
        connectController {
            controller?.let(action)
        }
    }

    private fun connectController(onReady: (() -> Unit)? = null) {
        if (controller != null) {
            onReady?.invoke()
            return
        }
        if (controllerConnecting) return
        controllerConnecting = true

        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = runCatching {
            MediaController.Builder(this, token).buildAsync()
        }.getOrElse {
            controllerConnecting = false
            return
        }

        future.addListener({
            runOnUiThread {
                try {
                    controller = future.get()
                    controller?.addListener(listener)
                    controller?.let {
                        syncProgress()
                        isPlaying = it.isPlaying
                        it.currentMediaItem?.mediaMetadata?.let { md ->
                            title = md.title?.toString().orEmpty().ifBlank { "Đang phát" }
                            artist = md.artist?.toString().orEmpty().ifBlank { "NGỌC SĨ MUSIC" }
                            artworkUri = md.artworkUri
                        }
                    }
                    onReady?.invoke()
                } catch (_: Throwable) {
                    controller = null
                } finally {
                    controllerConnecting = false
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
    artworkUri: android.net.Uri?,
    onMusic: () -> Unit,
    onYouTube: () -> Unit,
    onDrive: () -> Unit,
    onRadio: () -> Unit,
    onTv: () -> Unit,
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
    var selectedNav by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var showMore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(isPlaying, duration) {
        while (isActive && isPlaying) {
            delay(500L)
            onProgressTick()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF06070B),
            surface = Color(0xFF11131A),
            surfaceVariant = Color(0xFF181B24),
            primary = Color(0xFF8BE9FF),
            secondary = Color(0xFFB58CFF)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color(0xFF06070B)
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                ) {
                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "NGỌC SĨ MUSIC",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "MUSIC • VIDEO • RADIO",
                                color = Color(0xFF858B9C),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF132633),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color(0xFF28586A)
                            )
                        ) {
                            Text(
                                "PRO",
                                color = Color(0xFF91ECFF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Primary player surface: one clear visual hierarchy and one dominant action.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onFullPlayer),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Transparent
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFF17253A),
                                            Color(0xFF15172A),
                                            Color(0xFF231A37)
                                        )
                                    ),
                                    RoundedCornerShape(24.dp)
                                )
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(74.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xFF202E48),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Color(0xFF3F587D)
                                        )
                                    ) {
                                        ProArtwork(artworkUri = artworkUri, isPlaying = isPlaying)
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "ĐANG PHÁT",
                                            color = Color(0xFF8CEBFF),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.4.sp
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            title,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            artist,
                                            color = Color(0xFFA6ABBA),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (duration > 0L) {
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
                                        Text(
                                            formatProTime(position),
                                            color = Color(0xFF7E8494),
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            formatProTime(duration),
                                            color = Color(0xFF7E8494),
                                            fontSize = 10.sp
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        "Nhấn để mở trình phát đầy đủ",
                                        color = Color(0xFF7A8191),
                                        fontSize = 10.sp
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProControlButton("⏮", onPrevious, false)
                                    ProMainPlayButton(isPlaying, onTogglePlayback)
                                    ProControlButton("⏭", onNext, false)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "TRUY CẬP NHANH",
                        color = Color(0xFF858B9C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(Modifier.height(7.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProQuickTile("♫", "Thư viện", onMusic, Modifier.weight(1f))
                        ProQuickTile("▶", "YouTube", onYouTube, Modifier.weight(1f))
                        ProQuickTile("◉", "Radio", onRadio, Modifier.weight(1f))
                        ProQuickTile("▣", "TV", onTv, Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(10.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onQueue),
                        shape = RoundedCornerShape(17.dp),
                        color = Color(0xFF0D1118),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color(0xFF202632)
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("☷", color = Color(0xFFB796FF), fontSize = 17.sp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "HÀNG ĐỢI",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    "Chạm để mở danh sách đang chờ phát",
                                    color = Color(0xFF777E8D),
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                "›",
                                color = Color(0xFF9DEFFF),
                                fontSize = 22.sp,
                                                                )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (showMore) {
                        Text(
                            "TIỆN ÍCH",
                            color = Color(0xFF858B9C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProUtilityTile("☁", "Drive", onDrive, Modifier.weight(1f))
                            ProUtilityTile("♬", "Playlist", onPlaylists, Modifier.weight(1f))
                            ProUtilityTile("⚙", "Cài đặt", onSettings, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProUtilityTile("▣", "TV", onTv, Modifier.weight(1f))
                            ProUtilityTile("▶", "Player", onFullPlayer, Modifier.weight(1f))
                            Spacer(Modifier.weight(1f))
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0B0D12),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProBottomNavItem(
                            icon = "⌂",
                            label = "Trang chủ",
                            selected = selectedNav == 0,
                            onClick = { selectedNav = 0 }
                        )
                        ProBottomNavItem(
                            icon = "♫",
                            label = "Nhạc",
                            selected = selectedNav == 1,
                            onClick = {
                                selectedNav = 1
                                onMusic()
                            }
                        )
                        ProBottomNavItem(
                            icon = "▶",
                            label = "YouTube",
                            selected = selectedNav == 2,
                            onClick = {
                                selectedNav = 2
                                onYouTube()
                            }
                        )
                        ProBottomNavItem(
                            icon = "◉",
                            label = "Radio",
                            selected = selectedNav == 3,
                            onClick = {
                                selectedNav = 3
                                onRadio()
                            }
                        )
                        ProBottomNavItem(
                            icon = "＋",
                            label = "Thêm",
                            selected = showMore,
                            onClick = {
                                selectedNav = 4
                                showMore = !showMore
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProArtwork(artworkUri: android.net.Uri?, isPlaying: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(artworkUri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(artworkUri) {
        bitmap = null
        bitmap = artworkUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    // Album covers can be several thousand pixels wide. Decode a
                    // bounded preview instead of allocating the full source image.
                    val bounds = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    }

                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        return@runCatching null
                    }

                    val maxDimension = 512
                    var sample = 1
                    while (
                        bounds.outWidth / sample > maxDimension ||
                        bounds.outHeight / sample > maxDimension
                    ) {
                        sample *= 2
                    }

                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                }.getOrNull()
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (isPlaying) "♫" else "♪",
                color = Color(0xFF9EEFFF),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProMainPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(54.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF8DEEFF),
        shadowElevation = 7.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (isPlaying) "⏸" else "▶",
                color = Color(0xFF061018),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun ProControlButton(icon: String, onClick: () -> Unit, selected: Boolean) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) Color(0xFF1A2B3A) else Color(0xFF111721)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                icon,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProQuickTile(
    icon: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFF10151D),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202938))
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, color = Color(0xFF9FEFFF), fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProUtilityTile(
    icon: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F131B),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2733))
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(icon, color = Color(0xFFB796FF), fontSize = 16.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProBottomNavItem(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            icon,
            color = if (selected) Color(0xFF8DEEFF) else Color(0xFF717786),
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Normal
        )
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF717786),
            fontSize = 8.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
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

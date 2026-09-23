package com.ngocsi.music

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.max

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri
)

class MainActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private val songs = mutableStateListOf<Song>()
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var errorMessage by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs() else {
                errorMessage = "Cần cấp quyền đọc nhạc để quét thư viện."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NgocSiMusicApp() }
        requestMusicPermissionIfNeeded()
    }

    private fun requestMusicPermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadSongs() {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"

        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += Song(
                    id = id,
                    title = cursor.getString(titleCol).orEmpty().ifBlank { "Không có tên" },
                    artist = cursor.getString(artistCol).orEmpty().ifBlank { "Nghệ sĩ không rõ" },
                    duration = cursor.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }

        songs.clear()
        songs.addAll(result)
        errorMessage = if (songs.isEmpty()) "Chưa tìm thấy file nhạc trong thiết bị." else null
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        val song = songs[index]
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, song.uri)
                setOnPreparedListener {
                    it.start()
                    this@MainActivity.isPlaying = true
                    this@MainActivity.currentIndex = index
                }
                setOnCompletionListener {
                    if (index + 1 < songs.size) play(index + 1) else {
                        this@MainActivity.isPlaying = false
                        this@MainActivity.position = 0
                    }
                }
                setOnErrorListener { _, _, _ ->
                    this@MainActivity.isPlaying = false
                    this@MainActivity.errorMessage = "Không thể phát: " + song.title
                    true
                }
                prepareAsync()
            }
            currentIndex = index
            position = 0
            errorMessage = null
        } catch (e: Exception) {
            isPlaying = false
            errorMessage = "Lỗi mở file nhạc."
        }
    }

    private fun togglePlayPause() {
        val p = player
        if (p == null) {
            if (songs.isNotEmpty()) play(if (currentIndex >= 0) currentIndex else 0)
        } else {
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
            } else {
                p.start()
                isPlaying = true
            }
        }
    }

    private fun next() {
        if (songs.isEmpty()) return
        val nextIndex = if (currentIndex + 1 < songs.size) currentIndex + 1 else 0
        play(nextIndex)
    }

    private fun previous() {
        if (songs.isEmpty()) return
        val previousIndex = if (currentIndex > 0) currentIndex - 1 else songs.lastIndex
        play(previousIndex)
    }

    private fun stop() {
        player?.pause()
        player?.seekTo(0)
        isPlaying = false
        position = 0
    }

    private fun seekTo(value: Long) {
        player?.let {
            val safe = value.coerceIn(0L, max(0L, it.duration.toLong()))
            it.seekTo(safe.toInt())
            position = safe
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    @Composable
    private fun NgocSiMusicApp() {
        val currentSong = songs.getOrNull(currentIndex)
        val duration = currentSong?.duration ?: 0L

        LaunchedEffect(isPlaying, currentIndex) {
            while (isPlaying) {
                player?.let { if (it.isPlaying) position = it.currentPosition.toLong() }
                delay(500)
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF0B0B0F),
                surface = Color(0xFF15151B),
                primary = Color(0xFF9B7BFF)
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Header()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        PlayerCard(currentSong, duration)
                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "THƯ VIỆN • " + songs.size + " bài",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { loadSongs() }) { Text("LÀM MỚI") }
                        }

                        errorMessage?.let {
                            Text(
                                it,
                                color = Color(0xFFFFB4AB),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                            SongRow(song, index, index == currentIndex)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "NGỌC SĨ MUSIC",
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "MUSIC PLAYER 2.0",
                color = Color(0xFFAAA5B8),
                fontSize = 12.sp,
                letterSpacing = 3.sp
            )
        }
    }

    @Composable
    private fun PlayerCard(song: Song?, duration: Long) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF181820))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF2B2639)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♫", color = Color(0xFFC8B7FF), fontSize = 38.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "Chưa chọn bài hát",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song?.artist ?: "Chọn một bài trong thư viện",
                        color = Color(0xFFAAAAB5),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Slider(
                value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f,
                onValueChange = { seekTo(it.toLong()) },
                valueRange = 0f..max(1L, duration).toFloat(),
                enabled = song != null
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color(0xFF9999A5), fontSize = 12.sp)
                Text(formatTime(duration), color = Color(0xFF9999A5), fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallControl("⏮", ::previous)
                Button(
                    onClick = ::togglePlayPause,
                    enabled = song != null || songs.isNotEmpty(),
                    modifier = Modifier.height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7657D8))
                ) {
                    Text(if (isPlaying) "⏸" else "▶", fontSize = 22.sp)
                }
                SmallControl("⏭", ::next)
                SmallControl("⏹", ::stop)
            }
        }
    }

    @Composable
    private fun SmallControl(label: String, action: () -> Unit) {
        FilledTonalButton(
            onClick = action,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(label, fontSize = 18.sp)
        }
    }

    @Composable
    private fun SongRow(song: Song, index: Int, selected: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Color(0xFF252033) else Color(0xFF141419))
                .clickable { play(index) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                String.format("%02d", index + 1),
                color = if (selected) Color(0xFFC8B7FF) else Color(0xFF777783),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    color = Color(0xFF8F8F9A),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(formatTime(song.duration), color = Color(0xFF858591), fontSize = 12.sp)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = max(0L, milliseconds) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

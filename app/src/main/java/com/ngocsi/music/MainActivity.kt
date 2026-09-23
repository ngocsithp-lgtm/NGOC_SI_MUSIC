package com.ngocsi.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlin.math.max

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri, val source: String = "Thiết bị")

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val songs = mutableStateListOf<Song>()
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var errorMessage by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf("")
    private var youtubeQuery by mutableStateOf("")
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private val favorites = mutableStateMapOf<Long, Boolean>()
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadSongs() else errorMessage = "Cần cấp quyền đọc nhạc để quét thư viện."
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val drivePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importDriveSongs(uris)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: -1
            if (index >= 0) currentIndex = index
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_OFF) {
                isPlaying = false
                position = 0L
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            isPlaying = false
            errorMessage = "Không thể phát bài hát."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        loadSavedState()
        setContent { NgocSiMusicApp() }
        requestMusicPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
        connectController()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                shuffleEnabled = controller?.shuffleModeEnabled == true
                repeatMode = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
                isPlaying = controller?.isPlaying == true
                currentIndex = controller?.currentMediaItemIndex ?: -1
            } catch (e: Exception) {
                errorMessage = "Không kết nối được trình phát."
            }
        }, MoreExecutors.directExecutor())
    }

    private fun requestMusicPermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else permissionLauncher.launch(permission)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadSongs() {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += Song(id, cursor.getString(titleCol).orEmpty().ifBlank { "Không có tên" },
                    cursor.getString(artistCol).orEmpty().ifBlank { "Nghệ sĩ không rõ" },
                    cursor.getLong(durationCol), ContentUris.withAppendedId(collection, id), "Thiết bị")
            }
        }
        val savedOnlineUris = prefs.getStringSet("drive_uris", emptySet()) ?: emptySet()
        val existing = result.map { it.uri.toString() }.toMutableSet()
        savedOnlineUris.forEach { raw ->
            val uri = Uri.parse(raw)
            if (existing.add(raw)) result += songFromUri(uri)
        }
        songs.clear()
        songs.addAll(result)
        errorMessage = if (songs.isEmpty()) "Chưa tìm thấy file nhạc trong thiết bị." else null
        controller?.let { c ->
            if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
                c.setMediaItems(songs.map { mediaItemFor(it) })
                c.prepare()
            }
        }
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        val c = controller ?: run { errorMessage = "Trình phát đang khởi động, thử lại sau."; return }
        if (c.mediaItemCount != songs.size) {
            c.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
            c.prepare()
        }
        currentIndex = index
        c.seekToDefaultPosition(index)
        c.play()
        errorMessage = null
    }

    private fun mediaItemFor(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build()
            )
            .build()
    }

    private fun importDriveSongs(uris: List<Uri>) {
        val saved = prefs.getStringSet("drive_uris", emptySet()).toMutableSet()
        var added = 0
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            val raw = uri.toString()
            if (saved.add(raw) && songs.none { it.uri == uri }) {
                songs.add(songFromUri(uri))
                added++
            }
        }
        prefs.edit().putStringSet("drive_uris", saved).apply()
        errorMessage = if (added > 0) "Đã thêm $added bài từ Google Drive." else "Các bài đã chọn đã có trong thư viện."
        syncControllerQueue()
    }

    private fun songFromUri(uri: Uri): Song {
        var title = "Nhạc online"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                title = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    .substringBeforeLast(".").ifBlank { "Nhạc online" }
            }
        }
        return Song(
            id = -kotlin.math.abs(uri.toString().hashCode().toLong()),
            title = title,
            artist = "Google Drive",
            duration = 0L,
            uri = uri,
            source = "Google Drive"
        )
    }

    private fun openDrivePicker() {
        drivePickerLauncher.launch(arrayOf("audio/*"))
    }

    private fun searchYouTube() {
        val q = youtubeQuery.trim()
        if (q.isBlank()) {
            errorMessage = "Nhập tên bài hát để tìm trên YouTube."
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
    }

    private fun syncControllerQueue() {
        controller?.let { c ->
            if (songs.isNotEmpty()) {
                val selectedUri = c.currentMediaItem?.localConfiguration?.uri
                c.setMediaItems(songs.map { mediaItemFor(it) })
                c.prepare()
                val index = selectedUri?.let { uri -> songs.indexOfFirst { it.uri == uri } } ?: -1
                if (index >= 0) c.seekToDefaultPosition(index)
            }
        }
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
            c.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
            c.prepare()
            c.play()
        } else if (c.isPlaying) c.pause() else c.play()
    }

    private fun next() { controller?.seekToNextMediaItem(); controller?.play() }
    private fun previous() { controller?.seekToPreviousMediaItem(); controller?.play() }
    private fun stop() { controller?.pause(); controller?.seekTo(0L); position = 0L }

    private fun seekTo(value: Long) {
        controller?.let {
            val safe = value.coerceIn(0L, max(0L, it.duration))
            it.seekTo(safe)
            position = safe
        }
    }

    private fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        controller?.shuffleModeEnabled = shuffleEnabled
        savePlayerPreferences()
    }

    private fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller?.repeatMode = repeatMode
        savePlayerPreferences()
    }

    private fun loadSavedState() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()).orEmpty()
        savedFavorites.forEach { it.toLongOrNull()?.let { id -> favorites[id] = true } }
        shuffleEnabled = prefs.getBoolean("shuffle", false)
        repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
    }

    private fun savePlayerPreferences() {
        prefs.edit()
            .putStringSet("favorites", favorites.filterValues { it }.keys.map(Long::toString).toSet())
            .putBoolean("shuffle", shuffleEnabled)
            .putInt("repeat", repeatMode)
            .apply()
    }

    private fun toggleFavorite(song: Song) {
        favorites[song.id] = !(favorites[song.id] ?: false)
        savePlayerPreferences()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onDestroy()
    }

    @Composable
    private fun NgocSiMusicApp() {
        val currentSong = songs.getOrNull(currentIndex)
        val filteredSongs = remember(searchQuery, songs.size) {
            val q = searchQuery.trim()
            if (q.isBlank()) songs.toList() else songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.source.contains(q, true) }
        }
        LaunchedEffect(isPlaying, currentIndex) {
            while (isPlaying) {
                controller?.let { position = max(0L, it.currentPosition) }
                delay(500)
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0B0F), surface = Color(0xFF15151B), primary = Color(0xFF9B7BFF))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) {
                Column(Modifier.fillMaxSize()) {
                    Header()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, placeholder = { Text("Tìm bài hát hoặc nghệ sĩ") }, leadingIcon = { Text("🔎") }, shape = RoundedCornerShape(16.dp))
                        Spacer(Modifier.height(12.dp))
                        PlayerCard(currentSong)
                        Spacer(Modifier.height(12.dp))
                        OnlineSourcesCard()
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("THƯ VIỆN • ${songs.size} bài", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = ::loadSongs) { Text("LÀM MỚI") }
                        }
                        errorMessage?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(filteredSongs, key = { _, song -> song.id }) { _, song ->
                            val realIndex = songs.indexOfFirst { it.id == song.id }
                            SongRow(song, realIndex, realIndex == currentIndex)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("NGỌC SĨ MUSIC", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text("MUSIC PLAYER 2.5 PRO MAX", color = Color(0xFFAAA5B8), fontSize = 12.sp, letterSpacing = 3.sp)
        }
    }

    @Composable
    private fun OnlineSourcesCard() {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF14141B)).padding(14.dp)
        ) {
            Text("KHO NHẠC ONLINE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Google Drive là thư viện online chính. YouTube dùng trình phát chính thức.",
                color = Color(0xFF8F8F9A), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::openDrivePicker, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)) { Text("☁ Drive") }
                OutlinedButton(onClick = ::searchYouTube, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)) { Text("▶ YouTube") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = youtubeQuery, onValueChange = { youtubeQuery = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Tìm nhạc trên YouTube") }, shape = RoundedCornerShape(14.dp))
        }
    }

    @Composable
    private fun PlayerCard(song: Song?) {
        val duration = song?.duration ?: 0L
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF181820)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(74.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2B2639)), contentAlignment = Alignment.Center) {
                    Text("♫", color = Color(0xFFC8B7FF), fontSize = 38.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Chưa chọn bài hát", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(song?.artist ?: "Chọn một bài trong thư viện", color = Color(0xFFAAAAB5), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            Slider(value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f, onValueChange = { seekTo(it.toLong()) },
                valueRange = 0f..max(1L, duration).toFloat(), enabled = song != null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color(0xFF9999A5), fontSize = 12.sp)
                Text(formatTime(duration), color = Color(0xFF9999A5), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SmallControl(if (shuffleEnabled) "🔀" else "⇄", ::toggleShuffle, shuffleEnabled)
                SmallControl("⏮", ::previous)
                Button(onClick = ::togglePlayPause, enabled = song != null || songs.isNotEmpty(), modifier = Modifier.size(54.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7657D8))) { Text(if (isPlaying) "⏸" else "▶", fontSize = 22.sp) }
                SmallControl("⏭", ::next)
                SmallControl(when (repeatMode) { Player.REPEAT_MODE_ONE -> "🔂"; Player.REPEAT_MODE_ALL -> "🔁"; else -> "↻" }, ::cycleRepeat, repeatMode != Player.REPEAT_MODE_OFF)
            }
        }
    }

    @Composable
    private fun SmallControl(label: String, action: () -> Unit, active: Boolean = false) {
        FilledTonalButton(onClick = action, modifier = Modifier.size(48.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (active) Color(0xFF4A396F) else Color(0xFF25252D))) {
            Text(label, fontSize = 17.sp)
        }
    }

    @Composable
    private fun SongRow(song: Song, index: Int, selected: Boolean) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0xFF252033) else Color(0xFF141419))
            .clickable { play(index) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("%02d", index + 1), color = if (selected) Color(0xFFC8B7FF) else Color(0xFF777783),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${song.artist} • ${song.source}", color = Color(0xFF8F8F9A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { toggleFavorite(song) }) {
                Text(if (favorites[song.id] == true) "♥" else "♡", color = if (favorites[song.id] == true) Color(0xFFFF6B81) else Color(0xFF777783), fontSize = 22.sp)
            }
            Text(formatTime(song.duration), color = Color(0xFF858591), fontSize = 12.sp)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = max(0L, milliseconds) / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

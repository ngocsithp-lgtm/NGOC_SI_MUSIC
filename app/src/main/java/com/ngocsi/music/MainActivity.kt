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
import android.graphics.BitmapFactory
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.SharedPreferences
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.math.min
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.max

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri, val source: String = "Thiết bị", val albumId: Long = -1L)

data class JamendoTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val audioUrl: String,
    val imageUrl: String
)

data class AudiusTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val streamUrl: String,
    val imageUrl: String
)

data class OnlineSearchItem(
    val source: String,
    val index: Int,
    val title: String,
    val artist: String,
    val duration: Long
)

data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String
)

data class OnlineFavoriteMeta(
    val source: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val streamUrl: String,
    val imageUrl: String
)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val songs = mutableStateListOf<Song>()
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var errorMessage by mutableStateOf<String?>(null)
    private var searchQuery by mutableStateOf("")
    private var jamendoQuery by mutableStateOf("")
    private val jamendoTracks = mutableStateListOf<JamendoTrack>()
    private var jamendoLoading by mutableStateOf(false)
    private val audiusTracks = mutableStateListOf<AudiusTrack>()
    private val onlineFavorites = mutableStateListOf<String>()
    private val onlineFavoriteSet = mutableSetOf<String>()
    private var audiusLoading by mutableStateOf(false)
    private var onlineSearchActive by mutableStateOf(false)
    private var onlineHubTab by mutableStateOf("Tất cả")
    private var onlineSort by mutableStateOf("Tên A-Z")
    private var onlineVisibleCount by mutableIntStateOf(30)
    private val youtubeHistory = mutableStateListOf<String>()
    private val youtubeTracks = mutableStateListOf<YouTubeTrack>()
    private val youtubeFavoriteSet = mutableStateMapOf<String, Boolean>()
    private var youtubeLoading by mutableStateOf(false)
    private var youtubeSelectedVideoId by mutableStateOf<String?>(null)
    private var onlineUrl by mutableStateOf("")
    private var selectedLibrary by mutableStateOf("Tất cả")
    private var libraryView by mutableStateOf("Bài hát")
    private var showQueue by mutableStateOf(false) // #145 queue upgrade
    private var selectedSection by mutableStateOf("Trang chủ")
    private var showNowPlaying by mutableStateOf(false)
    private var showYoutube by mutableStateOf(false)
    private var youtubeQuery by mutableStateOf("")
    private var isVoiceSearching by mutableStateOf(false)
    private var speechRecognizer: SpeechRecognizer? = null
    private var showSleepTimer by mutableStateOf(false)
    private var sleepMinutes by mutableIntStateOf(0)
    private var lastSongUri by mutableStateOf<String?>(null)
    private var savedPosition by mutableLongStateOf(0L)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private val favorites = mutableStateMapOf<Long, Boolean>()
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadSongs() else errorMessage = "Cần cấp quyền đọc nhạc để quét thư viện."
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startVoiceRecognition()
        } else {
            isVoiceSearching = false
            errorMessage = "Cần cấp quyền microphone để tìm kiếm bằng giọng nói."
        }
    }

    private val speechActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVoiceSearching = false
        if (result.resultCode != RESULT_OK) {
            errorMessage = "Chưa nhận được giọng nói. Hãy bấm microphone và nói lại tên bài hát."
            return@registerForActivityResult
        }

        val query = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()

        if (query.isBlank()) {
            errorMessage = "Không nhận được nội dung giọng nói. Hãy thử lại."
            return@registerForActivityResult
        }

        jamendoQuery = query
        onlineSearchActive = true
        jamendoTracks.clear()
        audiusTracks.clear()
        errorMessage = null
        searchJamendo()
        searchAudius(query)
    }
    private val drivePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importDriveSongs(uris)
    }

    private val driveFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importDriveFolder(uri)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            savePlaybackState()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Media3's queue index can differ from the library index when shuffle
            // is enabled. Resolve the active item by URI instead of assuming the
            // two indexes are identical.
            val activeUri = mediaItem?.localConfiguration?.uri?.toString()
                ?: controller?.currentMediaItem?.localConfiguration?.uri?.toString()
            val libraryIndex = activeUri?.let { uri ->
                songs.indexOfFirst { it.uri.toString() == uri }
            } ?: -1

            if (libraryIndex >= 0) {
                currentIndex = libraryIndex
                lastSongUri = activeUri
                savedPosition = 0L
                position = 0L
                savePlaybackState()
            }
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleEnabled = shuffleModeEnabled
            savePlayerPreferences()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            this@MainActivity.repeatMode = repeatMode
            savePlayerPreferences()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_OFF) {
                isPlaying = false
                position = 0L
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            isPlaying = false
            errorMessage = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Mất kết nối mạng khi phát nhạc online."
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "Luồng âm thanh không được hỗ trợ hoặc URL không phải luồng nhạc trực tiếp."
                else -> "Không thể phát bài hát. Hãy kiểm tra URL và định dạng âm thanh."
            }
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

    private var usingOnDeviceRecognizer = false

    private fun cleanupSpeechRecognizer() {
        val recognizer = speechRecognizer
        speechRecognizer = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        isVoiceSearching = false
    }

    private fun startVoiceSearch() {
        if (isVoiceSearching) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isVoiceSearching = false
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        runOnUiThread {
            if (isVoiceSearching) return@runOnUiThread

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên bài hát hoặc nghệ sĩ")
            }

            val packageManager = packageManager
            val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (activities.isEmpty()) {
                isVoiceSearching = false
                errorMessage = "Điện thoại chưa có ứng dụng nhận diện giọng nói. Hãy bật Google hoặc Samsung Voice Input."
                return@runOnUiThread
            }

            try {
                errorMessage = null
                isVoiceSearching = true
                speechActivityLauncher.launch(intent)
            } catch (_: Exception) {
                isVoiceSearching = false
                errorMessage = "Không thể mở nhận diện giọng nói. Hãy kiểm tra Google/Samsung Voice Input trên điện thoại."
            }
        }
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)

                val c = controller
                if (c != null) {
                    // If the service already owns a queue, its Shuffle/Repeat state
                    // is authoritative because playback may have continued in the background.
                    // Apply saved preferences only when initializing a brand-new queue.
                    if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
                        c.shuffleModeEnabled = shuffleEnabled
                        c.repeatMode = repeatMode
                        c.setMediaItems(songs.map { mediaItemFor(it) })
                        c.prepare()

                        val restoreIndex = lastSongUri?.let { uri ->
                            songs.indexOfFirst { it.uri.toString() == uri }
                        } ?: -1
                        if (restoreIndex >= 0) {
                            c.seekToDefaultPosition(restoreIndex)
                            if (savedPosition > 0L) c.seekTo(savedPosition)
                        }
                    }

                    val currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
                    currentIndex = when {
                        currentUri != null -> songs.indexOfFirst { it.uri.toString() == currentUri }
                        c.currentMediaItemIndex >= 0 && c.currentMediaItemIndex < songs.size ->
                            c.currentMediaItemIndex
                        else -> -1
                    }
                    position = c.currentPosition.coerceAtLeast(0L)
                    isPlaying = c.isPlaying
                }
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
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += Song(id, cursor.getString(titleCol).orEmpty().ifBlank { "Không có tên" },
                    cursor.getString(artistCol).orEmpty().ifBlank { "Nghệ sĩ không rõ" },
                    cursor.getLong(durationCol), ContentUris.withAppendedId(collection, id), "Thiết bị", cursor.getLong(albumIdCol))
            }
        }
        val savedDriveUris = prefs.getStringSet("drive_uris", emptySet()) ?: emptySet()
        val savedOnlineUris = prefs.getStringSet("online_uris", emptySet()) ?: emptySet()
        val existing = result.map { it.uri.toString() }.toMutableSet()
        savedDriveUris.forEach { raw ->
            val uri = Uri.parse(raw)
            if (existing.add(raw)) result += songFromUri(uri)
        }
        savedOnlineUris.forEach { raw ->
            val uri = Uri.parse(raw)
            if (existing.add(raw)) result += onlineSongFromUri(uri)
        }
        songs.clear()
        songs.addAll(result)
        errorMessage = if (songs.isEmpty()) "Chưa tìm thấy file nhạc trong thiết bị." else null
        controller?.let { c ->
            // If MusicService already owns a matching queue, preserve its active item
            // and exact position. If the library changed with the same item count,
            // rebuild the queue so a library index can never point at the wrong URI.
            if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
                c.setMediaItems(songs.map { mediaItemFor(it) })
                c.prepare()

                val restoreIndex = lastSongUri?.let { uri ->
                    songs.indexOfFirst { it.uri.toString() == uri }
                } ?: -1
                if (restoreIndex >= 0) {
                    currentIndex = restoreIndex
                    c.seekToDefaultPosition(restoreIndex)
                    if (savedPosition > 0L) c.seekTo(savedPosition)
                }
            } else if (c.mediaItemCount > 0) {
                if (!isControllerQueueInSync(c)) {
                    syncControllerQueue()
                }

                val activeUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
                val activeIndex = activeUri?.let { uri ->
                    songs.indexOfFirst { it.uri.toString() == activeUri }
                } ?: -1
                if (activeIndex >= 0) {
                    currentIndex = activeIndex
                    lastSongUri = activeUri
                }
                position = c.currentPosition.coerceAtLeast(0L)
                isPlaying = c.isPlaying
            }
        }
    }

    private fun isControllerQueueInSync(c: MediaController): Boolean {
        if (c.mediaItemCount != songs.size) return false
        return songs.indices.all { index ->
            c.getMediaItemAt(index).localConfiguration?.uri == songs[index].uri
        }
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        val c = controller ?: run { errorMessage = "Trình phát đang khởi động, thử lại sau."; return }

        if (!isControllerQueueInSync(c)) {
            // Rebuilding the queue must not silently reset Shuffle/Repeat.
            syncControllerQueue()
        }

        currentIndex = index
        c.seekToDefaultPosition(index)
        c.play()
        shuffleEnabled = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        errorMessage = null
    }

    private fun mediaItemFor(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .apply {
                        if (song.albumId >= 0) {
                            setArtworkUri(
                                ContentUris.withAppendedId(
                                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                    song.albumId
                                )
                            )
                        }
                    }
                    .build()
            )
            .build()
    }

    private fun importDriveSongs(uris: List<Uri>) {
        val saved = (prefs.getStringSet("drive_uris", emptySet()) ?: emptySet()).toMutableSet()
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

    private fun openDriveFolderPicker() {
        driveFolderLauncher.launch(null)
    }

    private fun importDriveFolder(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }

        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null) {
            errorMessage = "Không mở được thư mục Google Drive."
            return
        }

        val audioUris = mutableListOf<Uri>()
        collectDriveAudioFiles(root, audioUris)

        if (audioUris.isEmpty()) {
            errorMessage = "Không tìm thấy file âm thanh trong thư mục đã chọn."
            return
        }

        importDriveSongs(audioUris)
    }

    private fun collectDriveAudioFiles(
        folder: DocumentFile,
        result: MutableList<Uri>
    ) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) {
                collectDriveAudioFiles(file, result)
            } else if (file.isFile && isAudioFile(file)) {
                result += file.uri
            }
        }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val type = file.type.orEmpty().lowercase()
        val name = file.name.orEmpty().lowercase()
        return type.startsWith("audio/") ||
            name.endsWith(".mp3") ||
            name.endsWith(".m4a") ||
            name.endsWith(".aac") ||
            name.endsWith(".flac") ||
            name.endsWith(".wav") ||
            name.endsWith(".ogg") ||
            name.endsWith(".opus") ||
            name.endsWith(".wma")
    }

    private fun playOnlineUrl() {
        val raw = onlineUrl.trim()
        if (raw.isBlank()) {
            errorMessage = "Nhập URL âm thanh trực tiếp (HTTPS)."
            return
        }
        val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            errorMessage = "URL không hợp lệ. Hãy dùng URL HTTP/HTTPS trỏ trực tiếp tới luồng âm thanh."
            return
        }

        val title = uri.lastPathSegment
            ?.substringBeforeLast(".")
            ?.ifBlank { null }
            ?: uri.host.orEmpty().ifBlank { "Nhạc Online" }

        val song = Song(
            id = -kotlin.math.abs(raw.hashCode().toLong()),
            title = title,
            artist = "Online",
            duration = 0L,
            uri = uri,
            source = "Online"
        )

        val saved = (prefs.getStringSet("online_uris", emptySet()) ?: emptySet()).toMutableSet()
        saved.add(raw)
        prefs.edit().putStringSet("online_uris", saved).apply()

        val existingIndex = songs.indexOfFirst { it.uri.toString() == raw }
        val index = if (existingIndex >= 0) existingIndex else {
            songs.add(song)
            songs.lastIndex
        }

        syncControllerQueue()
        play(index)
        errorMessage = "Đang kết nối luồng âm thanh online…"
    }

    private fun onlineSongFromUri(uri: Uri): Song {
        val title = uri.lastPathSegment
            ?.substringBeforeLast(".")
            ?.ifBlank { null }
            ?: uri.host.orEmpty().ifBlank { "Nhạc Online" }

        return Song(
            id = -kotlin.math.abs(uri.toString().hashCode().toLong()),
            title = title,
            artist = "Online",
            duration = 0L,
            uri = uri,
            source = "Online"
        )
    }

    private fun clearOnlineLibrary() {
        prefs.edit().remove("online_uris").apply()
        songs.removeAll { it.source == "Online" }
        if (currentIndex >= songs.size) currentIndex = -1
        syncControllerQueue()
        errorMessage = "Đã xóa các luồng online đã lưu."
    }

    private fun searchJamendo() {
        val q = jamendoQuery.trim()
        if (q.isBlank()) {
            errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm."
            return
        }

        jamendoLoading = true
        errorMessage = null

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                // Jamendo's documented free-text "search" parameter searches
                // track name, album, artist, tags and similar artists.
                val endpoint = "https://api.jamendo.com/v3.0/tracks/"
                val query = buildString {
                    append("client_id=709fa152")
                    append("&format=json")
                    append("&limit=50")
                    append("&audioformat=mp31")
                    append("&type=single%20albumtrack")
                    append("&search=")
                    append(java.net.URLEncoder.encode(q, "UTF-8"))
                }

                connection = (java.net.URL("$endpoint?$query").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "NGOC-SI-MUSIC/3.1")
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    throw java.io.IOException("Jamendo HTTP $code: $body")
                }

                val json = org.json.JSONObject(body)
                val headers = json.optJSONObject("headers")
                val apiError = headers?.optString("error_message").orEmpty()
                if (headers?.optString("status") == "success" && apiError.isNotBlank()) {
                    throw java.io.IOException(apiError)
                }

                val results = json.optJSONArray("results")
                val found = mutableListOf<JamendoTrack>()

                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val audio = item.optString("audio").trim()
                        if (audio.isBlank()) continue

                        found += JamendoTrack(
                            id = item.optLong("id"),
                            title = item.optString("name").ifBlank { "Không có tên" },
                            artist = item.optString("artist_name").ifBlank { "Nghệ sĩ không rõ" },
                            duration = item.optLong("duration").coerceAtLeast(0L) * 1000L,
                            audioUrl = audio,
                            imageUrl = item.optString("image")
                        )
                    }
                }

                runOnUiThread {
                    // Ignore stale responses when the user has already started a newer search.
                    if (jamendoQuery.trim() == q) {
                        jamendoTracks.clear()
                        jamendoTracks.addAll(found)
                        jamendoLoading = false
                        errorMessage = if (found.isEmpty()) {
                            "Không tìm thấy bài phù hợp trên Jamendo. Thử tên bài hát hoặc nghệ sĩ bằng tiếng Anh."
                        } else {
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    jamendoTracks.clear()
                    jamendoLoading = false
                    errorMessage = "Lỗi tìm nhạc online: ${e.message ?: "Không kết nối được Jamendo"}"
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun playAudiusTrack(track: AudiusTrack) {
        val uri = Uri.parse(track.streamUrl)
        val song = Song(
            id = -kotlin.math.abs(("audius:" + track.id).hashCode().toLong()),
            title = track.title,
            artist = track.artist,
            duration = track.duration,
            uri = uri,
            source = "Audius"
        )
        val existingIndex = songs.indexOfFirst { it.uri.toString() == track.streamUrl }
        val index = if (existingIndex >= 0) existingIndex else {
            songs.add(song)
            songs.lastIndex
        }
        syncControllerQueue()
        play(index)
    }

    private fun searchAudius(q: String) {
        audiusLoading = true
        lifecycleScope.launch(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                val endpoint = "https://api.audius.co/v1/tracks/search?query=$encoded&limit=50"
                connection = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "NGOC-SI-MUSIC/3.1")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw java.io.IOException("Audius HTTP $code")
                val json = org.json.JSONObject(body)
                val data = json.optJSONArray("data")
                val found = mutableListOf<AudiusTrack>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isBlank()) continue
                        val title = item.optString("title").ifBlank { "Không có tên" }
                        val user = item.optJSONObject("user")
                        val artist = user?.optString("name").orEmpty().ifBlank { "Nghệ sĩ Audius" }
                        val duration = item.optDouble("duration", 0.0).toLong().coerceAtLeast(0L) * 1000L
                        val stream = "https://api.audius.co/v1/tracks/$id/stream"
                        val image = item.optString("artwork").let { artwork ->
                            if (artwork.startsWith("http")) artwork else ""
                        }
                        found += AudiusTrack(id, title, artist, duration, stream, image)
                    }
                }
                runOnUiThread {
                    // Ignore stale responses when the user has already started a newer search.
                    if (jamendoQuery.trim() == q) {
                        audiusTracks.clear()
                        audiusTracks.addAll(found)
                        audiusLoading = false
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    // Do not let an older failed request disturb a newer search.
                    if (jamendoQuery.trim() == q) {
                        audiusTracks.clear()
                        audiusLoading = false
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun playJamendoTrack(track: JamendoTrack) {
        val raw = track.audioUrl
        val song = Song(
            id = -kotlin.math.abs(("jamendo:" + track.id).hashCode().toLong()),
            title = track.title,
            artist = track.artist,
            duration = track.duration,
            uri = Uri.parse(raw),
            source = "Jamendo"
        )
        val existingIndex = songs.indexOfFirst { it.uri.toString() == raw }
        val index = if (existingIndex >= 0) existingIndex else { songs.add(song); songs.lastIndex }
        syncControllerQueue()
        play(index)
    }

    private fun syncControllerQueue() {
        controller?.let { c ->
            if (songs.isNotEmpty()) {
                // Rebuild the queue only after capturing the exact active item,
                // position, playing state, shuffle mode and repeat mode.
                val selectedUri = c.currentMediaItem?.localConfiguration?.uri
                val savedPositionMs = c.currentPosition.coerceAtLeast(0L)
                val wasPlaying = c.isPlaying
                val keepShuffle = c.shuffleModeEnabled
                val keepRepeat = c.repeatMode

                c.setMediaItems(songs.map { mediaItemFor(it) })
                c.shuffleModeEnabled = keepShuffle
                c.repeatMode = keepRepeat
                c.prepare()

                val index = selectedUri?.let { uri ->
                    songs.indexOfFirst { it.uri == uri }
                } ?: -1

                if (index >= 0) {
                    c.seekToDefaultPosition(index)
                    if (savedPositionMs > 0L) c.seekTo(savedPositionMs)
                    if (wasPlaying) c.play()
                }

                shuffleEnabled = c.shuffleModeEnabled
                repeatMode = c.repeatMode
                position = c.currentPosition.coerceAtLeast(0L)
                isPlaying = c.isPlaying
            }
        }
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (songs.isNotEmpty() && !isControllerQueueInSync(c)) {
            syncControllerQueue()
        }
        if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
            c.setMediaItems(songs.map { mediaItemFor(it) })
            c.shuffleModeEnabled = shuffleEnabled
            c.repeatMode = repeatMode
            c.prepare()
            c.play()
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
        } else if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }

    private fun next() { controller?.seekToNextMediaItem(); controller?.play() }
    private fun previous() { controller?.seekToPreviousMediaItem(); controller?.play() }
    private fun removeFromQueue(index: Int) {
        if (index !in songs.indices) return
        val c = controller ?: return

        // The library list is stable order, while Media3 may expose a different
        // queue order when shuffle is enabled. Resolve the target by URI first.
        val targetUri = songs[index].uri.toString()
        val controllerIndex = (0 until c.mediaItemCount).firstOrNull { queueIndex ->
            c.getMediaItemAt(queueIndex).localConfiguration?.uri?.toString() == targetUri
        } ?: return

        val removedCurrent = c.currentMediaItem?.localConfiguration?.uri?.toString() == targetUri
        songs.removeAt(index)
        c.removeMediaItem(controllerIndex)

        val currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
        currentIndex = currentUri?.let { uri -> songs.indexOfFirst { it.uri.toString() == uri } } ?: -1

        if (songs.isEmpty()) {
            currentIndex = -1
            isPlaying = false
            position = 0L
        } else if (removedCurrent) {
            position = c.currentPosition.coerceAtLeast(0L)
        }
        savePlaybackState()
    }

    private fun moveQueueItem(from: Int, to: Int) {
        if (from !in songs.indices || to !in songs.indices || from == to) return
        val c = controller ?: return

        // Map the stable library positions to Media3 queue positions so shuffle
        // cannot cause the wrong item to be moved.
        val fromUri = songs[from].uri.toString()
        val toUri = songs[to].uri.toString()
        val controllerFrom = (0 until c.mediaItemCount).firstOrNull { queueIndex ->
            c.getMediaItemAt(queueIndex).localConfiguration?.uri?.toString() == fromUri
        } ?: return
        val controllerTo = (0 until c.mediaItemCount).firstOrNull { queueIndex ->
            c.getMediaItemAt(queueIndex).localConfiguration?.uri?.toString() == toUri
        } ?: return

        songs.add(to, songs.removeAt(from))
        c.moveMediaItem(controllerFrom, controllerTo)

        val currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
        currentIndex = currentUri?.let { uri -> songs.indexOfFirst { it.uri.toString() == uri } } ?: -1
        savePlaybackState()
    }

    private fun stop() {
        controller?.pause()
        controller?.seekTo(0L)
        position = 0L
        savedPosition = 0L
        // Persist the explicit Stop action so reopening the app does not
        // unexpectedly resume from the old saved position.
        savePlaybackState()
    }

    private fun seekTo(value: Long) {
        controller?.let {
            val safe = value.coerceIn(0L, max(0L, it.duration))
            it.seekTo(safe)
            position = safe
            savePlaybackState()
        }
    }

    private fun seekBy(deltaMs: Long) {
        controller?.let {
            val duration = it.duration.coerceAtLeast(0L)
            val target = (it.currentPosition + deltaMs).coerceIn(0L, duration)
            it.seekTo(target)
            position = target
            savePlaybackState()
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
        youtubeHistory.clear()
        youtubeHistory.addAll((prefs.getStringSet("youtube_history", emptySet()) ?: emptySet()).toList().take(8))
        (prefs.getStringSet("youtube_favorites", emptySet()) ?: emptySet()).forEach { youtubeFavoriteSet[it] = true }
        onlineFavoriteSet.addAll(prefs.getStringSet("online_favorites", emptySet()) ?: emptySet())
        onlineFavorites.addAll(onlineFavoriteSet)
        lastSongUri = prefs.getString("last_song_uri", null)
        savedPosition = prefs.getLong("last_position", 0L)
    }

    private fun savePlaybackState() {
        // Read directly from Media3 when available so onStop/onDestroy does not
        // persist a stale Compose position or an index from a shuffled queue.
        val c = controller
        val controllerUri = c?.currentMediaItem?.localConfiguration?.uri?.toString()
        val uri = controllerUri
            ?: songs.getOrNull(currentIndex)?.uri?.toString()
            ?: lastSongUri
        val currentPosition = c?.currentPosition?.coerceAtLeast(0L) ?: position.coerceAtLeast(0L)

        lastSongUri = uri
        savedPosition = currentPosition
        position = currentPosition

        prefs.edit()
            .putString("last_song_uri", uri)
            .putLong("last_position", currentPosition)
            .apply()
    }

    private fun clearDriveLibrary() {
        prefs.edit().remove("drive_uris").apply()
        songs.removeAll { it.source == "Google Drive" }
        if (currentIndex >= songs.size) currentIndex = -1
        syncControllerQueue()
        errorMessage = "Đã xóa các bài Google Drive khỏi thư viện ứng dụng."
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

    override fun onResume() {
        super.onResume()

        // Re-synchronize the Activity with Media3 after returning from the
        // background or a notification control. The service remains authoritative
        // so reopening the screen never resets the active queue or playback state.
        controller?.let { c ->
            val activeUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
            if (activeUri != null) {
                val libraryIndex = songs.indexOfFirst { it.uri.toString() == activeUri }
                if (libraryIndex >= 0) {
                    currentIndex = libraryIndex
                    lastSongUri = activeUri
                }
            }
            position = c.currentPosition.coerceAtLeast(0L)
            isPlaying = c.isPlaying
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
        }
    }

    override fun onStop() {
        // Persist the latest position even when the Activity leaves the foreground.
        // MusicService/MediaSession remains alive for background playback.
        if (::prefs.isInitialized) savePlaybackState()
        super.onStop()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        savePlaybackState()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onDestroy()
    }

    @Composable
    private fun NgocSiMusicApp() {
        val currentSong = songs.getOrNull(currentIndex)
        val filteredSongs = remember(searchQuery, songs.size, selectedLibrary, favorites.size, libraryView) {
            val q = searchQuery.trim()
            val byText = if (q.isBlank()) songs.toList() else songs.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.source.contains(q, true)
            }
            val bySource = when (selectedLibrary) {
                "Yêu thích" -> byText.filter { favorites[it.id] == true }
                "Thiết bị" -> byText.filter { it.source == "Thiết bị" }
                "Google Drive" -> byText.filter { it.source == "Google Drive" }
                "Online" -> byText.filter { it.source == "Online" }
                else -> byText
            }
            when (libraryView) {
                "Nghệ sĩ" -> bySource.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                "Album" -> bySource.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.substringBefore(" - ") })
                "Thư mục" -> bySource.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.source })
                else -> bySource.sortedBy { it.title.lowercase() }
            }
        }
        LaunchedEffect(isPlaying, currentIndex) {
            while (isPlaying) {
                controller?.let {
                    position = max(0L, it.currentPosition)
                    if (currentIndex >= 0) savePlaybackState()
                }
                delay(500)
            }
        }
        LaunchedEffect(sleepMinutes, isPlaying) {
            if (sleepMinutes > 0 && isPlaying) {
                val minutes = sleepMinutes
                delay(minutes * 60_000L)
                controller?.pause()
                sleepMinutes = 0
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF08090D), surface = Color(0xFF11131A), primary = Color(0xFFB18CFF), secondary = Color(0xFF7DD3FC))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) {
                Column(Modifier.fillMaxSize()) {
                    Header()
                    when (selectedSection) {
                        "Trang chủ" -> {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                SearchBarModern()
                                Spacer(Modifier.height(10.dp))
                                LibraryChips()
                                Spacer(Modifier.height(12.dp))
                                PlayerCard(currentSong)
                                Spacer(Modifier.height(12.dp))
                                QuickActions()
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { showQueue = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                    Text("☷  Hàng đợi phát • " + songs.size + " bài")
                                }
                            }
                        }
                        "Thư viện" -> {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                SearchBarModern()
                                Spacer(Modifier.height(10.dp))
                                LibraryViewTabs()
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("THƯ VIỆN • ${filteredSongs.size} bài", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = ::loadSongs) { Text("LÀM MỚI") }
                                }
                                errorMessage?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                            }
                        }
                        "Online" -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                            ) {
                                OnlineSourcesCard()
                                Spacer(Modifier.height(12.dp))
                                Text("Nguồn đã nhập: ${songs.count { it.source == "Google Drive" }} bài", color = Color(0xFF9B9BA8), fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        "Cài đặt" -> SettingsPanel()
                    }
                    Spacer(Modifier.height(8.dp))
                    if (selectedSection == "Trang chủ" || selectedSection == "Thư viện") {
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(filteredSongs.take(if (selectedSection == "Trang chủ") 12 else filteredSongs.size), key = { _, song -> song.id }) { _, song ->
                                val realIndex = songs.indexOfFirst { it.id == song.id }
                                SongRow(song, realIndex, realIndex == currentIndex)
                            }
                        }
                    } else if (selectedSection == "Cài đặt") {
                        Spacer(Modifier.weight(1f))
                    }
                    currentSong?.let { MiniPlayer(it) }
                    BottomNav()
                }
            }
        }
        currentSong?.let { if (showNowPlaying) NowPlayingDialog(it) }
        if (showQueue) QueueDialog()
        if (showYoutube) YouTubeDialog()
    }

    @Composable
    private fun Header() {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NGỌC SĨ", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("MUSIC", color = Color(0xFFB18CFF), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
            }
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF1D172B)), contentAlignment = Alignment.Center) {
                Text("♫", color = Color(0xFFCDBAFF), fontSize = 24.sp)
            }
        }
    }

    @Composable
    private fun SearchBarModern() {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Tìm bài hát, nghệ sĩ...", color = Color(0xFF777D8D)) },
            leadingIcon = { Text("⌕", color = Color(0xFFB18CFF), fontSize = 25.sp) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color(0xFF11131A),
                focusedContainerColor = Color(0xFF151722),
                unfocusedBorderColor = Color(0xFF252936),
                focusedBorderColor = Color(0xFF8F6FE8)
            )
        )
    }

    @Composable
    private fun LibraryChips() {
        val tabs = listOf("Tất cả", "Yêu thích", "Thiết bị", "Google Drive", "Online")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { tab ->
                FilterChip(selected = selectedLibrary == tab, onClick = { selectedLibrary = tab }, label = { Text(tab) }, shape = RoundedCornerShape(14.dp))
            }
        }
    }

    @Composable
    private fun LibraryViewTabs() {
        val tabs = listOf("Bài hát", "Nghệ sĩ", "Album", "Thư mục")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { tab ->
                FilterChip(selected = libraryView == tab, onClick = { libraryView = tab }, label = { Text(tab) }, shape = RoundedCornerShape(14.dp))
            }
        }
    }

    @Composable
    private fun QuickActions() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { selectedSection = "Thư viện" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text("🎵 Thư viện")
            }
            FilledTonalButton(onClick = { selectedSection = "Online" }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text("☁ Online")
            }
        }
    }

    private fun albumArtBitmap(song: Song): androidx.compose.ui.graphics.ImageBitmap? {
        if (song.albumId < 0) return null
        return try {
            val artUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, song.albumId)
            contentResolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (_: Exception) { null }
    }

    @Composable
    private fun AlbumArt(song: Song, modifier: Modifier = Modifier) {
        val bitmap = remember(song.uri.toString(), song.albumId) { albumArtBitmap(song) }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = "Ảnh bìa " + song.title, modifier = modifier.clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(modifier.clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF6D4CC5), Color(0xFF24283A)))), contentAlignment = Alignment.Center) {
                Text("♫", color = Color(0xFFC8B7FF), fontSize = 42.sp)
            }
        }
    }

    @Composable
    private fun MiniPlayer(song: Song) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1722))
                .clickable { showNowPlaying = true }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color(0xFF9999A5), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = ::togglePlayPause) { Text(if (isPlaying) "⏸" else "▶", fontSize = 20.sp) }
        }
    }

    @Composable
    private fun BottomNav() {
        NavigationBar(containerColor = Color(0xFF0F1016)) {
            listOf("Trang chủ" to "⌂", "Thư viện" to "♫", "Online" to "☁", "Cài đặt" to "⚙").forEach { (name, icon) ->
                NavigationBarItem(
                    selected = selectedSection == name,
                    onClick = { selectedSection = name },
                    icon = { Text(icon, fontSize = 20.sp) },
                    label = { Text(name, fontSize = 10.sp) }
                )
            }
        }
    }

    @Composable
    private fun SettingsPanel() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CÀI ĐẶT", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            SettingsRow("⏱", "Hẹn giờ tắt nhạc", if (sleepMinutes > 0) "${sleepMinutes} phút" else "Tắt") { showSleepTimer = true }
            SettingsRow("🔀", "Phát ngẫu nhiên", if (shuffleEnabled) "Đang bật" else "Đang tắt") { toggleShuffle() }
            SettingsRow("🔁", "Lặp lại", when (repeatMode) { Player.REPEAT_MODE_ONE -> "Một bài"; Player.REPEAT_MODE_ALL -> "Tất cả"; else -> "Tắt" }) { cycleRepeat() }
            SettingsRow("☁", "Google Drive", "${songs.count { it.source == "Google Drive" }} bài đã nhập") { selectedSection = "Online" }
            OutlinedButton(onClick = ::clearDriveLibrary, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("XÓA NHẠC GOOGLE DRIVE KHỎI ỨNG DỤNG")
            }
        }
        if (showSleepTimer) {
            AlertDialog(
                onDismissRequest = { showSleepTimer = false },
                title = { Text("Hẹn giờ tắt nhạc") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60, 90).forEach { min ->
                            OutlinedButton(onClick = {
                                sleepMinutes = min
                                showSleepTimer = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("${min} phút") }
                        }
                        TextButton(onClick = { sleepMinutes = 0; showSleepTimer = false }) { Text("Tắt hẹn giờ") }
                    }
                },
                confirmButton = {}
            )
        }
    }

    @Composable
    private fun SettingsRow(icon: String, title: String, value: String, action: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF15161D))
                .clickable(onClick = action).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(value, color = Color(0xFF8E8E99), fontSize = 12.sp)
            }
            Text("›", color = Color(0xFFB18CFF), fontSize = 24.sp)
        }
    }

    @Composable
    private fun NowPlayingDialog(song: Song) {
        Dialog(
            onDismissRequest = { showNowPlaying = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ĐANG PHÁT",
                                color = Color(0xFFB18CFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 3.sp
                            )
                            Text(
                                if (shuffleEnabled) "NGẪU NHIÊN • " + when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> "LẶP 1"
                                    Player.REPEAT_MODE_ALL -> "LẶP TẤT CẢ"
                                    else -> "KHÔNG LẶP"
                                } else "THƯ VIỆN • " + when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> "LẶP 1"
                                    Player.REPEAT_MODE_ALL -> "LẶP TẤT CẢ"
                                    else -> "KHÔNG LẶP"
                                },
                                color = Color(0xFF777783),
                                fontSize = 10.sp
                            )
                        }
                        IconButton(onClick = { toggleFavorite(song) }) {
                            Text(
                                if (favorites[song.id] == true) "♥" else "♡",
                                color = if (favorites[song.id] == true) Color(0xFFFF6B81) else Color(0xFF8A8A96),
                                fontSize = 26.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    AlbumArt(song, Modifier.size(250.dp))
                    Spacer(Modifier.height(18.dp))

                    Text(
                        song.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        color = Color(0xFF9999A5),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(14.dp))
                    Slider(
                        value = if (song.duration > 0) position.coerceIn(0, song.duration).toFloat() else 0f,
                        onValueChange = { seekTo(it.toLong()) },
                        valueRange = 0f..max(1L, song.duration).toFloat()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(position), color = Color(0xFF888894), fontSize = 12.sp)
                        Text(formatTime(song.duration), color = Color(0xFF888894), fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallControl(if (shuffleEnabled) "🔀" else "⇄", ::toggleShuffle, shuffleEnabled)
                        SmallControl("⏪10", { seekBy(-10_000L) })
                        SmallControl("⏮", ::previous)
                        Button(
                            onClick = ::togglePlayPause,
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape
                        ) {
                            Text(if (isPlaying) "⏸" else "▶", fontSize = 23.sp)
                        }
                        SmallControl("⏭", ::next)
                        SmallControl("10⏩", { seekBy(10_000L) })
                        SmallControl(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> "🔂"
                                Player.REPEAT_MODE_ALL -> "🔁"
                                else -> "↻"
                            },
                            ::cycleRepeat,
                            repeatMode != Player.REPEAT_MODE_OFF
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showQueue = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("☷ Hàng đợi") }
                        TextButton(
                            onClick = { showNowPlaying = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Đóng") }
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueDialog() {
        Dialog(onDismissRequest = { showQueue = false }) {
            Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF101117), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HÀNG ĐỢI PHÁT", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text(songs.size.toString() + " bài • " + if (shuffleEnabled) "Ngẫu nhiên" else "Theo thư viện", color = Color(0xFF888894), fontSize = 12.sp)
                        }
                        TextButton(onClick = { showQueue = false }) { Text("Đóng") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (index == currentIndex) Color(0xFF29213E) else Color(0xFF17181F))
                                    .clickable { play(index); showQueue = false }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (index == currentIndex) "▶" else String.format("%02d", index + 1), color = Color(0xFFC8B7FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Color(0xFF888894), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(formatTime(song.duration), color = Color(0xFF777783), fontSize = 11.sp)
                                TextButton(
                                    onClick = { if (index > 0) moveQueueItem(index, index - 1) },
                                    enabled = index > 0
                                ) { Text("↑") }
                                TextButton(
                                    onClick = { if (index < songs.lastIndex) moveQueueItem(index, index + 1) },
                                    enabled = index < songs.lastIndex
                                ) { Text("↓") }
                                TextButton(
                                    onClick = { removeFromQueue(index) }
                                ) { Text("×") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun searchYouTube() {
        val q = youtubeQuery.trim()
        if (q.isBlank()) {
            errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm trên YouTube."
            return
        }

        val apiKey = BuildConfig.YOUTUBE_API_KEY.trim()
        if (apiKey.isBlank()) {
            errorMessage = "YouTube chưa được cấu hình API key. Hãy thêm GitHub Secret YOUTUBE_API_KEY."
            return
        }

        youtubeLoading = true
        errorMessage = null
        lifecycleScope.launch(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val params = buildString {
                    append("part=snippet")
                    append("&type=video")
                    append("&videoEmbeddable=true")
                    append("&maxResults=50")
                    append("&order=relevance")
                    append("&regionCode=VN")
                    append("&relevanceLanguage=vi")
                    append("&safeSearch=moderate")
                    append("&q=")
                    append(java.net.URLEncoder.encode(q, "UTF-8"))
                    append("&key=")
                    append(java.net.URLEncoder.encode(apiKey, "UTF-8"))
                }
                val endpoint = "https://www.googleapis.com/youtube/v3/search?$params"
                connection = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "NGOC-SI-MUSIC/3.1")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching {
                        org.json.JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                    }.getOrDefault("")
                    throw java.io.IOException(
                        if (message.isBlank()) "YouTube HTTP $code" else message
                    )
                }

                val json = org.json.JSONObject(body)
                val items = json.optJSONArray("items")
                val found = mutableListOf<YouTubeTrack>()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optJSONObject("id")?.optString("videoId").orEmpty().trim()
                        val snippet = item.optJSONObject("snippet") ?: continue
                        if (id.isBlank()) continue
                        val title = snippet.optString("title").orEmpty()
                            .replace("&amp;", "&")
                            .ifBlank { "Video YouTube" }
                        val channel = snippet.optString("channelTitle").ifBlank { "YouTube" }
                        val thumbs = snippet.optJSONObject("thumbnails")
                        val thumb = thumbs?.optJSONObject("medium")?.optString("url").orEmpty()
                            .ifBlank { thumbs?.optJSONObject("high")?.optString("url").orEmpty() }
                            .ifBlank { thumbs?.optJSONObject("default")?.optString("url").orEmpty() }
                        found += YouTubeTrack(id, title, channel, thumb)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (youtubeQuery.trim() == q) {
                        youtubeTracks.clear()
                        youtubeTracks.addAll(found)
                        youtubeLoading = false
                        if (found.isEmpty()) {
                            errorMessage = "Không tìm thấy nội dung YouTube phù hợp."
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    youtubeLoading = false
                    errorMessage = "YouTube: " + (e.message ?: "không thể tìm kiếm")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun playYouTube(track: YouTubeTrack) {
        youtubeSelectedVideoId = track.videoId
        youtubeQuery = youtubeQuery.ifBlank { track.title }

        val history = (prefs.getStringSet("youtube_history", emptySet()) ?: emptySet()).toMutableList()
        history.remove(track.title)
        history.add(0, track.title)
        prefs.edit().putStringSet("youtube_history", history.take(8).toSet()).apply()
        youtubeHistory.clear()
        youtubeHistory.addAll(history.take(8))

        // YouTube được phát trong Activity riêng để tránh xung đột render
        // giữa WebView/video surface và Compose/ScrollView của màn hình chính.
        try {
            startActivity(
                Intent(this, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, track.videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_TITLE, track.title)
                    putExtra(YouTubePlayerActivity.EXTRA_CHANNEL, track.channelTitle)
                }
            )
        } catch (_: Exception) {
            showYoutube = true
        }
    }

    private fun toggleYouTubeFavorite(track: YouTubeTrack) {
        if (youtubeFavoriteSet.contains(track.videoId)) {
            youtubeFavoriteSet.remove(track.videoId)
        } else {
            youtubeFavoriteSet[track.videoId] = true
        }
        prefs.edit().putStringSet("youtube_favorites", youtubeFavoriteSet.keys).apply()
    }

    private var youtubeFullscreenWebView: WebView? = null

    @Composable
    private fun YouTubeDialog() {
        if (!showYoutube) return
        val videoId = youtubeSelectedVideoId
        val selectedTrack = youtubeTracks.firstOrNull { it.videoId == videoId }
        val title = selectedTrack?.title ?: "YouTube Video"
        val channel = selectedTrack?.channelTitle ?: "YouTube"
        val thumb = selectedTrack?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        var startPlayer by remember(videoId) { mutableStateOf(false) }
        var playerRetry by remember(videoId) { mutableIntStateOf(0) }
        var playerError by remember(videoId) { mutableStateOf(false) }
        val appContext = LocalContext.current
        val activity = appContext as? android.app.Activity
        var isYoutubeFullscreen by remember(videoId) { mutableStateOf(false) }

        Dialog(
            onDismissRequest = {
                showYoutube = false
                youtubeSelectedVideoId = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(color = Color(0xFF09090D), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF15161D))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("YOUTUBE", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = 16.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            showYoutube = false
                            youtubeSelectedVideoId = null
                        }) { Text("Đóng") }
                    }

                    if (videoId.isNullOrBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Chưa chọn video YouTube.", color = Color.White)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                                fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(channel, color = Color(0xFF9B9BA6), fontSize = 13.sp)

                            Spacer(Modifier.height(12.dp))

                            if (!startPlayer) {
                                // Hiển thị thumbnail trước khi phát để luôn có hình ảnh.
                                key(videoId, playerRetry) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(16.dp)),
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewClient = WebViewClient()
                                            setBackgroundColor(android.graphics.Color.BLACK)
                                            settings.javaScriptEnabled = false
                                            settings.domStorageEnabled = true
                                            settings.loadsImagesAutomatically = true
                                            settings.useWideViewPort = true
                                            settings.loadWithOverviewMode = true

                                            val safeThumb = thumb
                                                .replace("&", "&amp;")
                                                .replace("\"", "&quot;")
                                                .replace("<", "")
                                                .replace(">", "")

                                            val html = """
                                                <!doctype html>
                                                <html><head>
                                                <meta name="viewport" content="width=device-width,initial-scale=1">
                                                <style>
                                                  html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
                                                  img{width:100%;height:100%;object-fit:cover;display:block}
                                                </style></head>
                                                <body><img src="$safeThumb" /></body></html>
                                            """.trimIndent()
                                            loadDataWithBaseURL(
                                                "https://i.ytimg.com/",
                                                html, "text/html", "UTF-8", null
                                            )
                                        }
                                    }
                                )
                                 }

                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { startPlayer = true },
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("▶  PHÁT VIDEO", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                // Dùng URL embed trực tiếp thay vì iframe HTML trung gian.
                                // Cách này tương thích WebView tốt hơn và Play là thao tác của người dùng.
                                AndroidView(
                                    modifier = Modifier.fillMaxWidth()
                                        .aspectRatio(16f / 9f),
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

                                                override fun onPageFinished(view: WebView, url: String) {
                                                    super.onPageFinished(view, url)
                                                    view.requestLayout()
                                                    view.invalidate()
                                                }

                                                override fun onReceivedError(
                                                    view: WebView,
                                                    errorCode: Int,
                                                    description: String,
                                                    failingUrl: String
                                                ) {
                                                    playerError = true
                                                }

                                                override fun onRenderProcessGone(
                                                    view: WebView,
                                                    detail: android.webkit.RenderProcessGoneDetail
                                                ): Boolean {
                                                    playerError = true
                                                    return true
                                                }
                                            }
                                            webChromeClient = object : WebChromeClient() {
                                                override fun onShowFileChooser(view: WebView?, filePathCallback: android.webkit.ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean = false

                                                override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                                                    youtubeFullscreenWebView = this@apply
                                                    isYoutubeFullscreen = true
                                                    activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                    activity?.window?.decorView?.systemUiVisibility = (
                                                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                                                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                                    )
                                                }

                                                override fun onHideCustomView() {
                                                    youtubeFullscreenWebView = null
                                                    isYoutubeFullscreen = false
                                                    activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                                    activity?.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                                }
                                            }
                                            setBackgroundColor(android.graphics.Color.BLACK)

                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.loadsImagesAutomatically = true
                                            // Cho phép YouTube bắt đầu phát sau thao tác "PHÁT VIDEO".
                                            // WebView không còn chặn media gesture ở bước khởi tạo player.
                                            settings.mediaPlaybackRequiresUserGesture = false
                                            settings.useWideViewPort = true
                                            settings.loadWithOverviewMode = true
                                            settings.allowContentAccess = true
                                            settings.allowFileAccess = false
                                            settings.javaScriptCanOpenWindowsAutomatically = false
                                            settings.setSupportMultipleWindows(false)
                                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                            settings.setSupportZoom(false)

                                            CookieManager.getInstance().setAcceptCookie(true)
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            CookieManager.getInstance().flush()

                                            settings.userAgentString =
                                                "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                                                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

                                            val safeId = videoId
                                                .replace("&", "")
                                                .replace("\"", "")
                                                .replace("'", "")

                                            val embedUrl =
                                                "https://www.youtube.com/embed/$safeId" +
                                                    "?playsinline=1&autoplay=1&rel=0&controls=1&enablejsapi=1" +
                                                    "&origin=https%3A%2F%2Fcom.ngocsi.music"

                                            // Gửi Referer trực tiếp cùng request embed.
                                            // Giữ cách loadUrl vì bản ổn định trước đó đã phát được audio.
                                            val headers = mapOf(
                                                "Referer" to "https://com.ngocsi.music/"
                                            )

                                            // Không ép hardware layer và không clip WebView:
                                            // giảm nguy cơ video surface bị đen/trắng trong Compose.
                                            setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                                            youtubeFullscreenWebView = this
                                            isFocusable = true
                                            isFocusableInTouchMode = true
                                            requestFocus()

                                            loadUrl(embedUrl, headers)
                                        }
                                    }
                                )


                                if (playerError) {
                                    Spacer(Modifier.height(10.dp))
                                    Text("YouTube không tải được trình phát trong WebView.", color = Color(0xFFFFB4AB), fontSize = 13.sp)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { playerError = false; playerRetry++ }, modifier = Modifier.weight(1f)) { Text("Thử lại") }
                                        Button(onClick = {
                                            try {
                                                appContext.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse("https://www.youtube.com/watch?v=$videoId")
                                                    )
                                                )
                                            } catch (_: Exception) { }
                                        }, modifier = Modifier.weight(1f)) { Text("Mở YouTube") }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { startPlayer = false }) {
                                    Text("← Quay lại ảnh xem trước")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onlineFavoriteKey(item: OnlineSearchItem): String =
        item.source + ":" + item.title + ":" + item.artist

    private fun favoriteMetaFor(item: OnlineSearchItem): OnlineFavoriteMeta? {
        return if (item.source == "Audius") {
            audiusTracks.getOrNull(item.index)?.let {
                OnlineFavoriteMeta("Audius", it.title, it.artist, it.duration, it.streamUrl, it.imageUrl)
            }
        } else {
            jamendoTracks.getOrNull(item.index)?.let {
                OnlineFavoriteMeta("Jamendo", it.title, it.artist, it.duration, it.audioUrl, it.imageUrl)
            }
        }
    }

    private fun getOnlineFavoriteMeta(key: String): OnlineFavoriteMeta? {
        val raw = prefs.getString("online_favorite_meta", null) ?: return null
        return runCatching {
            val obj = org.json.JSONObject(raw).optJSONObject(key) ?: return@runCatching null
            OnlineFavoriteMeta(
                obj.optString("source"),
                obj.optString("title"),
                obj.optString("artist"),
                obj.optLong("duration", 0L),
                obj.optString("streamUrl"),
                obj.optString("imageUrl")
            )
        }.getOrNull()
    }

    private fun saveOnlineFavoriteMeta(key: String, meta: OnlineFavoriteMeta?) {
        val root = runCatching { org.json.JSONObject(prefs.getString("online_favorite_meta", null).orEmpty()) }
            .getOrElse { org.json.JSONObject() }
        if (meta == null) {
            root.remove(key)
        } else {
            root.put(key, org.json.JSONObject().apply {
                put("source", meta.source)
                put("title", meta.title)
                put("artist", meta.artist)
                put("duration", meta.duration)
                put("streamUrl", meta.streamUrl)
                put("imageUrl", meta.imageUrl)
            })
        }
        prefs.edit().putString("online_favorite_meta", root.toString()).apply()
    }

    private fun toggleOnlineFavorite(item: OnlineSearchItem) {
        val key = onlineFavoriteKey(item)
        if (onlineFavoriteSet.contains(key)) {
            onlineFavoriteSet.remove(key)
            saveOnlineFavoriteMeta(key, null)
        } else {
            onlineFavoriteSet.add(key)
            saveOnlineFavoriteMeta(key, favoriteMetaFor(item))
        }
        onlineFavorites.clear()
        onlineFavorites.addAll(onlineFavoriteSet)
        prefs.edit().putStringSet("online_favorites", onlineFavoriteSet).apply()
    }

    private fun playOnlineFavoriteKey(key: String) {
        val meta = getOnlineFavoriteMeta(key)
        if (meta != null && meta.streamUrl.isNotBlank()) {
            val song = Song(
                id = -kotlin.math.abs(("favorite:" + key).hashCode().toLong()),
                title = meta.title,
                artist = meta.artist,
                duration = meta.duration,
                uri = Uri.parse(meta.streamUrl),
                source = meta.source
            )
            val existingIndex = songs.indexOfFirst { it.uri.toString() == meta.streamUrl }
            val index = if (existingIndex >= 0) existingIndex else {
                songs.add(song)
                songs.lastIndex
            }
            syncControllerQueue()
            play(index)
            return
        }

        val parts = key.split(":", limit = 3)
        if (parts.size != 3) return
        val source = parts[0]
        val title = parts[1]
        val artist = parts[2]
        if (source == "Audius") {
            val track = audiusTracks.firstOrNull { it.title == title && it.artist == artist }
            if (track != null) playAudiusTrack(track)
            else errorMessage = "Hãy tìm lại bài Audius này để phát."
        } else if (source == "Jamendo") {
            val track = jamendoTracks.firstOrNull { it.title == title && it.artist == artist }
            if (track != null) playJamendoTrack(track)
            else errorMessage = "Hãy tìm lại bài Jamendo này để phát."
        }
    }

    private fun removeOnlineFavoriteKey(key: String) {
        onlineFavoriteSet.remove(key)
        saveOnlineFavoriteMeta(key, null)
        onlineFavorites.clear()
        onlineFavorites.addAll(onlineFavoriteSet)
        prefs.edit().putStringSet("online_favorites", onlineFavoriteSet).apply()
    }

    private fun clearOnlineFavorites() {
        onlineFavoriteSet.clear()
        onlineFavorites.clear()
        prefs.edit().remove("online_favorites").remove("online_favorite_meta").apply()
    }

    private fun isOnlineTrackPlaying(streamUrl: String): Boolean {
        val current = controller?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        return isPlaying && current == streamUrl
    }

    private fun refreshOnlineSearch() {
        val q = jamendoQuery.trim()
        if (q.isBlank()) {
            errorMessage = "Nhập tên bài hát hoặc nghệ sĩ trước khi làm mới."
            return
        }
        onlineSearchActive = true
        errorMessage = null
        jamendoTracks.clear()
        audiusTracks.clear()
        onlineVisibleCount = 30
        searchJamendo()
        searchAudius(q)
    }

    @Composable
    private fun OnlineArtwork(url: String, modifier: Modifier = Modifier) {
        var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(url) {
            bitmap = if (url.isBlank()) null else withContext(Dispatchers.IO) {
                runCatching {
                    java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
        Box(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF25252F)),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("♪", color = Color(0xFFB18CFF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun OnlineSourcesCard() {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF14141B)).padding(14.dp)) {
            Text("NGỌC SĨ ONLINE MUSIC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (onlineFavorites.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("YÊU THÍCH ONLINE • " + onlineFavorites.size, color = Color(0xFF8F8F9A), fontSize = 12.sp)
            }
            Text("Trung tâm nhạc online • Audius + Jamendo + YouTube", color = Color(0xFF8F8F9A), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Tất cả", "Audius", "Jamendo", "Yêu thích", "YouTube").forEach { tab ->
                    FilterChip(selected = onlineHubTab == tab, onClick = { onlineHubTab = tab }, label = { Text(tab) })
                }
            }
            Spacer(Modifier.height(10.dp))
            if (onlineHubTab != "YouTube" && onlineHubTab != "Yêu thích") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = jamendoQuery,
                    onValueChange = { jamendoQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Tên bài hát / nghệ sĩ") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            val q = jamendoQuery.trim()
                            if (q.isBlank()) {
                                errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm."
                            } else {
                                errorMessage = null
                                onlineSearchActive = true
                                jamendoTracks.clear()
                                audiusTracks.clear()
                                onlineVisibleCount = 30
                                searchJamendo()
                                searchAudius(q)
                            }
                        },
                        onDone = {
                            val q = jamendoQuery.trim()
                            if (q.isBlank()) {
                                errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm."
                            } else {
                                errorMessage = null
                                onlineSearchActive = true
                                jamendoTracks.clear()
                                audiusTracks.clear()
                                searchJamendo()
                                searchAudius(q)
                            }
                        }
                    )
                )
                Button(onClick = {
                    val q = jamendoQuery.trim()
                    if (q.isBlank()) {
                        errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm."
                    } else {
                        errorMessage = null
                        onlineSearchActive = true
                        jamendoTracks.clear()
                        audiusTracks.clear()
                        searchJamendo()
                        searchAudius(q)
                    }
                }, shape = RoundedCornerShape(14.dp)) { Text("TÌM") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = ::startVoiceSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                enabled = !isVoiceSearching
            ) {
                Text(if (isVoiceSearching) "🎙️ ĐANG NGHE…" else "🎙️ TÌM KIẾM BẰNG GIỌNG NÓI")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    jamendoQuery = ""
                    onlineSearchActive = false
                    jamendoTracks.clear()
                    audiusTracks.clear()
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("XÓA TÌM KIẾM") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = ::refreshOnlineSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                enabled = !jamendoLoading && !audiusLoading
            ) { Text("↻ LÀM MỚI KẾT QUẢ") }
            Spacer(Modifier.height(10.dp))
            if (jamendoLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            if (audiusLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            val onlineResults = buildList {
                val seen = mutableSetOf<String>()
                if (onlineHubTab == "Tất cả" || onlineHubTab == "Audius") {
                    audiusTracks.forEachIndexed { index, track ->
                        val key = track.title.trim().lowercase() + "|" + track.artist.trim().lowercase()
                        if (seen.add(key)) add(OnlineSearchItem("Audius", index, track.title, track.artist, track.duration))
                    }
                }
                if (onlineHubTab == "Tất cả" || onlineHubTab == "Jamendo") {
                    jamendoTracks.forEachIndexed { index, track ->
                        val key = track.title.trim().lowercase() + "|" + track.artist.trim().lowercase()
                        if (seen.add(key)) add(OnlineSearchItem("Jamendo", index, track.title, track.artist, track.duration))
                    }
                }
            }

            if (onlineSearchActive && (jamendoLoading || audiusLoading)) {
                Text(
                    "ĐANG TÌM KIẾM ĐA NGUỒN…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
            }

            if (onlineSearchActive && !jamendoLoading && !audiusLoading) {
                if (onlineResults.isEmpty()) {
                    Text("Không tìm thấy kết quả trên Jamendo hoặc Audius.", color = Color(0xFF8F8F9A), fontSize = 13.sp)
                } else {
                    Text(
                        "KẾT QUẢ ONLINE • ${onlineResults.size} BÀI",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Audius ${audiusTracks.size} • Jamendo ${jamendoTracks.size} • đã lọc trùng",
                        color = Color(0xFF8F8F9A),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Tên A-Z", "Nghệ sĩ", "Thời lượng").forEach { sort ->
                            FilterChip(
                                selected = onlineSort == sort,
                                onClick = { onlineSort = sort },
                                label = { Text(sort) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val sortedOnlineResults = when (onlineSort) {
                        "Nghệ sĩ" -> onlineResults.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                        "Thời lượng" -> onlineResults.sortedBy { it.duration }
                        else -> onlineResults.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    }
                    val visibleOnlineResults = sortedOnlineResults.take(onlineVisibleCount)
                    visibleOnlineResults.forEach { item ->
                        val artworkUrl = if (item.source == "Audius") audiusTracks.getOrNull(item.index)?.imageUrl.orEmpty() else jamendoTracks.getOrNull(item.index)?.imageUrl.orEmpty()
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1B1B23))
                                .clickable {
                                    if (item.source == "Audius") playAudiusTrack(audiusTracks[item.index])
                                    else playJamendoTrack(jamendoTracks[item.index])
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OnlineArtwork(artworkUrl, Modifier.size(54.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.artist} • ${item.source} • ${formatTime(item.duration)}", color = Color(0xFF8F8F9A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(
                                    onClick = { if (item.source == "Audius") playAudiusTrack(audiusTracks[item.index]) else playJamendoTrack(jamendoTracks[item.index]) },
                                    shape = CircleShape
                                ) {
                                    val streamUrl = if (item.source == "Audius") {
                                        audiusTracks.getOrNull(item.index)?.streamUrl.orEmpty()
                                    } else {
                                        jamendoTracks.getOrNull(item.index)?.audioUrl.orEmpty()
                                    }
                                    Text(if (isOnlineTrackPlaying(streamUrl)) "⏸" else "▶")
                                }
                                FilledTonalButton(onClick = { toggleOnlineFavorite(item) }, shape = CircleShape) { Text(if (onlineFavoriteSet.contains(item.source + ":" + item.title + ":" + item.artist)) "♥" else "♡") }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (sortedOnlineResults.size > onlineVisibleCount) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onlineVisibleCount += 30 },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("XEM THÊM • CÒN " + (sortedOnlineResults.size - onlineVisibleCount) + " BÀI") }
                    }
                }
            }
            }
            if (onlineHubTab == "Yêu thích") {
                Text("DANH SÁCH YÊU THÍCH ONLINE • ${onlineFavorites.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                if (onlineFavorites.isEmpty()) Text("Chưa có bài online yêu thích.", color = Color(0xFF8F8F9A), fontSize = 13.sp)
                else {
                    onlineFavorites.forEach { key ->
                        val parts = key.split(":", limit = 3)
                        if (parts.size == 3) {
                            val meta = getOnlineFavoriteMeta(key)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF1B1B23))
                                    .clickable { playOnlineFavoriteKey(key) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OnlineArtwork(meta?.imageUrl.orEmpty(), Modifier.size(54.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(meta?.title ?: parts[1], color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val durationText = if ((meta?.duration ?: 0L) > 0L) " • " + formatTime(meta!!.duration) else ""
                                    Text((meta?.artist ?: parts[2]) + " • " + (meta?.source ?: parts[0]) + durationText,
                                        color = Color(0xFF8F8F9A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilledTonalButton(onClick = { playOnlineFavoriteKey(key) }, shape = CircleShape) { Text("▶") }
                                    FilledTonalButton(onClick = { removeOnlineFavoriteKey(key) }, shape = CircleShape) { Text("♥") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (onlineFavorites.isNotEmpty()) {
                        OutlinedButton(
                            onClick = ::clearOnlineFavorites,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("XÓA TẤT CẢ YÊU THÍCH") }
                    }
                }
            }
            if (onlineHubTab == "Tất cả" || onlineHubTab == "YouTube") {
            Spacer(Modifier.height(10.dp))
            Text("YOUTUBE MUSIC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Kho tìm kiếm YouTube chính thức • phát bằng trình phát YouTube nhúng. Không tải hoặc tách luồng âm thanh.",
                color = Color(0xFF8F8F9A),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = youtubeQuery,
                onValueChange = { youtubeQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Tìm bài hát / nghệ sĩ trên YouTube") },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { searchYouTube() },
                    onDone = { searchYouTube() }
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::searchYouTube,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !youtubeLoading
                ) { Text(if (youtubeLoading) "ĐANG TÌM..." else "TÌM YOUTUBE") }
                OutlinedButton(
                    onClick = {
                        youtubeQuery = ""
                        youtubeTracks.clear()
                        errorMessage = null
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("XÓA") }
            }

            if (youtubeHistory.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("TÌM GẦN ĐÂY", color = Color(0xFFB18CFF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    youtubeHistory.take(8).forEach { item ->
                        AssistChip(
                            onClick = {
                                youtubeQuery = item
                                searchYouTube()
                            },
                            label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            if (youtubeTracks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "KẾT QUẢ YOUTUBE • " + youtubeTracks.size + " VIDEO",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                youtubeTracks.forEach { track ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B1B23))
                            .clickable { playYouTube(track) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OnlineArtwork(track.thumbnailUrl, Modifier.size(72.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                track.channelTitle,
                                color = Color(0xFF8F8F9A),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            FilledTonalButton(
                                onClick = { playYouTube(track) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp)
                            ) { Text("▶") }
                            FilledTonalButton(
                                onClick = { toggleYouTubeFavorite(track) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text(if (youtubeFavoriteSet.contains(track.videoId)) "♥" else "♡")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            } else if (!youtubeLoading && youtubeQuery.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nhấn TÌM YOUTUBE để tải kho kết quả.",
                    color = Color(0xFF8F8F9A),
                    fontSize = 12.sp
                )
            }
            }

            Spacer(Modifier.height(10.dp))
            Text("NHẠC ONLINE KHÁC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::openDrivePicker, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("File Drive") }
                OutlinedButton(onClick = ::openDriveFolderPicker, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Thư mục") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = onlineUrl, onValueChange = { onlineUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Dán URL luồng âm thanh HTTP/HTTPS") }, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(6.dp))
            Button(onClick = ::playOnlineUrl, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("PHÁT LUỒNG ÂM THANH") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { onlineUrl = "http://stream-tx3.radioparadise.com/mp3-192"; playOnlineUrl() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("THỬ RADIO ONLINE") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = ::clearOnlineLibrary, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("XÓA URL ONLINE ĐÃ LƯU") }
        }
    }

    @Composable
    private fun PlayerCard(song: Song?) {
        val duration = song?.duration ?: 0L
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF211A35), Color(0xFF12151D)))).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(82.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF6D4CC5), Color(0xFF24283A)))), contentAlignment = Alignment.Center) {
                    Text("♫", color = Color(0xFFC8B7FF), fontSize = 42.sp)
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

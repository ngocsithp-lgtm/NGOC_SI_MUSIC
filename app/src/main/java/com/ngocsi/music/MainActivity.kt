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
    private val youtubeHistory = mutableStateListOf<String>()
    private var onlineUrl by mutableStateOf("")
    private var selectedLibrary by mutableStateOf("Tất cả")
    private var libraryView by mutableStateOf("Bài hát")
    private var showQueue by mutableStateOf(false)
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
            val index = controller?.currentMediaItemIndex ?: -1
            if (index >= 0) {
                currentIndex = index
                songs.getOrNull(index)?.let {
                    lastSongUri = it.uri.toString()
                    savedPosition = 0L
                    savePlaybackState()
                }
            }
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
    private var voiceFallbackAttempted = false
    private var voiceStartInProgress = false

    private fun createSpeechRecognizerSafely(): SpeechRecognizer? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                usingOnDeviceRecognizer = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                usingOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: UnsupportedOperationException) {
            try {
                usingOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(this)
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun initSpeechRecognizer(): Boolean {
        speechRecognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        speechRecognizer = null

        val recognizer = createSpeechRecognizerSafely() ?: return false
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isVoiceSearching = true
            }
            override fun onBeginningOfSpeech() {
                isVoiceSearching = true
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isVoiceSearching = false
            }
            override fun onError(error: Int) {
                isVoiceSearching = false

                val canFallback = usingOnDeviceRecognizer &&
                    !voiceFallbackAttempted &&
                    error in setOf(
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER
                    )

                if (canFallback) {
                    voiceFallbackAttempted = true
                    val oldRecognizer = speechRecognizer
                    speechRecognizer = null
                    try { oldRecognizer?.cancel() } catch (_: Exception) {}
                    try { oldRecognizer?.destroy() } catch (_: Exception) {}
                    errorMessage = "Đang chuyển sang nhận diện giọng nói của hệ thống…"
                    window.decorView.postDelayed({ startVoiceRecognitionInternal(forceSystemRecognizer = true) }, 250L)
                    return
                }

                errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Không truy cập được microphone. Kiểm tra quyền microphone."
                    SpeechRecognizer.ERROR_CLIENT ->
                        "Dịch vụ nhận diện giọng nói gặp lỗi. Hãy thử lại."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Chưa được cấp quyền microphone."
                    SpeechRecognizer.ERROR_NETWORK ->
                        "Không kết nối được dịch vụ nhận diện giọng nói."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Hết thời gian kết nối nhận diện giọng nói."
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Không nghe rõ giọng nói. Hãy nói lại tên bài hát."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Bộ nhận diện đang bận. Hãy thử lại."
                    SpeechRecognizer.ERROR_SERVER ->
                        "Dịch vụ nhận diện giọng nói đang lỗi."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Không nghe thấy giọng nói."
                    else ->
                        "Lỗi nhận diện giọng nói: $error"
                }
                try { speechRecognizer?.destroy() } catch (_: Exception) {}
                speechRecognizer = null
            }
            override fun onResults(results: Bundle?) {
                isVoiceSearching = false
                val query = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (query.isBlank()) {
                    errorMessage = "Không nhận được nội dung tìm kiếm. Hãy nói lại tên bài hát."
                    return
                }
                jamendoQuery = query
                onlineSearchActive = true
                jamendoTracks.clear()
                audiusTracks.clear()
                errorMessage = null
                searchJamendo()
                searchAudius(query)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer = recognizer
        return true
    }

    private fun startVoiceSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        startVoiceRecognitionInternal(forceSystemRecognizer = false)
    }

    private fun startVoiceRecognitionInternal(forceSystemRecognizer: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isVoiceSearching = false
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        runOnUiThread {
            if (voiceStartInProgress) return@runOnUiThread
            voiceStartInProgress = true

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                voiceStartInProgress = false
                isVoiceSearching = false
                errorMessage = "Điện thoại chưa có dịch vụ nhận diện giọng nói. Hãy cài hoặc bật Google/Samsung Speech Recognition."
                return@runOnUiThread
            }

            if (forceSystemRecognizer) usingOnDeviceRecognizer = false
            if (!forceSystemRecognizer) voiceFallbackAttempted = false

            if (!initSpeechRecognizer()) {
                voiceStartInProgress = false
                isVoiceSearching = false
                errorMessage = "Không thể khởi tạo bộ nhận diện giọng nói trên điện thoại."
                return@runOnUiThread
            }

            val recognizer = speechRecognizer
            if (recognizer == null) {
                voiceStartInProgress = false
                isVoiceSearching = false
                errorMessage = "Không thể khởi tạo bộ nhận diện giọng nói."
                return@runOnUiThread
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên bài hát hoặc nghệ sĩ")
            }

            errorMessage = null
            isVoiceSearching = true
            try {
                recognizer.startListening(intent)
                voiceStartInProgress = false
            } catch (_: SecurityException) {
                voiceStartInProgress = false
                isVoiceSearching = false
                try { recognizer.cancel() } catch (_: Exception) {}
                try { recognizer.destroy() } catch (_: Exception) {}
                speechRecognizer = null
                errorMessage = "Quyền microphone chưa được cấp. Hãy cho phép microphone rồi thử lại."
            } catch (_: Exception) {
                voiceStartInProgress = false
                isVoiceSearching = false
                try { recognizer.cancel() } catch (_: Exception) {}
                try { recognizer.destroy() } catch (_: Exception) {}
                speechRecognizer = null
                errorMessage = "Không thể bắt đầu nghe. Hãy kiểm tra dịch vụ nhận diện giọng nói trên điện thoại."
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
            if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
                c.setMediaItems(songs.map { mediaItemFor(it) })
                c.prepare()
            }
            val restoreIndex = lastSongUri?.let { uri ->
                songs.indexOfFirst { it.uri.toString() == uri }
            } ?: -1
            if (restoreIndex >= 0) {
                currentIndex = restoreIndex
                c.seekToDefaultPosition(restoreIndex)
                if (savedPosition > 0L) c.seekTo(savedPosition)
            }
        }
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        val c = controller ?: run { errorMessage = "Trình phát đang khởi động, thử lại sau."; return }
        if (c.mediaItemCount != songs.size) {
            c.setMediaItems(songs.map { mediaItemFor(it) })
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
                    append("&limit=30")
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
                val endpoint = "https://api.audius.co/v1/tracks/search?query=$encoded&limit=25"
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
            c.setMediaItems(songs.map { mediaItemFor(it) })
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
        onlineFavoriteSet.addAll(prefs.getStringSet("online_favorites", emptySet()) ?: emptySet())
        onlineFavorites.addAll(onlineFavoriteSet)
        lastSongUri = prefs.getString("last_song_uri", null)
        savedPosition = prefs.getLong("last_position", 0L)
    }

    private fun savePlaybackState() {
        val uri = songs.getOrNull(currentIndex)?.uri?.toString() ?: lastSongUri
        prefs.edit()
            .putString("last_song_uri", uri)
            .putLong("last_position", position.coerceAtLeast(0L))
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
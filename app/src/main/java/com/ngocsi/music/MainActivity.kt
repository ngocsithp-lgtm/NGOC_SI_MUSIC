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
import android.location.LocationManager
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.SharedPreferences
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
import androidx.compose.foundation.lazy.items
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.max

data class Song(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri, val source: String = "Thiết bị", val albumId: Long = -1L, val artworkUri: Uri? = null, val folder: String = "")

data class TvSource(
    val name: String,
    val description: String,
    val url: String
)

object TvCatalog {
    // Official/public entry points. Playback remains inside the provider's
    // own web experience; no stream extraction or DRM bypass is performed.
    val builtIn = listOf(
        TvSource("VTV1 • Live", "VTV Go — kênh VTV1 trực tiếp", "https://vtvgo.vn/channel/1"),
        TvSource("VTV2 • Live", "VTV Go — kênh VTV2 trực tiếp", "https://package.vtvgo.vn/channel/2"),
        TvSource("VTV3 • Live", "VTV Go — kênh VTV3 trực tiếp", "https://vtvgo.vn/channel/3"),
        TvSource("VTV5 Tây Nam Bộ • Live", "VTV Go — kênh VTV5 Tây Nam Bộ", "https://vtvgo.vn/channel/7"),
        TvSource("VTV9 • Live", "VTV Go — kênh VTV9 trực tiếp", "https://vtvgo.vn/channel/9"),
        TvSource("VTVgo", "Nền tảng truyền hình số quốc gia của VTV", "https://www.vtvgo.vn/"),
        TvSource("HTVm", "Nền tảng nội dung truyền hình của HTV", "https://htvm.htv.com.vn/"),
        TvSource("FPT Play", "Truyền hình trực tuyến và nội dung chính thức của FPT Play", "https://fptplay.vn/"),
        TvSource("VieON • TV Online", "Truyền hình trực tuyến của VieON", "https://vieon.vn/truyen-hinh-truc-tuyen/"),
        TvSource("VieON • VTV1", "VieON — VTV1 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv1-hd/"),
        TvSource("VieON • VTV2", "VieON — VTV2 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv2-hd/"),
        TvSource("VieON • VTV3", "VieON — VTV3 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv3-hd/"),
        TvSource("VieON • VTV4", "VieON — VTV4 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv4-hd/"),
        TvSource("VieON • VTV6", "VieON — VTV6 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv6-hd/"),
        TvSource("VieON • Quốc Phòng", "VieON — Quốc Phòng HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/quoc-phong-hd/"),
        TvSource("VieON • Vĩnh Long 1", "VieON — Vĩnh Long 1 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/thvl1-hd/"),
        TvSource("VieON • Vĩnh Long 2", "VieON — Vĩnh Long 2 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/thvl2-hd/"),
        TvSource("VieON • Vĩnh Long 3", "VieON — Vĩnh Long 3 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/thvl3-hd/"),
        TvSource("VieON • Vĩnh Long 4", "VieON — Vĩnh Long 4 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/thvl4-hd/"),
        TvSource("VieON • VTV9", "VieON — VTV9 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv9-hd/"),
        TvSource("VieON • VTV5", "VieON — VTV5 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv5-hd/"),
        TvSource("VieON • VTV7", "VieON — VTV7 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv7-hd/"),
        TvSource("VieON • VTV8", "VieON — VTV8 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv8-hd/"),
        TvSource("VieON • VTV Cần Thơ", "VieON — VTV Cần Thơ trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/vtv-can-tho/"),
        TvSource("VieON • HTV7", "VieON — HTV7 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/htv7-hd/"),
        TvSource("VieON • HTV9", "VieON — HTV9 HD trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/htv9-hd/"),
        TvSource("VieON • HTV3", "VieON — HTV3 trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/htv3/"),
        TvSource("VieON • HTV Thể Thao", "VieON — HTV Thể Thao trực tuyến", "https://vieon.vn/truyen-hinh-truc-tuyen/htv-the-thao/"),
        TvSource("SCTV3 • Live", "VTVgo — SCTV3 trực tiếp", "https://vtvgo.vn/channel/sctv3"),
        TvSource("SCTV22 • Live", "VTVgo — SCTV22 trực tiếp", "https://vtvgo.vn/channel/sctv22"),
        TvSource("ON VFamily • Live", "VTVgo — ON VFamily trực tiếp", "https://vtvgo.vn/channel/vtvcab24"),
        TvSource("ON Info TV • Live", "VTVgo — ON Info TV trực tiếp", "https://vtvgo.vn/channel/vtvcab9"),
        TvSource("VTV3 Miền Bắc • Live", "VTVgo — VTV3 Miền Bắc trực tiếp", "https://vtvgo.vn/channel/3_nb"),
        TvSource("VTV6 • Live", "VTVgo — VTV6 trực tiếp", "https://vtvgo.vn/channel/xem-truc-tuyen-kenh-vtv6"),
        TvSource("Truyền hình Hải Phòng 3 • Live", "VTVgo — Truyền hình Hải Phòng 3", "https://vtvgo.vn/channel/143"),
        TvSource("Truyền hình Đồng Nai 2 • Live", "VTVgo — Truyền hình Đồng Nai 2", "https://vtvgo.vn/channel/145")
    )
}

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

data class YouTubeFavoriteMeta(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String
)

data class YouTubeWatchLaterMeta(
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

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val songs = mutableStateListOf<Song>()
    private val queueSongs = mutableStateListOf<Song>()
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    // Active playback duration reported by Media3; used by the main progress ticker.
    private var duration by mutableLongStateOf(0L)
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
    private val youtubeFavoriteTracks = mutableStateListOf<YouTubeFavoriteMeta>()
    private val youtubeWatchLater = mutableStateListOf<YouTubeWatchLaterMeta>()
    private var youtubeLoading by mutableStateOf(false)
    private var youtubeNextPageToken by mutableStateOf<String?>(null)
    private var youtubeSelectedVideoId by mutableStateOf<String?>(null)
    private var youtubeLastPlayed by mutableStateOf<YouTubeTrack?>(null)
    private var youtubeLastQueueJson by mutableStateOf<String?>(null)
    private var youtubeLastQueueIndex by mutableIntStateOf(0)
    private var onlineUrl by mutableStateOf("")
    private var driveSharedLink by mutableStateOf("")
    private var driveSharedLoading by mutableStateOf(false)
    private var driveSharedStatus by mutableStateOf("Chưa liên kết nguồn Drive chia sẻ")
    private var driveRecentLinks by mutableStateOf(listOf<String>())
    private var selectedLibrary by mutableStateOf("Tất cả")
    private var libraryView by mutableStateOf("Bài hát")
    private var showQueue by mutableStateOf(false) // #145 queue upgrade
    private var showVietnamRadioHub by mutableStateOf(false)
    private var radioWebUrl by mutableStateOf<String?>(null)
    private var radioWebTitle by mutableStateOf("RADIO VIỆT NAM")
    private var radioFilter by mutableStateOf("")
    private var activeRadioTitle: String? = null
    private var activeRadioStreams: List<String> = emptyList()
    private var activeRadioStreamIndex = 0
    private var selectedSection by mutableStateOf("Trang chủ")
    private var showTvSourceDialog by mutableStateOf(false)
    private var tvSourceUrl by mutableStateOf("")
    private var tvSourceName by mutableStateOf("")
    private val customTvSources = mutableStateListOf<TvSource>()
    private var mapSearchQuery by mutableStateOf("")
    private var mapSearching by mutableStateOf(false)
    private var showNowPlaying by mutableStateOf(false)
    private var youtubeQuery by mutableStateOf("")
    private var isVoiceSearching by mutableStateOf(false)
    private var speechRecognizer: SpeechRecognizer? = null
    private var showSleepTimer by mutableStateOf(false)
    private var sleepMinutes by mutableIntStateOf(0)
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndAt by mutableLongStateOf(0L)
    private var jamendoSearchJob: Job? = null
    private var audiusSearchJob: Job? = null
    private var youtubeSearchJob: Job? = null
    private var driveImportJob: Job? = null
    private var lastSongUri by mutableStateOf<String?>(null)
    private var savedPosition by mutableLongStateOf(0L)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private var selectedPlaybackSpeed by mutableFloatStateOf(1.0f)
    private var showPlaybackSpeed by mutableStateOf(false)
    private val favorites = mutableStateMapOf<Long, Boolean>()
    private lateinit var prefs: SharedPreferences
    private lateinit var playlistStore: PlaylistStore
    private val playlists = mutableStateListOf<MusicPlaylist>()
    private var showPlaylists by mutableStateOf(false)
    private var playlistDetailId by mutableStateOf<String?>(null)
    private var playlistTargetSongUri by mutableStateOf<String?>(null)
    private var showCreatePlaylist by mutableStateOf(false)
    private var newPlaylistName by mutableStateOf("")

    // Radio recovery watchdog. A live stream can stay in BUFFERING without
    // emitting a fatal player error, so switch to the next known stream after
    // a bounded timeout instead of leaving the player apparently stuck.
    private val radioRecoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val radioRecoveryRunnable = Runnable {
        recoverBufferedRadio()
    }

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

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                showCurrentLocationOnMap()
            } else {
                errorMessage = "Cần cấp quyền vị trí để hiển thị vị trí hiện tại trên bản đồ."
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
        youtubeQuery = query
        onlineSearchActive = true
        jamendoTracks.clear()
        audiusTracks.clear()
        youtubeTracks.clear()
        youtubeNextPageToken = null
        errorMessage = null
        searchJamendo()
        searchAudius(query)
        searchYouTube()
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
                controller?.duration?.takeIf { it > 0L }?.let { duration = it }
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
            if (activeRadioTitle != null) {
                if (playbackState == Player.STATE_BUFFERING) {
                    scheduleRadioRecovery()
                } else if (playbackState == Player.STATE_READY) {
                    cancelRadioRecovery()
                }
            }
            controller?.duration?.takeIf { it > 0L }?.let { duration = it }
            if (playbackState == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_OFF) {
                // A naturally finished last item must persist 00:00 as well.
                // Otherwise on reopening the app, the old end-position from
                // SharedPreferences could be restored unexpectedly.
                controller?.seekTo(0L)
                isPlaying = false
                position = 0L
                savedPosition = 0L
                savePlaybackState()
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            isPlaying = false
            cancelRadioRecovery()

            val radioTitle = activeRadioTitle
            if (radioTitle != null && activeRadioStreamIndex + 1 < activeRadioStreams.size) {
                activeRadioStreamIndex++
                val fallbackUrl = activeRadioStreams[activeRadioStreamIndex]
                onlineUrl = fallbackUrl
                errorMessage = "Luồng $radioTitle lỗi, đang thử nguồn dự phòng…"
                lifecycleScope.launch {
                    delay(350L)
                    playRadioFallback(radioTitle, fallbackUrl)
                }
                return
            }

            if (radioTitle != null && openRadioOfficialSource(radioTitle)) {
                errorMessage = "${radioTitle} không phát được bằng luồng trực tiếp; đã chuyển sang nguồn chính thức trong ứng dụng."
                return
            }

            errorMessage = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Mất kết nối mạng khi phát ${radioTitle ?: "nhạc online"}."
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "Luồng ${radioTitle ?: "âm thanh"} không được hỗ trợ hoặc máy chủ đang thay đổi nguồn phát."
                else -> "Không thể phát ${radioTitle ?: "bài hát"}. Hãy kiểm tra kết nối mạng."
            }

            activeRadioTitle = null
            activeRadioStreams = emptyList()
            activeRadioStreamIndex = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        playlistStore = PlaylistStore(this)
        playlists.addAll(playlistStore.load())
        loadCustomTvSources()
        loadSavedState()
        loadDriveRecentLinks()
        restoreSleepTimer()

        // PRO shell deep-links into the proven feature surfaces without
        // duplicating their implementation. This keeps YouTube, Drive,
        // Radio, TV, Map and Media3 playback on the same stable code paths.
        handleProDestination(intent.getStringExtra("pro_destination"))

        setContent { NgocSiMusicApp() }
        requestMusicPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
        connectController()
    }

    private fun handleProDestination(destination: String?) {
        when (destination) {
            "library" -> {
                selectedSection = "Thư viện"
                selectedLibrary = "Tất cả"
                libraryView = "Bài hát"
            }
            "youtube" -> {
                selectedSection = "Online"
                onlineHubTab = "YouTube"
            }
            "drive" -> {
                selectedSection = "Online"
                onlineHubTab = "Tất cả"
                errorMessage = "Google Drive: chọn File Drive hoặc Thư mục để nhập nhạc."
            }
            "radio" -> {
                selectedSection = "Radio"
                showVietnamRadioHub = true
            }
            "tv" -> selectedSection = "TV"
            "map" -> selectedSection = "Bản đồ"
            "playlists" -> showPlaylists = true
            "queue" -> showQueue = true
            "player" -> {
                if (songs.getOrNull(currentIndex) != null) {
                    showNowPlaying = true
                } else {
                    selectedSection = "Thư viện"
                    selectedLibrary = "Tất cả"
                    libraryView = "Bài hát"
                    errorMessage = "Chưa có bài hát đang phát. Hãy chọn một bài hát để mở trình phát."
                }
            }
            "settings" -> selectedSection = "Cài đặt"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProDestination(intent.getStringExtra("pro_destination"))
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
                    if (c.mediaItemCount == 0 && queueSongs.isNotEmpty()) {
                        c.shuffleModeEnabled = shuffleEnabled
                        c.repeatMode = repeatMode
                        c.setMediaItems(queueSongs.map { mediaItemFor(it) })
                        c.prepare()

                        val restoreIndex = lastSongUri?.let { uri ->
                            queueSongs.indexOfFirst { it.uri.toString() == uri }
                        } ?: -1
                        if (restoreIndex >= 0) {
                            c.seekToDefaultPosition(restoreIndex)
                            if (savedPosition > 0L) c.seekTo(savedPosition)
                        }
                    }

                    val currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString()

                    // Re-arm the Radio recovery watchdog when a saved Radio item
                    // is restored by Media3 after a process restart. Without this,
                    // the UI can show the station correctly but won't know which
                    // fallback streams belong to the active station.
                    val restoredRadio = currentUri?.let { uri ->
                        RadioCatalog.stations.firstOrNull { station ->
                            station.streamUrls.contains(uri)
                        }
                    }
                    if (restoredRadio != null) {
                        activeRadioTitle = restoredRadio.title
                        activeRadioStreams = restoredRadio.streamUrls
                        activeRadioStreamIndex =
                            activeRadioStreams.indexOf(currentUri).coerceAtLeast(0)
                    } else if (currentUri != null) {
                        clearActiveRadioState()
                    }

                    currentIndex = when {
                        currentUri != null -> songs.indexOfFirst { it.uri.toString() == currentUri }
                        c.currentMediaItemIndex >= 0 && c.currentMediaItemIndex < c.mediaItemCount -> {
                            val queueUri = c.getMediaItemAt(c.currentMediaItemIndex)
                                .localConfiguration?.uri?.toString()
                            queueUri?.let { uri -> songs.indexOfFirst { it.uri.toString() == uri } } ?: -1
                        }
                        else -> -1
                    }
                    // The MediaSession service is authoritative when playback
                    // continues in the background or resumes from the lock screen.
                    // Do not overwrite its persisted speed with the Activity default.
                    selectedPlaybackSpeed = c.playbackParameters.speed.coerceIn(0.5f, 2.0f)
                    shuffleEnabled = c.shuffleModeEnabled
                    repeatMode = c.repeatMode
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
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val rawPath = dataCol.takeIf { it >= 0 }?.let { cursor.getString(it).orEmpty() }.orEmpty()
                val folder = rawPath.takeIf { it.isNotBlank() }
                    ?.let { File(it).parentFile?.name }
                    ?.ifBlank { null }
                    ?: "Thiết bị"
                result += Song(id, cursor.getString(titleCol).orEmpty().ifBlank { "Không có tên" },
                    cursor.getString(artistCol).orEmpty().ifBlank { "Nghệ sĩ không rõ" },
                    cursor.getLong(durationCol), ContentUris.withAppendedId(collection, id), "Thiết bị", cursor.getLong(albumIdCol), folder = folder)
            }
        }
        val savedDriveUris = prefs.getStringSet("drive_uris", emptySet()) ?: emptySet()
        val savedOnlineUris = prefs.getStringSet("online_uris", emptySet()) ?: emptySet()
        val existing = result.map { it.uri.toString() }.toMutableSet()

        // Rehydrate files previously linked from a Google Drive sharing URL.
        val sharedItems = loadSharedDriveItems()
        val sharedApiKey = driveApiKey()
        if (sharedApiKey.isNotBlank()) {
            sharedItems.forEach { item ->
                val uri = sharedDriveMediaUri(item, sharedApiKey)
                val raw = uri.toString()
                if (existing.add(raw)) {
                    result += Song(
                        id = -kotlin.math.abs(raw.hashCode().toLong()),
                        title = item.name.substringBeforeLast(".").ifBlank { item.name },
                        artist = "Google Drive • Chia sẻ",
                        duration = 0L,
                        uri = uri,
                        source = "Google Drive",
                        folder = "Drive chia sẻ"
                    )
                }
            }
        }

        val staleDriveUris = mutableSetOf<String>()
        savedDriveUris.forEach { raw ->
            val uri = Uri.parse(raw)
            if (existing.add(raw)) {
                val song = songFromUri(uri)
                if (song != null) result += song else staleDriveUris += raw
            }
        }
        if (staleDriveUris.isNotEmpty()) {
            val cleanedDriveUris = savedDriveUris.toMutableSet().apply { removeAll(staleDriveUris) }
            prefs.edit().putStringSet("drive_uris", cleanedDriveUris).apply()
        }
        savedOnlineUris.forEach { raw ->
            val uri = Uri.parse(raw)
            if (existing.add(raw)) result += onlineSongFromUri(uri)
        }
        // Keep the library in its own stable order. Queue order is restored
        // separately below so reordering the queue never reorders the library.
        val hasSavedQueue = prefs.contains("queue_order")
        val savedQueueOrder = prefs.getString("queue_order", "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        songs.clear()
        songs.addAll(result)

        // Rehydrate a transient Radio item saved by MusicService so background
        // playback/lock-screen resumption is not discarded when MainActivity
        // rebuilds its local library model after a process restart.
        if (savedQueueOrder.isNotEmpty()) {
            val queueMetadataByUri = mutableMapOf<String, org.json.JSONObject>()
            prefs.getString("queue_metadata", null)?.let { raw ->
                runCatching {
                    val array = org.json.JSONArray(raw)
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val uri = item.optString("uri")
                        if (uri.isNotBlank()) queueMetadataByUri[uri] = item
                    }
                }
            }

            savedQueueOrder.forEach { rawUri ->
                if (songs.any { it.uri.toString() == rawUri }) return@forEach
                val radioStation = RadioCatalog.stations.firstOrNull { station ->
                    station.streamUrls.any { it == rawUri }
                } ?: return@forEach

                val metadata = queueMetadataByUri[rawUri]
                songs.add(
                    Song(
                        id = -kotlin.math.abs(rawUri.hashCode().toLong()),
                        title = metadata?.optString("title").orEmpty()
                            .ifBlank { radioStation.title },
                        artist = metadata?.optString("artist").orEmpty()
                            .ifBlank { "VOV" },
                        duration = 0L,
                        uri = Uri.parse(rawUri),
                        source = "Radio Việt Nam",
                        folder = "Radio Việt Nam"
                    )
                )
            }
        }

        queueSongs.clear()
        val byUri = songs.associateBy { it.uri.toString() }
        if (hasSavedQueue) {
            // An explicitly saved empty queue stays empty across app restarts.
            // Drop entries that no longer exist in the current library, then
            // persist the cleaned order so stale Drive/provider URIs do not
            // survive another restart.
            savedQueueOrder.forEach { uri -> byUri[uri]?.let { queueSongs.add(it) } }
            if (queueSongs.size != savedQueueOrder.size) {
                saveQueueOrder()
            }
        } else {
            // First launch: initialize the queue from the complete library.
            queueSongs.addAll(songs)
            saveQueueOrder()
        }
        errorMessage = if (songs.isEmpty()) "Chưa tìm thấy file nhạc trong thiết bị." else null
        controller?.let { c ->
            // If MusicService already owns a matching queue, preserve its active item
            // and exact position. If the library changed with the same item count,
            // rebuild the queue so a library index can never point at the wrong URI.
            if (c.mediaItemCount == 0 && songs.isNotEmpty()) {
                c.setMediaItems(queueSongs.map { mediaItemFor(it) })
                c.prepare()

                val restoreQueueIndex = lastSongUri?.let { uri ->
                    queueSongs.indexOfFirst { it.uri.toString() == uri }
                } ?: -1
                if (restoreQueueIndex >= 0) {
                    currentIndex = songs.indexOfFirst { it.uri.toString() == lastSongUri }
                    c.seekToDefaultPosition(restoreQueueIndex)
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
        if (c.mediaItemCount != queueSongs.size) return false
        return queueSongs.indices.all { index ->
            c.getMediaItemAt(index).localConfiguration?.uri == queueSongs[index].uri
        }
    }

    private fun playFilteredSongs(items: List<Song>) {
        val first = items.firstOrNull() ?: return
        val c = controller ?: run {
            errorMessage = "Trình phát đang khởi động, thử lại sau."
            return
        }
        clearActiveRadioState()
        queueSongs.clear()
        queueSongs.addAll(items)
        c.setMediaItems(queueSongs.map { mediaItemFor(it) }, 0, 0L)
        c.shuffleModeEnabled = shuffleEnabled
        c.repeatMode = repeatMode
        c.setPlaybackSpeed(selectedPlaybackSpeed)
        c.prepare()
        c.play()
        currentIndex = songs.indexOfFirst { it.uri == first.uri }
        lastSongUri = first.uri.toString()
        position = 0L
        duration = c.duration.coerceAtLeast(0L)
        savedPosition = 0L
        saveQueueOrder()
        savePlaybackState()
        errorMessage = "Đang phát ${items.size} bài theo danh sách hiện tại."
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        val c = controller ?: run { errorMessage = "Trình phát đang khởi động, thử lại sau."; return }

        if (songs[index].source != "Radio Việt Nam") {
            clearActiveRadioState()
        }

        if (!isControllerQueueInSync(c)) {
            // Rebuilding the queue must not silently reset Shuffle/Repeat.
            syncControllerQueue()
        }

        // A direct tap on a library item must remain playable even when the
        // user previously replaced the queue with a filtered/playlist subset.
        // Add the target to the logical queue instead of showing a dead-end
        // "not in queue" state.
        if (queueSongs.none { it.uri == songs[index].uri }) {
            queueSongs.add(songs[index])
            syncControllerQueue()
        }

        currentIndex = index
        val queueIndex = queueSongs.indexOfFirst { it.uri == songs[index].uri }
        if (queueIndex < 0) {
            errorMessage = "Không thể thêm bài hát vào hàng đợi."
            return
        }
        c.seekToDefaultPosition(queueIndex)
        c.play()
        shuffleEnabled = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        errorMessage = null
    }

    private fun mediaItemFor(song: Song): MediaItem {
        val rawUri = song.uri.toString().lowercase()
        val path = song.uri.path.orEmpty().lowercase()
        val builder = MediaItem.Builder()
            .setMediaId(song.uri.toString())
            .setUri(song.uri)
        // Force HLS only when the URL actually identifies an HLS manifest.
        // Some radio providers expose a direct AAC/MP3 stream without a .m3u8
        // suffix; forcing APPLICATION_M3U8 on those URLs makes Media3 reject
        // an otherwise playable audio stream.
        val isHls = path.endsWith(".m3u8") ||
            rawUri.contains(".m3u8") ||
            rawUri.contains("/playlist") ||
            rawUri.contains("/manifest")
        if (isHls) {
            builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        return builder
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .apply {
                        if (song.artworkUri != null) {
                            setArtworkUri(song.artworkUri)
                        } else if (song.albumId >= 0) {
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
        val uniqueUris = uris.distinctBy { it.toString() }
        if (uniqueUris.isEmpty()) return

        // Importing a large Drive folder can involve many provider metadata queries.
        // Keep all provider I/O off the main thread so the player UI remains responsive.
        driveImportJob?.cancel()
        val existingUris = songs.map { it.uri.toString() }.toSet()
        errorMessage = "Đang nhập ${uniqueUris.size} file từ Google Drive…"

        driveImportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val saved = (prefs.getStringSet("drive_uris", emptySet()) ?: emptySet()).toMutableSet()
                val importedSongs = mutableListOf<Song>()

                uniqueUris.forEach { uri ->
                    val raw = uri.toString()
                    if (raw in saved || raw in existingUris) return@forEach

                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        // Some providers grant temporary read access without a
                        // persistable grant. The current import can still proceed.
                    }

                    val importedSong = songFromUri(uri)
                    if (importedSong != null) {
                        saved.add(raw)
                        importedSongs += importedSong
                    }
                }

                saved to importedSongs
            }

            val (saved, importedSongs) = result
            prefs.edit().putStringSet("drive_uris", saved).apply()

            importedSongs.forEach { song ->
                if (songs.none { it.uri == song.uri }) songs.add(song)
                if (queueSongs.none { it.uri == song.uri }) queueSongs.add(song)
            }

            syncControllerQueue()
            errorMessage = when {
                importedSongs.isNotEmpty() ->
                    "Đã thêm ${importedSongs.size} bài từ Google Drive."
                else ->
                    "Các bài đã chọn đã có trong thư viện hoặc không còn truy cập được."
            }
            driveImportJob = null
        }
    }

    private fun songFromUri(uri: Uri): Song? {
        var title = "Nhạc online"
        // A persisted Drive URI can outlive its provider grant. Treat a failed
        // metadata query as stale so the broken entry is removed from the library.
        val metadataReadable = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    title = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        .substringBeforeLast(".")
                        .ifBlank { "Nhạc online" }
                }
            }
            true
        }.getOrDefault(false)
        if (!metadataReadable) return null

        return Song(
            id = -kotlin.math.abs(uri.toString().hashCode().toLong()),
            title = title,
            artist = "Google Drive",
            duration = 0L,
            uri = uri,
            source = "Google Drive",
            folder = "Google Drive"
        )
    }

    private data class SharedDriveItem(
        val id: String,
        val name: String,
        val mimeType: String,
        val resourceKey: String,
        val size: Long
    )

    private fun driveApiKey(): String = BuildConfig.DRIVE_API_KEY.trim()
        .ifBlank { BuildConfig.YOUTUBE_API_KEY.trim() }

    private fun extractDriveIdAndResourceKey(raw: String): Pair<String, String?>? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("drive.google.com") && !host.endsWith("docs.google.com")) return null

        val queryId = uri.getQueryParameter("id").orEmpty().trim()
        val segments = uri.pathSegments.orEmpty()
        val marker = segments.indexOfFirst {
            it.equals("d", true) || it.equals("folders", true)
        }
        val id = when {
            queryId.isNotBlank() -> queryId
            marker >= 0 && marker + 1 < segments.size -> segments[marker + 1]
            else -> ""
        }.trim().removeSuffix("/")

        if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9_-]{10,}"))) return null
        return id to uri.getQueryParameter("resourcekey")
    }

    private fun driveApiGet(url: String): org.json.JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.7 (Android)")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    org.json.JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                throw java.io.IOException(
                    if (message.isBlank()) "Google Drive HTTP $code" else message
                )
            }
            return org.json.JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun inspectSharedDriveItem(
        id: String,
        resourceKey: String?,
        apiKey: String
    ): org.json.JSONObject {
        val url = "https://www.googleapis.com/drive/v3/files/" +
            Uri.encode(id) +
            "?fields=id,name,mimeType,size,resourceKey,trashed,capabilities/canDownload" +
            "&supportsAllDrives=true" +
            "&key=" + URLEncoder.encode(apiKey, "UTF-8") +
            (resourceKey?.takeIf { it.isNotBlank() }?.let {
                "&resourceKey=" + URLEncoder.encode(it, "UTF-8")
            } ?: "")
        return driveApiGet(url)
    }

    private fun listSharedDriveFolder(
        folderId: String,
        resourceKey: String?,
        apiKey: String,
        result: MutableList<SharedDriveItem>,
        visited: MutableSet<String>,
        depth: Int = 0
    ) {
        if (depth > 8 || !visited.add(folderId)) return

        var pageToken: String? = null
        do {
            val query = URLEncoder.encode(
                "'$folderId' in parents and trashed = false",
                "UTF-8"
            )
            val url = "https://www.googleapis.com/drive/v3/files?q=$query" +
                "&pageSize=1000" +
                "&fields=nextPageToken,files(id,name,mimeType,size,resourceKey,trashed,capabilities/canDownload)" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&key=" + URLEncoder.encode(apiKey, "UTF-8") +
                (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
                (resourceKey?.takeIf { it.isNotBlank() }?.let {
                    "&resourceKey=" + URLEncoder.encode(it, "UTF-8")
                } ?: "")

            val json = driveApiGet(url)
            val files = json.optJSONArray("files") ?: org.json.JSONArray()
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").ifBlank { "Google Drive" }
                val mime = item.optString("mimeType").trim()
                val childKey = item.optString("resourceKey").trim()
                val canDownload = item.optJSONObject("capabilities")
                    ?.optBoolean("canDownload", true) ?: true
                if (id.isBlank() || !canDownload) continue

                if (mime == "application/vnd.google-apps.folder") {
                    listSharedDriveFolder(
                        id,
                        childKey.ifBlank { null },
                        apiKey,
                        result,
                        visited,
                        depth + 1
                    )
                } else if (isSupportedDriveAudio(name, mime)) {
                    result += SharedDriveItem(
                        id,
                        name,
                        mime,
                        childKey,
                        item.optLong("size", 0L)
                    )
                }
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (!pageToken.isNullOrBlank())
    }

    private fun isSupportedDriveAudio(name: String, mimeType: String): Boolean {
        val type = mimeType.lowercase()
        val fileName = name.lowercase()
        return type.startsWith("audio/") ||
            fileName.endsWith(".mp3") ||
            fileName.endsWith(".m4a") ||
            fileName.endsWith(".aac") ||
            fileName.endsWith(".flac") ||
            fileName.endsWith(".wav") ||
            fileName.endsWith(".ogg") ||
            fileName.endsWith(".opus") ||
            fileName.endsWith(".webm")
    }

    private fun sharedDriveMediaUri(item: SharedDriveItem, apiKey: String): Uri {
        val builder = Uri.parse(
            "https://www.googleapis.com/drive/v3/files/" + Uri.encode(item.id)
        ).buildUpon()
            .appendQueryParameter("alt", "media")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("key", apiKey)
        if (item.resourceKey.isNotBlank()) {
            builder.appendQueryParameter("resourceKey", item.resourceKey)
        }
        return builder.build()
    }

    private fun loadSharedDriveItems(): MutableList<SharedDriveItem> {
        val raw = prefs.getString("drive_shared_items", null) ?: return mutableListOf()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        SharedDriveItem(
                            id,
                            name,
                            item.optString("mimeType"),
                            item.optString("resourceKey"),
                            item.optLong("size", 0L)
                        )
                    )
                }
            }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun saveSharedDriveItems(items: List<SharedDriveItem>) {
        val array = org.json.JSONArray()
        items.distinctBy { it.id }.forEach { item ->
            array.put(
                org.json.JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("mimeType", item.mimeType)
                    put("resourceKey", item.resourceKey)
                    put("size", item.size)
                }
            )
        }
        prefs.edit().putString("drive_shared_items", array.toString()).apply()
    }

    private fun loadDriveRecentLinks() {
        driveRecentLinks = prefs.getString("drive_recent_links", null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(8)
            ?: emptyList()
        val count = loadSharedDriveItems().size
        driveSharedStatus = if (count > 0) "Đã liên kết $count tệp Drive chia sẻ" else "Chưa liên kết nguồn Drive chia sẻ"
    }

    private fun saveDriveRecentLink(link: String) {
        val value = link.trim()
        if (value.isBlank()) return
        val updated = buildList {
            add(value)
            addAll(driveRecentLinks.filterNot { it == value })
        }.take(8)
        driveRecentLinks = updated
        prefs.edit().putString("drive_recent_links", updated.joinToString("\n")).apply()
    }

    private fun openGoogleDrive() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/my-drive")))
        }.onFailure {
            errorMessage = "Không mở được Google Drive."
        }
    }

    private fun clearSharedDriveLibrary() {
        val sharedUris = songs.filter { it.source == "Google Drive" }.map { it.uri.toString() }.toSet()
        if (sharedUris.isEmpty()) {
            driveSharedStatus = "Không có nhạc Drive chia sẻ để xóa."
            return
        }
        val activeUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        prefs.edit().remove("drive_shared_items").apply()
        songs.removeAll { it.source == "Google Drive" }
        queueSongs.removeAll { it.source == "Google Drive" }
        if (activeUri != null && sharedUris.contains(activeUri)) {
            controller?.stop()
            currentIndex = -1
            isPlaying = false
            position = 0L
        } else {
            currentIndex = songs.indexOfFirst { it.uri.toString() == activeUri }
        }
        syncControllerQueue()
        driveSharedStatus = "Đã xóa nhạc Drive chia sẻ khỏi thư viện."
        errorMessage = "Đã xóa nhạc Google Drive chia sẻ."
    }

    private fun importSharedDriveLink() {
        val parsed = extractDriveIdAndResourceKey(driveSharedLink)
        if (parsed == null) {
            errorMessage = "Link Google Drive không hợp lệ. Hãy dán link tệp hoặc thư mục Drive."
            return
        }

        val apiKey = driveApiKey()
        if (apiKey.isBlank()) {
            errorMessage = "Chưa có Google Drive API key. Thêm GitHub Secret DRIVE_API_KEY và bật Drive API."
            return
        }

        driveImportJob?.cancel()
        driveSharedLoading = true
        errorMessage = "Đang kết nối Google Drive được chia sẻ…"

        driveImportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (id, suppliedResourceKey) = parsed
                    val root = inspectSharedDriveItem(id, suppliedResourceKey, apiKey)
                    if (root.optBoolean("trashed", false)) error("Nguồn Drive đã bị xóa.")
                    val canDownload = root.optJSONObject("capabilities")
                        ?.optBoolean("canDownload", true) ?: true
                    if (!canDownload) error("Nguồn Drive không cho phép tải nội dung.")

                    val rootName = root.optString("name").ifBlank { "Google Drive" }
                    val mime = root.optString("mimeType").trim()
                    val rootResourceKey = root.optString("resourceKey")
                        .ifBlank { suppliedResourceKey.orEmpty() }
                        .ifBlank { null }

                    val collected = mutableListOf<SharedDriveItem>()
                    if (mime == "application/vnd.google-apps.folder") {
                        listSharedDriveFolder(
                            id,
                            rootResourceKey,
                            apiKey,
                            collected,
                            mutableSetOf()
                        )
                    } else {
                        if (!isSupportedDriveAudio(rootName, mime)) {
                            error("Link này không trỏ tới tệp âm thanh được hỗ trợ.")
                        }
                        collected += SharedDriveItem(
                            id,
                            rootName,
                            mime,
                            rootResourceKey.orEmpty(),
                            root.optLong("size", 0L)
                        )
                    }
                    collected.distinctBy { it.id }
                }
            }

            result.onSuccess { items ->
                val currentUris = songs.map { it.uri.toString() }.toMutableSet()
                val savedItems = loadSharedDriveItems().toMutableList()
                var added = 0

                items.forEach { item ->
                    val uri = sharedDriveMediaUri(item, apiKey)
                    val raw = uri.toString()
                    if (!currentUris.add(raw)) return@forEach

                    val song = Song(
                        id = -kotlin.math.abs(raw.hashCode().toLong()),
                        title = item.name.substringBeforeLast(".").ifBlank { item.name },
                        artist = "Google Drive • Chia sẻ",
                        duration = 0L,
                        uri = uri,
                        source = "Google Drive",
                        folder = "Drive chia sẻ"
                    )
                    songs.add(song)
                    if (queueSongs.none { it.uri == uri }) queueSongs.add(song)
                    savedItems.removeAll { it.id == item.id }
                    savedItems.add(item)
                    added++
                }

                saveSharedDriveItems(savedItems)
                saveDriveRecentLink(driveSharedLink)
                driveSharedStatus = "Đã liên kết $added tệp từ nguồn Drive chia sẻ"
                syncControllerQueue()
                driveSharedLoading = false
                driveSharedLink = ""
                errorMessage = if (added > 0) {
                    "Đã liên kết $added bài từ Google Drive được chia sẻ."
                } else {
                    "Các bài trong link Drive đã có trong thư viện."
                }
                driveImportJob = null
            }.onFailure { e ->
                driveSharedLoading = false
                driveImportJob = null
                driveSharedStatus = "Không thể liên kết nguồn Drive chia sẻ"
                val message = e.message.orEmpty()
                errorMessage = when {
                    message.contains("403") || message.contains("permission", true) ||
                        message.contains("insufficient", true) ->
                        "Drive từ chối quyền. Link cần được chia sẻ theo “Bất kỳ ai có liên kết” hoặc tài khoản đang dùng phải có quyền xem/tải."
                    else ->
                        "Không đọc được link Google Drive: " +
                            message.ifBlank { "nguồn không cho phép truy cập trực tiếp" }
                }
            }
        }
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

        errorMessage = "Đang quét thư mục Google Drive…"

        lifecycleScope.launch {
            val audioUris = withContext(Dispatchers.IO) {
                val result = mutableListOf<Uri>()
                collectDriveAudioFiles(root, result)
                result.distinct()
            }

            if (audioUris.isEmpty()) {
                errorMessage = "Không tìm thấy file âm thanh trong thư mục đã chọn."
                return@launch
            }

            importDriveSongs(audioUris)
        }
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

    private fun verifiedRadioStreams(title: String): List<String> =
        RadioCatalog.find(title)?.streamUrls.orEmpty()

    private fun scheduleRadioRecovery() {
        cancelRadioRecovery()
        if (activeRadioStreams.isNotEmpty()) {
            radioRecoveryHandler.postDelayed(radioRecoveryRunnable, 12_000L)
        }
    }

    private fun cancelRadioRecovery() {
        radioRecoveryHandler.removeCallbacks(radioRecoveryRunnable)
    }

    private fun openRadioOfficialSource(title: String): Boolean {
        val sourceUrl = RadioCatalog.find(title)?.sourceUrl ?: return false
        controller?.pause()
        cancelRadioRecovery()
        radioWebTitle = title
        radioWebUrl = sourceUrl
        activeRadioTitle = null
        activeRadioStreams = emptyList()
        activeRadioStreamIndex = 0
        return true
    }

    private fun recoverBufferedRadio() {
        val title = activeRadioTitle ?: return
        val c = controller ?: return
        val currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString()
        val expectedUri = activeRadioStreams.getOrNull(activeRadioStreamIndex)
        if (c.isPlaying || c.playbackState != Player.STATE_BUFFERING || currentUri != expectedUri) {
            return
        }

        val nextIndex = activeRadioStreamIndex + 1
        if (nextIndex >= activeRadioStreams.size) {
            if (openRadioOfficialSource(title)) {
                errorMessage = "$title không còn luồng HLS khả dụng; đã chuyển sang nguồn chính thức trong ứng dụng."
            } else {
                errorMessage = "$title đang gặp sự cố kết nối. Không còn luồng dự phòng khả dụng."
                cancelRadioRecovery()
            }
            return
        }

        activeRadioStreamIndex = nextIndex
        val fallbackUrl = activeRadioStreams[nextIndex]
        onlineUrl = fallbackUrl
        errorMessage = "$title đang chậm, tự động chuyển sang luồng dự phòng…"
        playRadioFallback(title, fallbackUrl)
    }

    private fun playVerifiedRadio(title: String, streamUrls: List<String>) {
        val candidates = streamUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) {
            errorMessage = "Chưa có luồng phát cho $title."
            return
        }

        cancelRadioRecovery()
        activeRadioTitle = title
        activeRadioStreams = candidates
        activeRadioStreamIndex = 0
        onlineUrl = candidates.first()
        errorMessage = "Đang kết nối $title…"
        playOnlineUrl(displayTitle = title, displayArtist = "VOV")
    }

    private fun clearActiveRadioState() {
        cancelRadioRecovery()
        activeRadioTitle = null
        activeRadioStreams = emptyList()
        activeRadioStreamIndex = 0
    }
    private fun playRadioFallback(title: String, streamUrl: String) {
        val c = controller ?: return
        val uri = runCatching { Uri.parse(streamUrl) }.getOrNull() ?: return

        val song = Song(
            id = -kotlin.math.abs(streamUrl.hashCode().toLong()),
            title = title,
            artist = "VOV",
            duration = 0L,
            uri = uri,
            source = "Radio Việt Nam",
            folder = "Radio Việt Nam"
        )

        // Replace any older radio item so a failed candidate is not left
        // behind in Previous/Next or in the persisted queue.
        songs.removeAll { it.source == "Radio Việt Nam" }
        queueSongs.removeAll { it.source == "Radio Việt Nam" }
        songs.add(song)
        queueSongs.add(song)

        val queueIndex = queueSongs.lastIndex
        if (queueIndex < 0) return

        val keepShuffle = c.shuffleModeEnabled
        val keepRepeat = c.repeatMode

        c.setMediaItems(queueSongs.map { mediaItemFor(it) }, queueIndex, 0L)
        c.shuffleModeEnabled = keepShuffle
        c.repeatMode = keepRepeat
        c.setPlaybackSpeed(selectedPlaybackSpeed)
        c.prepare()
        c.play()

        currentIndex = songs.indexOfFirst { it.uri == uri }
        lastSongUri = uri.toString()
        position = 0L
        savedPosition = 0L
        saveQueueOrder()
        savePlaybackState()
        errorMessage = "Đang phát $title."
    }

    private fun playOnlineUrl(
        displayTitle: String? = null,
        displayArtist: String = "Online"
    ) {
        val raw = onlineUrl.trim()
        if (raw.isBlank()) {
            errorMessage = "Nhập URL âm thanh trực tiếp (HTTPS)."
            return
        }
        val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            errorMessage = "URL không hợp lệ. Hãy dùng HTTPS trỏ trực tiếp tới luồng âm thanh."
            return
        }

        val title = displayTitle
            ?.trim()
            ?.ifBlank { null }
            ?: uri.lastPathSegment
                ?.substringBeforeLast(".")
                ?.ifBlank { null }
            ?: uri.host.orEmpty().ifBlank { "Nhạc Online" }

        val isRadio = displayArtist == "VOV"
        val song = Song(
            id = -kotlin.math.abs(raw.hashCode().toLong()),
            title = title,
            artist = displayArtist,
            duration = 0L,
            uri = uri,
            source = if (isRadio) "Radio Việt Nam" else "Online"
        )

        if (isRadio) {
            // A radio station is a transient live source, not a permanent
            // library/queue entry. Keep at most one active radio item so
            // previous stations cannot be revisited accidentally.
            // Preserve activeRadioTitle/streams here: the recovery watchdog
            // depends on that state while Media3 is buffering or failing over.
            songs.removeAll { it.source == "Radio Việt Nam" }
            queueSongs.removeAll { it.source == "Radio Việt Nam" }
        } else {
            clearActiveRadioState()
            val saved = (prefs.getStringSet("online_uris", emptySet()) ?: emptySet()).toMutableSet()
            saved.add(raw)
            prefs.edit().putStringSet("online_uris", saved).apply()
        }

        val existingIndex = songs.indexOfFirst { it.uri.toString() == raw }
        val index = if (existingIndex >= 0) existingIndex else {
            songs.add(song)
            queueSongs.add(song)
            songs.lastIndex
        }

        if (existingIndex >= 0 && queueSongs.none { it.uri == uri }) {
            queueSongs.add(songs[index])
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
            source = "Online",
            folder = "Online"
        )
    }

    private fun clearOnlineLibrary() {
        val activeUri = controller?.currentMediaItem?.localConfiguration?.uri
            ?: songs.getOrNull(currentIndex)?.uri

        prefs.edit().remove("online_uris").apply()
        songs.removeAll { it.source == "Online" }
        queueSongs.removeAll { it.source == "Online" }

        // Re-resolve by URI after removal so an earlier deleted item cannot
        // shift currentIndex onto an unrelated song.
        currentIndex = activeUri?.let { uri ->
            songs.indexOfFirst { it.uri == uri }
        } ?: -1
        if (currentIndex >= 0) lastSongUri = activeUri?.toString()

        syncControllerQueue()
        errorMessage = "Đã xóa các luồng online đã lưu."
    }

    private fun openOnlineSource(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            errorMessage = "Không mở được nguồn online."
        }
    }

    private fun searchJamendo() {
        val q = jamendoQuery.trim()
        if (q.isBlank()) {
            errorMessage = "Nhập tên bài hát hoặc nghệ sĩ để tìm."
            return
        }

        jamendoSearchJob?.cancel()
        jamendoLoading = true
        errorMessage = null

        jamendoSearchJob = lifecycleScope.launch(Dispatchers.IO) {
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
                    setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.2")
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
                    if (jamendoQuery.trim() == q) {
                        jamendoTracks.clear()
                        jamendoLoading = false
                        errorMessage = "Lỗi tìm nhạc online: ${e.message ?: "Không kết nối được Jamendo"}"
                    }
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
            source = "Audius",
            artworkUri = track.imageUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
        )
        val existingIndex = songs.indexOfFirst { it.uri.toString() == track.streamUrl }
        val index = if (existingIndex >= 0) existingIndex else {
            songs.add(song)
            queueSongs.add(song)
            songs.lastIndex
        }
        if (existingIndex >= 0 && queueSongs.none { it.uri == song.uri }) {
            queueSongs.add(songs[index])
        }
        syncControllerQueue()
        play(index)
    }

    private fun searchAudius(q: String) {
        audiusSearchJob?.cancel()
        audiusLoading = true
        audiusSearchJob = lifecycleScope.launch(Dispatchers.IO) {
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
            source = "Jamendo",
            artworkUri = track.imageUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
        )
        val existingIndex = songs.indexOfFirst { it.uri.toString() == raw }
        val index = if (existingIndex >= 0) existingIndex else {
            songs.add(song)
            queueSongs.add(song)
            songs.lastIndex
        }
        if (existingIndex >= 0 && queueSongs.none { it.uri == song.uri }) {
            queueSongs.add(songs[index])
        }
        syncControllerQueue()
        play(index)
    }

    private fun syncControllerQueue() {
        controller?.let { c ->
            if (queueSongs.isEmpty()) {
                // Never leave stale Media3 items or persisted queue metadata
                // after the playback queue is emptied.
                c.pause()
                c.clearMediaItems()
                currentIndex = -1
                position = 0L
                savedPosition = 0L
                lastSongUri = null
                saveQueueOrder()
                savePlaybackState()
                return@let
            }
            if (queueSongs.isNotEmpty()) {
                // Rebuild the queue only after capturing the exact active item,
                // position, playing state, shuffle mode and repeat mode.
                val controllerWasEmpty = c.mediaItemCount == 0
                val selectedUri = c.currentMediaItem?.localConfiguration?.uri
                    ?: if (controllerWasEmpty) lastSongUri?.let(Uri::parse) else null
                val savedPositionMs = if (controllerWasEmpty) {
                    savedPosition.coerceAtLeast(0L)
                } else {
                    c.currentPosition.coerceAtLeast(0L)
                }
                val wasPlaying = c.isPlaying
                val keepShuffle = c.shuffleModeEnabled
                val keepRepeat = c.repeatMode

                c.setMediaItems(queueSongs.map { mediaItemFor(it) })
                saveQueueOrder()
                c.shuffleModeEnabled = keepShuffle
                c.repeatMode = keepRepeat
                c.prepare()

                val queueIndex = selectedUri?.let { uri ->
                    queueSongs.indexOfFirst { it.uri == uri }
                } ?: -1

                if (queueIndex >= 0) {
                    c.seekToDefaultPosition(queueIndex)
                    if (savedPositionMs > 0L) c.seekTo(savedPositionMs)
                    if (wasPlaying) c.play()
                }

                shuffleEnabled = c.shuffleModeEnabled
                repeatMode = c.repeatMode
                c.setPlaybackSpeed(selectedPlaybackSpeed)
                position = c.currentPosition.coerceAtLeast(0L)
                isPlaying = c.isPlaying
            }
        }
    }

    private fun restoreSleepTimer() {
        val endAt = prefs.getLong("sleep_timer_end_at", 0L).coerceAtLeast(0L)
        sleepTimerJob?.cancel()
        if (endAt <= System.currentTimeMillis()) {
            sleepTimerEndAt = 0L
            sleepMinutes = 0
            prefs.edit().remove("sleep_timer_end_at").apply()
            return
        }

        sleepTimerEndAt = endAt
        val remainingMs = endAt - System.currentTimeMillis()
        sleepMinutes = kotlin.math.ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
        sleepTimerJob = lifecycleScope.launch {
            delay(remainingMs)
            controller?.pause()
            controller?.seekTo(0L)
            position = 0L
            savedPosition = 0L
            sleepMinutes = 0
            sleepTimerEndAt = 0L
            sleepTimerJob = null
            prefs.edit().remove("sleep_timer_end_at").apply()
            savePlaybackState()
            errorMessage = "Hẹn giờ đã tắt nhạc."
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerJob = null
            sleepTimerEndAt = 0L
            sleepMinutes = 0
            prefs.edit().remove("sleep_timer_end_at").apply()
            return
        }

        val endAt = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerEndAt = endAt
        sleepMinutes = minutes
        prefs.edit().putLong("sleep_timer_end_at", endAt).apply()

        sleepTimerJob = lifecycleScope.launch {
            delay((endAt - System.currentTimeMillis()).coerceAtLeast(0L))
            controller?.pause()
            controller?.seekTo(0L)
            position = 0L
            savedPosition = 0L
            sleepMinutes = 0
            sleepTimerEndAt = 0L
            sleepTimerJob = null
            prefs.edit().remove("sleep_timer_end_at").apply()
            savePlaybackState()
            errorMessage = "Hẹn giờ đã tắt nhạc."
        }
    }
    private fun togglePlayPause() {
        val c = controller ?: return
        if (queueSongs.isNotEmpty() && !isControllerQueueInSync(c)) {
            syncControllerQueue()
        }
        if (c.mediaItemCount == 0 && queueSongs.isNotEmpty()) {
            // Use the canonical sync path so an empty controller restores the
            // saved item/position and keeps Shuffle/Repeat behavior consistent.
            syncControllerQueue()
            c.play()
        } else if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }

    private fun next() {
        val c = controller ?: return
        if (queueSongs.isEmpty()) return
        if (!isControllerQueueInSync(c)) syncControllerQueue()
        c.seekToNextMediaItem()
        c.play()
    }

    private fun previous() {
        val c = controller ?: return
        if (queueSongs.isEmpty()) return
        if (!isControllerQueueInSync(c)) syncControllerQueue()

        // Standard music-player behavior: pressing Previous near the start
        // goes to the previous track; otherwise restart the current track.
        if (c.currentPosition > 3_000L) {
            c.seekTo(0L)
        } else {
            c.seekToPreviousMediaItem()
        }
        c.play()
        position = c.currentPosition.coerceAtLeast(0L)
        savePlaybackState()
    }
    private fun playNext(song: Song) {
        val c = controller ?: run {
            errorMessage = "Trình phát đang khởi động, thử lại sau."
            return
        }

        // Keep the visible queue order canonical and place the selected song
        // immediately after the current item. Do not duplicate an existing item.
        val existingIndex = queueSongs.indexOfFirst { it.uri == song.uri }
        if (existingIndex >= 0) {
            queueSongs.removeAt(existingIndex)
        }

        val activeUri = c.currentMediaItem?.localConfiguration?.uri
        val insertIndex = activeUri
            ?.let { uri -> queueSongs.indexOfFirst { it.uri == uri } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?.coerceAtMost(queueSongs.size)
            ?: queueSongs.size

        queueSongs.add(insertIndex, song)
        syncControllerQueue()
        errorMessage = "Đã đặt phát tiếp theo: " + song.title
    }

    private fun addToQueue(song: Song) {
        if (queueSongs.any { it.uri == song.uri }) {
            errorMessage = "Bài hát đã có trong hàng đợi."
            return
        }

        // queueSongs is the canonical logical order. Rebuild Media3 from it so
        // Shuffle/Repeat, the current item and the playback position stay aligned.
        queueSongs.add(song)
        syncControllerQueue()
        errorMessage = "Đã thêm vào hàng đợi: " + song.title
    }

    private fun removeFromQueue(index: Int) {
        if (index !in queueSongs.indices) return

        val removed = queueSongs[index]
        val controller = controller
        val currentUri = controller?.currentMediaItem?.localConfiguration?.uri
        val removingCurrent = currentUri == removed.uri
        val wasPlaying = controller?.isPlaying == true
        val fallback = when {
            index + 1 < queueSongs.size -> queueSongs[index + 1]
            index - 1 >= 0 -> queueSongs[index - 1]
            else -> null
        }

        // If the active item is removed, explicitly choose a valid replacement
        // before rebuilding Media3. This prevents the controller from retaining
        // a URI that no longer exists in the logical queue.
        queueSongs.removeAt(index)
        if (removingCurrent && queueSongs.isNotEmpty()) {
            val fallbackSong = fallback ?: queueSongs.last()
            val fallbackIndex = queueSongs.indexOfFirst { it.uri == fallbackSong.uri }
            if (fallbackIndex >= 0) {
                controller?.let { c ->
                    c.setMediaItems(queueSongs.map { mediaItemFor(it) }, fallbackIndex, 0L)
                    c.shuffleModeEnabled = shuffleEnabled
                    c.repeatMode = repeatMode
                    c.setPlaybackSpeed(selectedPlaybackSpeed)
                    c.prepare()
                    if (wasPlaying) c.play()
                    currentIndex = songs.indexOfFirst { it.uri == fallbackSong.uri }
                    lastSongUri = fallbackSong.uri.toString()
                    position = 0L
                    savedPosition = 0L
                    saveQueueOrder()
                    savePlaybackState()
                    return
                }
            }
        }

        syncControllerQueue()
        savePlaybackState()
    }

    private fun clearQueue() {
        if (queueSongs.isEmpty()) return

        // Clear the canonical queue and Media3 together. This also removes
        // persisted queue metadata so a later app/service restart cannot
        // resurrect tracks that the user explicitly removed.
        queueSongs.clear()
        syncControllerQueue()
        errorMessage = "Đã xóa toàn bộ hàng đợi."
    }

    private fun playQueueFromStart() {
        val c = controller ?: return
        if (queueSongs.isEmpty()) {
            errorMessage = "Hàng đợi đang trống."
            return
        }

        // Keep the visible queue as the canonical order and explicitly start
        // from its first item. Shuffle remains available as a playback mode.
        c.setMediaItems(queueSongs.map { mediaItemFor(it) }, 0, 0L)
        c.shuffleModeEnabled = shuffleEnabled
        c.repeatMode = repeatMode
        c.setPlaybackSpeed(selectedPlaybackSpeed)
        c.prepare()
        c.play()
        currentIndex = songs.indexOfFirst { it.uri == queueSongs.first().uri }
        lastSongUri = queueSongs.first().uri.toString()
        position = 0L
        savedPosition = 0L
        saveQueueOrder()
        savePlaybackState()
        errorMessage = "Đang phát từ đầu hàng đợi."
    }

    private fun moveQueueItem(from: Int, to: Int) {
        if (from !in queueSongs.indices || to !in queueSongs.indices || from == to) return

        // queueSongs is the user-visible order. Update it first and let the
        // canonical sync preserve the active URI, position, Shuffle and Repeat.
        queueSongs.add(to, queueSongs.removeAt(from))
        syncControllerQueue()
        savePlaybackState()
    }

    private fun stop() {
        clearActiveRadioState()
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

    private fun setPlaybackSpeed(speed: Float) {
        selectedPlaybackSpeed = speed.coerceIn(0.5f, 2.0f)
        controller?.setPlaybackSpeed(selectedPlaybackSpeed)
        savePlayerPreferences()
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

    private fun loadCustomTvSources() {
        val raw = prefs.getString("tv_custom_sources", "").orEmpty()
        if (raw.isBlank()) return
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val description = item.optString("description").trim()
                val url = item.optString("url").trim()
                if (name.isNotBlank() && url.startsWith("https://")) {
                    customTvSources.add(
                        TvSource(
                            name,
                            description.ifBlank { "Nguồn TV cá nhân" },
                            url
                        )
                    )
                }
            }
        }
    }

    private fun saveCustomTvSources() {
        val array = org.json.JSONArray()
        customTvSources.forEach { source ->
            array.put(
                org.json.JSONObject().apply {
                    put("name", source.name)
                    put("description", source.description)
                    put("url", source.url)
                }
            )
        }
        prefs.edit().putString("tv_custom_sources", array.toString()).apply()
    }

    private fun addCustomTvSource() {
        val name = tvSourceName.trim()
        val url = tvSourceUrl.trim()
        if (name.isBlank() || url.isBlank()) {
            errorMessage = "Hãy nhập tên và URL TV."
            return
        }
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
            errorMessage = "URL TV phải là HTTPS hợp lệ."
            return
        }
        if (customTvSources.none { it.url == url }) {
            customTvSources.add(TvSource(name, "Nguồn TV đã thêm", url))
            saveCustomTvSources()
        }
        tvSourceName = ""
        tvSourceUrl = ""
        showTvSourceDialog = false
        errorMessage = "Đã thêm nguồn TV: " + name
    }

    private fun openTvSource(source: TvSource) {
        runCatching {
            startActivity(
                Intent(this, TvPlayerActivity::class.java).apply {
                    putExtra(TvPlayerActivity.EXTRA_URL, source.url)
                    putExtra(TvPlayerActivity.EXTRA_TITLE, source.name)
                    putExtra(TvPlayerActivity.EXTRA_DESCRIPTION, source.description)
                }
            )
        }.onFailure {
            errorMessage = "Không mở được trình phát TV."
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
            }
        }
    }

    private fun showCurrentLocationOnMap() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabledProviders = locationManager.getProviders(true).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (enabledProviders.isEmpty()) {
            errorMessage = "Dịch vụ Vị trí đang tắt. Hãy bật Vị trí/GPS rồi thử lại."
            return
        }

        fun openLocationOnMap(location: android.location.Location) {
            val lat = location.latitude
            val lon = location.longitude
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                errorMessage = "Vị trí nhận được không hợp lệ. Hãy thử lại."
                return
            }

            val delta = 0.018
            val left = lon - delta
            val right = lon + delta
            val bottom = lat - delta
            val top = lat + delta

            radioWebTitle = "NGỌC SĨ MAP • VỊ TRÍ HIỆN TẠI"
            radioWebUrl =
                "https://www.openstreetmap.org/#map=15/" +
                    "$lat/$lon&layers=N"
            errorMessage = null
        }

        // Prefer a recent cached fix for instant response.
        val cached = enabledProviders
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (cached != null && System.currentTimeMillis() - cached.time <= 5 * 60 * 1000L) {
            openLocationOnMap(cached)
            return
        }

        val providersToRequest = buildList {
            if (hasFine && enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (enabledProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            enabledProviders.firstOrNull { it !in this }?.let(::add)
        }.distinct()

        if (providersToRequest.isEmpty()) {
            errorMessage = "Không có nguồn vị trí khả dụng. Hãy bật GPS/Vị trí rồi thử lại."
            return
        }

        var delivered = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (delivered) return
                delivered = true
                runCatching { locationManager.removeUpdates(this) }
                handler.removeCallbacksAndMessages(null)
                openLocationOnMap(location)
            }

            override fun onProviderDisabled(providerName: String) {
                // Another enabled provider may still produce a valid fix.
                if (providersToRequest.all { !runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }) {
                    delivered = true
                    runCatching { locationManager.removeUpdates(this) }
                    handler.removeCallbacksAndMessages(null)
                    errorMessage = "Dịch vụ Vị trí đang tắt. Hãy bật GPS/Vị trí rồi thử lại."
                }
            }
        }

        var requestedAny = false
        for (provider in providersToRequest) {
            val requested = runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    1f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
            }.isSuccess
            requestedAny = requestedAny || requested
        }

        if (!requestedAny) {
            delivered = true
            errorMessage = "Không thể yêu cầu vị trí hiện tại. Hãy kiểm tra quyền Vị trí."
            return
        }

        // Give GPS/network a short active window, then fall back to the newest cached fix.
        handler.postDelayed({
            if (delivered) return@postDelayed

            val fallback = enabledProviders
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }

            delivered = true
            runCatching { locationManager.removeUpdates(listener) }
            handler.removeCallbacksAndMessages(null)

            if (fallback != null) {
                openLocationOnMap(fallback)
            } else {
                errorMessage = "Chưa nhận được tín hiệu vị trí. Hãy bật GPS/Vị trí và thử lại."
            }
        }, 10_000L)
    }

    private fun loadSavedState() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()).orEmpty()
        savedFavorites.forEach { it.toLongOrNull()?.let { id -> favorites[id] = true } }
        shuffleEnabled = prefs.getBoolean("shuffle", false)
        repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        selectedPlaybackSpeed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
        sleepTimerEndAt = prefs.getLong("sleep_timer_end_at", 0L).coerceAtLeast(0L)
        loadYouTubeLibraryState()
        loadYouTubeWatchLater()
        onlineFavoriteSet.addAll(prefs.getStringSet("online_favorites", emptySet()) ?: emptySet())
        onlineFavorites.addAll(onlineFavoriteSet)
        lastSongUri = prefs.getString("last_song_uri", null)
        savedPosition = prefs.getLong("last_position", 0L)
    }

    private fun saveQueueOrder() {
        if (!::prefs.isInitialized) return
        val metadata = org.json.JSONArray()
        queueSongs.forEach { song ->
            metadata.put(
                org.json.JSONObject().apply {
                    put("uri", song.uri.toString())
                    put("title", song.title)
                    put("artist", song.artist)
                    put("albumId", song.albumId)
                    put("artworkUri", song.artworkUri?.toString() ?: "")
                }
            )
        }
        prefs.edit()
            .putString("queue_order", queueSongs.joinToString("\n") { it.uri.toString() })
            .putString("queue_metadata", metadata.toString())
            .apply()
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
        driveImportJob?.cancel()
        driveImportJob = null
        val activeUri = controller?.currentMediaItem?.localConfiguration?.uri
            ?: songs.getOrNull(currentIndex)?.uri

        prefs.edit().remove("drive_uris").apply()
        songs.removeAll { it.source == "Google Drive" }
        queueSongs.removeAll { it.source == "Google Drive" }

        // Re-resolve the active library index by URI after the list shrinks.
        // This prevents deleting a Drive item before the current song from
        // making currentIndex silently point to a different track.
        currentIndex = activeUri?.let { uri ->
            songs.indexOfFirst { it.uri == uri }
        } ?: -1
        if (currentIndex >= 0) lastSongUri = activeUri?.toString()

        syncControllerQueue()
        errorMessage = "Đã xóa các bài Google Drive khỏi thư viện ứng dụng."
    }

    private fun savePlayerPreferences() {
        prefs.edit()
            .putStringSet("favorites", favorites.filterValues { it }.keys.map(Long::toString).toSet())
            .putBoolean("shuffle", shuffleEnabled)
            .putInt("repeat", repeatMode)
            .putFloat("playback_speed", selectedPlaybackSpeed)
            .apply()
    }

    private fun toggleFavorite(song: Song) {
        favorites[song.id] = !(favorites[song.id] ?: false)
        savePlayerPreferences()
    }

    private fun refreshPlaylists() {
        playlists.clear()
        playlists.addAll(playlistStore.load())
    }

    private fun createPlaylist() {
        val created = playlistStore.create(newPlaylistName)
        if (created == null) {
            errorMessage = "Tên playlist không được để trống."
            return
        }

        val targetSong = playlistTargetSongUri?.let { target ->
            songs.firstOrNull { it.uri.toString() == target }
        }
        if (targetSong != null) {
            playlistStore.addSong(created.id, targetSong.uri.toString())
        }

        newPlaylistName = ""
        playlistTargetSongUri = null
        refreshPlaylists()
        showCreatePlaylist = false
        errorMessage = if (targetSong != null) {
            "Đã tạo playlist “" + created.name + "” và thêm “" + targetSong.title + "”."
        } else {
            "Đã tạo playlist: " + created.name
        }
    }

    private fun deletePlaylist(playlist: MusicPlaylist) {
        playlistStore.delete(playlist.id)
        refreshPlaylists()
        errorMessage = "Đã xóa playlist: " + playlist.name
    }

    private fun addSongToPlaylist(playlistId: String, song: Song) {
        val updated = playlistStore.addSong(playlistId, song.uri.toString())
        refreshPlaylists()
        playlistTargetSongUri = null
        errorMessage = if (updated != null) {
            "Đã thêm “" + song.title + "” vào " + updated.name + "."
        } else {
            "Không tìm thấy playlist."
        }
    }

    private fun playPlaylist(playlist: MusicPlaylist) {
        val orderedSongs = playlist.songUris.mapNotNull { uri ->
            songs.firstOrNull { it.uri.toString() == uri }
        }
        if (orderedSongs.isEmpty()) {
            errorMessage = "Playlist “" + playlist.name + "” chưa có bài khả dụng."
            return
        }

        val c = controller ?: run {
            errorMessage = "Trình phát đang khởi động, thử lại sau."
            return
        }

        cancelRadioRecovery()
        activeRadioTitle = null
        activeRadioStreams = emptyList()
        activeRadioStreamIndex = 0

        val keepShuffle = c.shuffleModeEnabled
        val keepRepeat = c.repeatMode
        queueSongs.clear()
        queueSongs.addAll(orderedSongs)

        c.setMediaItems(queueSongs.map { mediaItemFor(it) }, 0, 0L)
        c.shuffleModeEnabled = keepShuffle
        c.repeatMode = keepRepeat
        c.setPlaybackSpeed(selectedPlaybackSpeed)
        c.prepare()
        c.play()

        currentIndex = songs.indexOfFirst { it.uri == orderedSongs.first().uri }
        position = 0L
        savedPosition = 0L
        lastSongUri = orderedSongs.first().uri.toString()
        saveQueueOrder()
        savePlaybackState()
        showPlaylists = false
        errorMessage = "Đang phát playlist: " + playlist.name
    }

    override fun onResume() {
        super.onResume()

        // Refresh YouTube library state after returning from the in-app player.
        // The player writes favorites/watch-later directly to SharedPreferences,
        // so the PRO/YouTube surface must reload them without requiring an app restart.
        if (::prefs.isInitialized) {
            loadYouTubeLibraryState()
            loadYouTubeWatchLater()
        }

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
        cancelRadioRecovery()
        sleepTimerJob?.cancel()
        jamendoSearchJob?.cancel()
        audiusSearchJob?.cancel()
        youtubeSearchJob?.cancel()
        driveImportJob?.cancel()
        driveImportJob = null
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
                "Online" -> byText.filter { it.source in setOf("Online", "Jamendo", "Audius", "Radio Việt Nam") }
                else -> byText
            }
            when (libraryView) {
                "Nghệ sĩ" -> bySource.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                "Album" -> bySource.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.substringBefore(" - ") })
                "Thư mục" -> bySource.sortedWith(compareBy<Song> { it.folder.ifBlank { it.source }.lowercase() }.thenBy { it.title.lowercase() })
                else -> bySource.sortedBy { it.title.lowercase() }
            }
        }

        LaunchedEffect(isPlaying, currentIndex) {
            while (isPlaying) {
                controller?.let {
                    val liveDuration = it.duration
                    if (liveDuration > 0L) duration = liveDuration
                    position = it.currentPosition.coerceAtLeast(0L)
                    if (duration > 0L && position >= duration) position = duration
                }
                delay(500)
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF08090D),
                surface = Color(0xFF11131A),
                primary = Color(0xFFB18CFF),
                secondary = Color(0xFF7DD3FC)
            )
        ) {
            Surface(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                color = Color(0xFF08090D)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Header()
                    SearchBarModern()
                    Spacer(Modifier.height(8.dp))

                    when (selectedSection) {
                        "Trang chủ" -> {
                            LazyColumn(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item { HomeHero(currentSong) }
                                item { LibraryChips() }
                                item { HomeCollections() }
                                item { PlayerCard(currentSong) }
                                item {
                                    Text(
                                        "TRUY CẬP NHANH",
                                        color = Color(0xFF8F8F9D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.2.sp
                                    )
                                }
                                item { QuickActions() }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("THƯ VIỆN", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                        TextButton(onClick = { selectedSection = "Thư viện" }) { Text("XEM TẤT CẢ") }
                                    }
                                }
                                itemsIndexed(
                                    filteredSongs.take(8),
                                    key = { _, song -> "home:" + song.uri.toString() }
                                ) { _, song ->
                                    val realIndex = songs.indexOfFirst { it.uri == song.uri }
                                    SongRow(song, realIndex, realIndex == currentIndex)
                                }
                            }
                        }

                        "Thư viện" -> {
                            Column(Modifier.weight(1f).fillMaxWidth()) {
                                LibraryViewTabs()
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "THƯ VIỆN • ${filteredSongs.size} bài",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 17.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { playFilteredSongs(filteredSongs) },
                                            enabled = filteredSongs.isNotEmpty()
                                        ) { Text("PHÁT TẤT CẢ") }
                                        TextButton(onClick = {
                                            loadSongs()
                                            selectedLibrary = "Tất cả"
                                            libraryView = "Bài hát"
                                            errorMessage = null
                                        }) { Text("LÀM MỚI") }
                                    }
                                }
                                errorMessage?.let {
                                    Text(
                                        it,
                                        color = Color(0xFFFFB4AB),
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                LazyColumn(
                                    Modifier.weight(1f),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(
                                        filteredSongs,
                                        key = { _, song -> "library:" + song.uri.toString() }
                                    ) { _, song ->
                                        val realIndex = songs.indexOfFirst { it.uri == song.uri }
                                        SongRow(song, realIndex, realIndex == currentIndex)
                                    }
                                }
                            }
                        }

                        "TV" -> {
                            TvHub()
                        }

                        "Bản đồ" -> {
                            MapHub()
                        }

                        "Online" -> {
                            Column(
                                Modifier.weight(1f).fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                            ) {
                                OnlineSourcesCard()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Nguồn đã nhập: ${songs.count { it.source == "Google Drive" }} bài",
                                    color = Color(0xFF9B9BA8),
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.height(24.dp))
                            }
                        }

                        "Radio" -> {
                            Column(
                                Modifier.weight(1f).fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    color = Color(0xFF11131A),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252936))
                                ) {
                                    Column(Modifier.padding(18.dp)) {
                                        Text("RADIO VIỆT NAM", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (activeRadioTitle != null) "Đang phát: $activeRadioTitle" else "Chọn đài để bắt đầu phát",
                                            color = Color(0xFF9C9DA9),
                                            fontSize = 13.sp
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { showVietnamRadioHub = true },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Text("TẤT CẢ ĐÀI")
                                            }
                                            OutlinedButton(
                                                onClick = { selectedSection = "Online"; onlineHubTab = "YouTube" },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Text("YOUTUBE")
                                            }
                                        }
                                        if (activeRadioTitle != null) {
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = { controller?.pause() },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Text("TẠM DỪNG RADIO")
                                            }
                                        }
                                    }
                                }

                                Text(
                                    "ĐÀI NỔI BẬT",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                )

                                RadioCatalog.stations.forEach { station ->
                                    RadioStationCard(station)
                                }

                                errorMessage?.takeIf { activeRadioTitle != null }?.let {
                                    Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp)
                                }
                            }
                        }

                        "Cài đặt" -> {
                            Column(
                                Modifier.weight(1f).fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                SettingsPanel()
                            }
                        }
                    }

                    currentSong?.let { MiniPlayer(it) }
                    BottomNav()
                }
            }
        }

        currentSong?.let { if (showNowPlaying) NowPlayingDialog(it) }
        if (showQueue) QueueDialog()
        if (showPlaylists) PlaylistManagerDialog()
        playlistDetailId?.let { id ->
            playlists.firstOrNull { it.id == id }?.let { PlaylistDetailDialog(it) }
        }
        playlistTargetSongUri?.let { targetUri ->
            songs.firstOrNull { it.uri.toString() == targetUri }?.let { PlaylistPickerDialog(it) }
        }
        if (showCreatePlaylist) CreatePlaylistDialog()
        if (showVietnamRadioHub) VietnamRadioHubDialog()
        if (showTvSourceDialog) TvSourceDialog()
        radioWebUrl?.let { RadioWebViewDialog(it, radioWebTitle) }
    }

    @Composable
    private fun Header() {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF8C64E8), Color(0xFF4E3A8B))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "NS",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "NGỌC SĨ MUSIC",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.2.sp
                )
                Text(
                    "ÂM NHẠC • RADIO • ONLINE",
                    color = Color(0xFF9698A7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
            }

            IconButton(onClick = { showPlaylists = true }) {
                Text("♫", color = Color(0xFFCDBAFF), fontSize = 23.sp)
            }
            IconButton(onClick = { selectedSection = "Cài đặt" }) {
                Text("⚙", color = Color(0xFFA7A9B7), fontSize = 21.sp)
            }
        }
    }

    @Composable
    private fun SearchBarModern() {
        val runSearch = {
            val q = searchQuery.trim()
            if (q.isBlank()) {
                selectedSection = "Thư viện"
            } else {
                youtubeQuery = q
                jamendoQuery = q
                onlineSearchActive = true
                jamendoTracks.clear()
                audiusTracks.clear()
                youtubeTracks.clear()
                youtubeNextPageToken = null
                selectedSection = "Online"
                searchJamendo()
                searchAudius(q)
                searchYouTube()
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Tìm bài hát, nghệ sĩ, album...", color = Color(0xFF777D8D)) },
            leadingIcon = { Text("⌕", color = Color(0xFFB18CFF), fontSize = 25.sp) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !isVoiceSearching, onClick = ::startVoiceSearch) {
                        Text(
                            if (isVoiceSearching) "…" else "🎙",
                            color = if (isVoiceSearching) Color(0xFF7DD3FC) else Color(0xFFB18CFF),
                            fontSize = 18.sp
                        )
                    }
                    IconButton(onClick = runSearch) {
                        Text("›", color = Color(0xFFB7B4C6), fontSize = 27.sp)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
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
    private fun HomeHero(song: Song?) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2A2044), Color(0xFF121722))
                        ),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        "NGHE NHẠC THEO CÁCH CỦA BẠN",
                        color = Color(0xFFBFA9FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.8.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        song?.title ?: "Sẵn sàng phát nhạc",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song?.artist ?: "Thiết bị • Google Drive • YouTube • Radio",
                        color = Color(0xFFA8AAB8),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeroAction(
                            icon = "▶",
                            label = "Đang phát",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (song != null) showNowPlaying = true
                                else if (songs.isNotEmpty()) selectedSection = "Thư viện"
                            }
                        )
                        HeroAction(
                            icon = "🎧",
                            label = "YouTube",
                            modifier = Modifier.weight(1f),
                            onClick = { selectedSection = "Online"; onlineHubTab = "YouTube" }
                        )
                        HeroAction(
                            icon = "📻",
                            label = "Radio",
                            modifier = Modifier.weight(1f),
                            onClick = { selectedSection = "Radio"; showVietnamRadioHub = true }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HeroAction(
        icon: String,
        label: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = modifier.clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = Color(0x3320202B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x332F3150))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 9.dp, horizontal = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(icon, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = Color(0xFFD6D5DE),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun HomeCollections() {
        val collections = listOf(
            Triple("♫", "Bài hát", "${songs.size}"),
            Triple("♥", "Yêu thích", favorites.count { it.value }.toString()),
            Triple("▣", "Playlist", playlists.size.toString()),
            Triple("☷", "Hàng đợi", queueSongs.size.toString())
        )
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                collections.take(2).forEach { (icon, title, count) ->
                    CollectionCard(icon, title, count, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                collections.drop(2).forEach { (icon, title, count) ->
                    CollectionCard(icon, title, count, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun CollectionCard(
        icon: String,
        title: String,
        count: String,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.clickable {
                when (title) {
                    "Bài hát" -> {
                        selectedSection = "Thư viện"
                        selectedLibrary = "Tất cả"
                        libraryView = "Bài hát"
                    }
                    "Yêu thích" -> {
                        selectedSection = "Thư viện"
                        selectedLibrary = "Yêu thích"
                        libraryView = "Bài hát"
                    }
                    "Playlist" -> showPlaylists = true
                    "Hàng đợi" -> showQueue = true
                }
            },
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF15161E),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252936))
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(icon, color = Color(0xFFC8B7FF), fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("$count mục", color = Color(0xFF888894), fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun LibraryChips() {
        val tabs = listOf("Tất cả", "Yêu thích", "Thiết bị", "Google Drive", "Online")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEach { tab ->
                FilterChip(
                    selected = selectedLibrary == tab,
                    onClick = { selectedLibrary = tab },
                    label = { Text(tab) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }

    @Composable
    private fun LibraryViewTabs() {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Bài hát", "Nghệ sĩ", "Album", "Thư mục").forEach { tab ->
                FilterChip(
                    selected = libraryView == tab,
                    onClick = { libraryView = tab },
                    label = { Text(tab) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }

    @Composable
    private fun QuickActions() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { selectedSection = "Thư viện" },
                modifier = Modifier.weight(1f).height(68.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♫", fontSize = 20.sp)
                    Text("Thư viện", fontWeight = FontWeight.Bold)
                }
            }
            FilledTonalButton(
                onClick = { selectedSection = "Online" },
                modifier = Modifier.weight(1f).height(68.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("☁", fontSize = 20.sp)
                    Text("Online", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { selectedSection = "Radio"; showVietnamRadioHub = true },
                modifier = Modifier.weight(1f).height(62.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📻", fontSize = 18.sp)
                    Text("Radio", fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = { showQueue = true },
                modifier = Modifier.weight(1f).height(62.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("☷", fontSize = 18.sp)
                    Text("Hàng đợi", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { selectedSection = "TV" },
                modifier = Modifier.weight(1f).height(62.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📺", fontSize = 18.sp)
                    Text("TV", fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = { selectedSection = "Bản đồ" },
                modifier = Modifier.weight(1f).height(62.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🗺️", fontSize = 18.sp)
                    Text("Bản đồ", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun BottomNav() {
        NavigationBar(
            containerColor = Color(0xFF0D0F14),
            tonalElevation = 8.dp
        ) {
            listOf(
                "Trang chủ" to "⌂",
                "Thư viện" to "♫",
                "Online" to "▶",
                "Radio" to "📻",
                "Cài đặt" to "⚙"
            ).forEach { (name, icon) ->
                val selected = selectedSection == name
                NavigationBarItem(
                    selected = selected,
                    onClick = { selectedSection = name },
                    icon = {
                        Box(
                            Modifier
                                .size(if (selected) 40.dp else 32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Color(0xFF33264F)
                                    else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                icon,
                                color = if (selected) Color.White else Color(0xFF9295A5),
                                fontSize = 17.sp
                            )
                        }
                    },
                    label = {
                        Text(
                            when (name) {
                                "Trang chủ" -> "Trang chủ"
                                "Thư viện" -> "Nhạc"
                                else -> name
                            },
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
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
            SettingsRow("⏩", "Tốc độ phát", "${selectedPlaybackSpeed}x") { showPlaybackSpeed = true }
            SettingsRow("☁", "Google Drive", songs.count { it.source == "Google Drive" }.toString() + " bài đã nhập") { selectedSection = "Online" }
            SettingsRow("📺", "TV", (TvCatalog.builtIn.size + customTvSources.size).toString() + " nguồn TV") { selectedSection = "TV" }
            SettingsRow("🗺️", "Bản đồ", "Bản đồ + giao thông thời gian thực") { selectedSection = "Bản đồ" }
            SettingsRow("♫", "Playlist", playlists.size.toString() + " danh sách đã tạo") { showPlaylists = true }
            OutlinedButton(onClick = ::clearDriveLibrary, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("XÓA NHẠC GOOGLE DRIVE KHỎI ỨNG DỤNG")
            }
        }
        if (showPlaybackSpeed) {
            AlertDialog(
                onDismissRequest = { showPlaybackSpeed = false },
                title = { Text("Tốc độ phát") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                            OutlinedButton(
                                onClick = {
                                    setPlaybackSpeed(speed)
                                    showPlaybackSpeed = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (speed == selectedPlaybackSpeed) "✓ ${speed}x" else "${speed}x")
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
        if (showSleepTimer) {
            AlertDialog(
                onDismissRequest = { showSleepTimer = false },
                title = { Text("Hẹn giờ tắt nhạc") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60, 90, 120).forEach { min ->
                            OutlinedButton(onClick = {
                                startSleepTimer(min)
                                showSleepTimer = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("${min} phút") }
                        }
                        TextButton(onClick = { startSleepTimer(0); showSleepTimer = false }) { Text("Tắt hẹn giờ") }
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
                    SongArtwork(song, Modifier.size(250.dp))
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
                    // Media3 may discover the real duration only after prepare.
                    // Prefer the live controller duration, with the library value
                    // as a fallback for local files that already have metadata.
                    val totalDuration = max(duration, song.duration).coerceAtLeast(1L)
                    var isSeeking by remember(song.uri.toString()) { mutableStateOf(false) }
                    var sliderPosition by remember(song.uri.toString(), totalDuration) {
                        mutableFloatStateOf(position.coerceIn(0L, totalDuration).toFloat())
                    }
                    LaunchedEffect(position, isSeeking, song.uri.toString(), totalDuration) {
                        if (!isSeeking) {
                            sliderPosition = position.coerceIn(0L, totalDuration).toFloat()
                        }
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            isSeeking = true
                            sliderPosition = it.coerceIn(0f, totalDuration.toFloat())
                        },
                        onValueChangeFinished = {
                            seekTo(sliderPosition.toLong())
                            isSeeking = false
                        },
                        valueRange = 0f..totalDuration.toFloat()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(position), color = Color(0xFF888894), fontSize = 12.sp)
                        Text(
                            formatTime(totalDuration),
                            color = Color(0xFF888894),
                            fontSize = 12.sp
                        )
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
                        SmallControl("■", ::stop)
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
    private fun TvHub() {
        val sources = TvCatalog.builtIn + customTvSources

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF11131A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252936))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("TV TRỰC TUYẾN", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Xem các nguồn TV chính thức trong NGỌC SĨ MUSIC hoặc thêm nguồn HTTPS của riêng mình.",
                        color = Color(0xFF9698A7),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showTvSourceDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("+ THÊM NGUỒN TV")
                    }
                }
            }

            sources.forEach { source ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF15161E),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252936))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF7653B8), Color(0xFF293047)))),
                            contentAlignment = Alignment.Center
                        ) { Text("📺", fontSize = 23.sp) }

                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(source.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                source.description,
                                color = Color(0xFF8F909E),
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                openTvSource(source)
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(46.dp)
                        ) { Text("▶", fontSize = 17.sp) }
                        if (customTvSources.any { it.url == source.url }) {
                            TextButton(
                                onClick = {
                                    customTvSources.removeAll { it.url == source.url }
                                    saveCustomTvSources()
                                    errorMessage = "Đã xóa nguồn TV: " + source.name
                                }
                            ) { Text("×", color = Color(0xFFFF8A9A)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TvSourceDialog() {
        AlertDialog(
            onDismissRequest = { showTvSourceDialog = false },
            title = { Text("Thêm nguồn TV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tvSourceName,
                        onValueChange = { tvSourceName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tên nguồn") },
                        placeholder = { Text("Ví dụ: TV của tôi") }
                    )
                    OutlinedTextField(
                        value = tvSourceUrl,
                        onValueChange = { tvSourceUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("URL HTTPS") },
                        placeholder = { Text("https://...") }
                    )
                    Text(
                        "Nguồn web hỗ trợ HTTPS. URL trực tiếp .m3u8/.mp4/.webm sẽ dùng trình phát video Media3 của ứng dụng.",
                        color = Color(0xFF8F909E),
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::addCustomTvSource) { Text("THÊM") }
            },
            dismissButton = {
                TextButton(onClick = { showTvSourceDialog = false }) { Text("HỦY") }
            }
        )
    }

    private fun searchMapPlace() {
        val query = mapSearchQuery.trim()
        if (query.isBlank()) {
            errorMessage = "Nhập địa điểm cần tìm."
            return
        }
        if (mapSearching) return
        mapSearching = true
        errorMessage = null

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val connection = (URL(
                        "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=vi&q=$encoded"
                    ).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.3 (Android)")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw IllegalStateException("HTTP ${connection.responseCode}")
                        }
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val first = JSONArray(body).optJSONObject(0)
                            ?: throw NoSuchElementException("Không tìm thấy")
                        val lat = first.optDouble("lat", Double.NaN)
                        val lon = first.optDouble("lon", Double.NaN)
                        val displayName = first.optString("display_name", query)
                        if (!lat.isFinite() || !lon.isFinite()) {
                            throw IllegalStateException("Tọa độ không hợp lệ")
                        }
                        Triple(lat, lon, displayName)
                    } finally {
                        connection.disconnect()
                    }
                }
            }

            mapSearching = false
            result.onSuccess { (lat, lon, displayName) ->
                    radioWebTitle = "NGỌC SĨ MAP • ${displayName.substringBefore(",")}"
                radioWebUrl =
                    "https://www.openstreetmap.org/#map=15/" +
                        "$lat/$lon&layers=N"
            }.onFailure {
                errorMessage = "Không tìm thấy địa điểm hoặc máy chủ bản đồ đang bận. Hãy thử tên địa điểm cụ thể hơn."
            }
        }
    }

    @Composable
    private fun MapHub() {
        val mapUrl =
            "https://www.openstreetmap.org/#map=13/10.8231/106.6297&layers=N"
        val trafficUrl =
            "https://www.google.com/maps/@?api=1&map_action=map&center=10.8231%2C106.6297&zoom=12&basemap=roadmap&layer=traffic"
        val satelliteUrl =
            "https://www.google.com/maps/@?api=1&map_action=map&center=10.8231%2C106.6297&zoom=12&basemap=satellite&layer=traffic"

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF11131A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF252936))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "NGỌC SĨ MAP",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Bản đồ OpenStreetMap tương tác, tìm kiếm và vị trí hiện tại chạy trong ứng dụng; Vệ tinh, Giao thông và Chỉ đường mở Google Maps chính thức.",
                        color = Color(0xFF9698A7),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                radioWebTitle = "NGỌC SĨ MAP • BẢN ĐỒ"
                                radioWebUrl = mapUrl
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("🗺 BẢN ĐỒ")
                        }
                        Button(
                            onClick = {
                                runCatching {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trafficUrl)))
                                }.onFailure {
                                    errorMessage = "Không mở được Google Maps giao thông."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("🚦 GIAO THÔNG")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(satelliteUrl)))
                                }.onFailure {
                                    errorMessage = "Không mở được Google Maps vệ tinh."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("🛰 VỆ TINH")
                        }
                        OutlinedButton(
                            onClick = {
                                val q = mapSearchQuery.trim()
                                if (q.isBlank()) {
                                    errorMessage = "Nhập điểm đến trước khi chọn chỉ đường."
                                } else {
                                    val directionsUrl =
                                        "https://www.google.com/maps/dir/?api=1&destination=" +
                                            Uri.encode(q) + "&travelmode=driving"
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl)))
                                    }.onFailure {
                                        errorMessage = "Không mở được Google Maps chỉ đường."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("🧭 CHỈ ĐƯỜNG")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            showCurrentLocationOnMap()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("📍 VỊ TRÍ HIỆN TẠI")
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.google.com/maps/")
                                    )
                                )
                            }.onFailure {
                                errorMessage = "Không mở được Google Maps."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("MỞ GOOGLE MAPS ĐẦY ĐỦ")
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = mapSearchQuery,
                        onValueChange = { mapSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tìm địa điểm") },
                        placeholder = { Text("Ví dụ: Chợ Bến Thành") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { searchMapPlace() }
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { searchMapPlace() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (mapSearching) "ĐANG TÌM…" else "TÌM ĐỊA ĐIỂM")
                    }
                }
            }

            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF15161E)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "MAP PRO",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bản đồ đường phố và tìm kiếm chạy ngay trong NGỌC SĨ MUSIC; Vệ tinh, Giao thông và Chỉ đường mở Google Maps khi cần.",
                        color = Color(0xFF8F909E),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Bản đồ đường phố\n• Giao thông\n• Vệ tinh\n• Tìm địa điểm",
                        color = Color(0xFFB0B2BF),
                        fontSize = 11.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun RadioStationCard(station: RadioStation) {
        val playing = activeRadioTitle == station.title && isPlaying
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = if (playing) Color(0xFF241B38) else Color(0xFF15161E),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (playing) Color(0xFF6E53A8) else Color(0xFF252936)
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF7653B8), Color(0xFF2C243E))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📻", fontSize = 22.sp)
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        station.title,
                        color = Color.White,
                        fontWeight = if (playing) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        station.description,
                        color = Color(0xFF8F909E),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (playing) {
                        Text(
                            "● ĐANG PHÁT",
                            color = Color(0xFFBFA9FF),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = {
                        if (playing) controller?.pause()
                        else playVerifiedRadio(station.title, station.streamUrls)
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Text(if (playing) "⏸" else "▶", fontSize = 18.sp)
                }
            }
        }
    }

    @Composable
    private fun VietnamRadioHubDialog() {
        val sources = RadioCatalog.stations.map {
            Triple(it.title, it.description, it.sourceUrl)
        } + listOf(
            Triple("VOV • Radio Việt Nam", "Cổng các kênh phát thanh trực tuyến của VOV", "https://vovmedia.vn/"),
            Triple("VOH • Radio", "Radio và các kênh phát thanh của VOH", "https://voh.com.vn/radios"),
            Triple("HTV • Radio", "Các kênh radio được HTV giới thiệu", "https://htv.vn/radio.htm"),
            Triple("VOV3 • Podcast", "Podcast văn hóa, nghệ thuật và âm nhạc", "https://vov3.vov.vn/podcast"),
            Triple("VOV3 • Lịch phát", "Xem lịch chương trình VOV3 theo khung giờ", "https://vov3.vov.vn/lich-phat-song")
        )
val verifiedStreams = RadioCatalog.stations.associate { it.title to it.streamUrls }
        val normalizedFilter = radioFilter.trim().lowercase()
        val filteredSources = if (normalizedFilter.isBlank()) sources else sources.filter { source ->
            source.first.lowercase().contains(normalizedFilter) || source.second.lowercase().contains(normalizedFilter)
        }
        Dialog(onDismissRequest = {
            showVietnamRadioHub = false
            radioFilter = ""
        }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("RADIO VIỆT NAM", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "VOV1 • VOV2 • VOV3 phát trực tiếp bằng Media3/HLS; có tự động chuyển luồng dự phòng",
                        color = Color(0xFF8F8F9A),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = radioFilter,
                        onValueChange = { radioFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tìm đài") },
                        placeholder = { Text("Ví dụ: Hà Nội, VOV3, FM 96") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${filteredSources.size}/${sources.size} nguồn",
                        color = Color(0xFF777D8D),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredSources) { source ->
                            val streamUrls = verifiedStreams[source.first]
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF181922),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(source.first, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            if (!streamUrls.isNullOrEmpty()) "${source.second} • HLS + tự động dùng luồng dự phòng" else source.second,
                                            color = Color(0xFF8F8F9A),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (!streamUrls.isNullOrEmpty()) {
                                        Button(
                                            onClick = {
                                                showVietnamRadioHub = false
                                                radioFilter = ""
                                                playVerifiedRadio(source.first, streamUrls)
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) { Text("PHÁT APP") }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                radioWebTitle = source.first
                                                radioWebUrl = source.third
                                                showVietnamRadioHub = false
                                                radioFilter = ""
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) { Text("MỞ NGUỒN") }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Các luồng Media3 chỉ dùng URL HTTPS HLS đã xác minh; các đài chưa có luồng phù hợp vẫn mở nguồn chính thức.",
                        color = Color(0xFF777D8D),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        showVietnamRadioHub = false
                        radioFilter = ""
                    }) { Text("Đóng") }
                }
            }
        }
    }

    @Composable
    private fun RadioWebViewDialog(url: String, title: String) {
        Dialog(
            onDismissRequest = { radioWebUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.88f)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    title.startsWith("NGỌC SĨ MAP") || title.startsWith("BẢN ĐỒ") ->
                                        "Bản đồ OpenStreetMap trong NGỌC SĨ MUSIC"
                                    title.startsWith("TV") ->
                                        "Truyền hình trực tuyến trong NGỌC SĨ MUSIC"
                                    else ->
                                        "Phát trực tuyến trong NGỌC SĨ MUSIC • nguồn chính thức"
                                },
                                color = Color(0xFF8F8F9A),
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }.onFailure {
                                    errorMessage = "Không mở được nguồn bên ngoài."
                                }
                            }
                        ) {
                            Text("Mở ngoài")
                        }
                        TextButton(onClick = { radioWebUrl = null }) { Text("Đóng") }
                    }
                    var webViewRef by remember { mutableStateOf<WebView?>(null) }

                    androidx.activity.compose.BackHandler(
                        enabled = webViewRef?.canGoBack() == true
                    ) {
                        webViewRef?.goBack()
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                webViewRef = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadsImagesAutomatically = true
                                settings.databaseEnabled = true
                                settings.userAgentString =
                                    "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportZoom(false)
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                // Allow the provider's own video player to start after
                                // the user explicitly opens a TV source. Some live players
                                // otherwise remain permanently paused inside WebView.
                                settings.mediaPlaybackRequiresUserGesture = false
                                // Keep WebView video rendering on the hardware compositor.
                                // Some live-TV players can continue producing audio while
                                // a software-rendered WebView shows a black video surface.
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                settings.allowFileAccess = false
                                settings.allowContentAccess = true
                                settings.builtInZoomControls = false
                                settings.displayZoomControls = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                        return false
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: android.webkit.WebResourceRequest,
                                        error: android.webkit.WebResourceError
                                    ) {
                                        if (request.isForMainFrame) {
                                            errorMessage =
                                                "Không tải được nguồn TV/bản đồ. Có thể nguồn đang giới hạn WebView hoặc tạm ngừng."
                                        }
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    private var customView: android.view.View? = null
                                    private var customViewCallback: CustomViewCallback? = null

                                    override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                                        // Support provider video players that switch to the HTML5
                                        // fullscreen surface. Without this callback, audio may
                                        // continue while the video surface remains hidden.
                                        if (customView != null) {
                                            callback.onCustomViewHidden()
                                            return
                                        }
                                        customView = view
                                        customViewCallback = callback
                                        view.setBackgroundColor(android.graphics.Color.BLACK)
                                        val decor = window.decorView as android.view.ViewGroup
                                        decor.addView(
                                            view,
                                            android.view.ViewGroup.LayoutParams(
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                                    }

                                    override fun onHideCustomView() {
                                        val view = customView ?: return
                                        val decor = window.decorView as android.view.ViewGroup
                                        decor.removeView(view)
                                        customView = null
                                        customViewCallback?.onCustomViewHidden()
                                        customViewCallback = null
                                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                                    }

                                    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                                        // Do not grant camera/microphone access to arbitrary
                                        // TV/map pages. Media playback does not require it.
                                        request.deny()
                                    }
                                }

                                // Track the URL requested by this dialog separately
                                // from WebView.url. Internal navigation must not be
                                // reset on every Compose recomposition (for example,
                                // while playback position updates are flowing).
                                tag = url
                                if (title.startsWith("NGỌC SĨ MAP") || title.startsWith("BẢN ĐỒ")) {
                                    val parsedMapUrl = Uri.parse(url)
                                    val query = parsedMapUrl.getQueryParameter("query")
                                    val center = parsedMapUrl.getQueryParameter("center")?.split(",")
                                    val latitude = center?.getOrNull(0)?.toDoubleOrNull() ?: 10.8231
                                    val longitude = center?.getOrNull(1)?.toDoubleOrNull() ?: 106.6297
                                    val mapPageUrl = if (!query.isNullOrBlank()) {
                                        "https://www.openstreetmap.org/search?query=" + Uri.encode(query)
                                    } else {
                                        "https://www.openstreetmap.org/export/embed.html?bbox=" +
                                            (longitude - 0.18).toString() + "," +
                                            (latitude - 0.17).toString() + "," +
                                            (longitude + 0.18).toString() + "," +
                                            (latitude + 0.17).toString() +
                                            "&layer=mapnik&marker=" + latitude + "," + longitude
                                    }
                                    loadUrl(mapPageUrl)
                                } else {
                                    // Keep TV/provider pages on their own origin.
                                    loadUrl(url)
                                }
                            }
                        },
                        update = { view ->
                            val requestedUrl = view.tag as? String
                            if (requestedUrl != url) {
                                view.tag = url
                                if (title.startsWith("NGỌC SĨ MAP") || title.startsWith("BẢN ĐỒ")) {
                                    val parsedMapUrl = Uri.parse(url)
                                    val query = parsedMapUrl.getQueryParameter("query")
                                    val center = parsedMapUrl.getQueryParameter("center")?.split(",")
                                    val latitude = center?.getOrNull(0)?.toDoubleOrNull() ?: 10.8231
                                    val longitude = center?.getOrNull(1)?.toDoubleOrNull() ?: 106.6297
                                    val mapPageUrl = if (!query.isNullOrBlank()) {
                                        "https://www.openstreetmap.org/search?query=" + Uri.encode(query)
                                    } else {
                                        "https://www.openstreetmap.org/export/embed.html?bbox=" +
                                            (longitude - 0.18).toString() + "," +
                                            (latitude - 0.17).toString() + "," +
                                            (longitude + 0.18).toString() + "," +
                                            (latitude + 0.17).toString() +
                                            "&layer=mapnik&marker=" + latitude + "," + longitude
                                    }
                                    view.loadUrl(mapPageUrl)
                                } else {
                                    view.loadUrl(url)
                                }
                            }
                        }
                    )
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
                            Text(queueSongs.size.toString() + " bài • " + if (shuffleEnabled) "Ngẫu nhiên" else "Theo thứ tự hàng đợi", color = Color(0xFF888894), fontSize = 12.sp)
                        }
                        if (queueSongs.isNotEmpty()) {
                            TextButton(onClick = { playQueueFromStart() }) {
                                Text("Phát từ đầu", color = Color(0xFFC8B7FF))
                            }
                            TextButton(onClick = { clearQueue() }) {
                                Text("Xóa hết", color = Color(0xFFFF8A9A))
                            }
                        }
                        TextButton(onClick = { showQueue = false }) { Text("Đóng") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(queueSongs, key = { _, song -> song.uri.toString() }) { index, song ->
                            val activeUri = controller?.currentMediaItem?.localConfiguration?.uri
                            val isCurrent = activeUri == song.uri
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (isCurrent) Color(0xFF29213E) else Color(0xFF17181F))
                                    .clickable {
                                        val libraryIndex = songs.indexOfFirst { it.uri == song.uri }
                                        if (libraryIndex >= 0) play(libraryIndex)
                                        showQueue = false
                                    }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (isCurrent) "▶" else String.format("%02d", index + 1), color = Color(0xFFC8B7FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
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
                                    onClick = { if (index < queueSongs.lastIndex) moveQueueItem(index, index + 1) },
                                    enabled = index < queueSongs.lastIndex
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

    private fun searchYouTube(loadMore: Boolean = false) {
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

        if (loadMore && youtubeNextPageToken.isNullOrBlank()) return

        youtubeSearchJob?.cancel()
        youtubeLoading = true
        errorMessage = null
        youtubeSearchJob = lifecycleScope.launch(Dispatchers.IO) {
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
                    if (loadMore) {
                        append("&pageToken=")
                        append(java.net.URLEncoder.encode(youtubeNextPageToken.orEmpty(), "UTF-8"))
                    }
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
                    setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.1")
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
                        // Prefer YouTube's stable thumbnail host to avoid intermittent API-image
                        // rendering failures in WebView/Compose lists.
                        val thumbs = snippet.optJSONObject("thumbnails")
                        val apiThumb = thumbs?.optJSONObject("medium")?.optString("url").orEmpty()
                            .ifBlank { thumbs?.optJSONObject("high")?.optString("url").orEmpty() }
                            .ifBlank { thumbs?.optJSONObject("default")?.optString("url").orEmpty() }
                        val thumb = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
                            .ifBlank { apiThumb }
                        found += YouTubeTrack(id, title, channel, thumb)
                    }
                }
                val nextPageToken = json.optString("nextPageToken").ifBlank { null }

                withContext(Dispatchers.Main) {
                    if (youtubeQuery.trim() == q) {
                        if (loadMore) {
                            val existingIds = youtubeTracks.mapTo(mutableSetOf()) { it.videoId }
                            youtubeTracks.addAll(found.filter { existingIds.add(it.videoId) })
                        } else {
                            youtubeTracks.clear()
                            youtubeTracks.addAll(found)
                        }
                        youtubeNextPageToken = nextPageToken
                        youtubeLoading = false
                        if (youtubeTracks.isEmpty()) {
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
        rememberYouTubeHistory(track.title)

        // Always include the selected video in the player queue. This matters
        // when opening items from Yêu thích/Xem sau after search results have cleared.
        val queueTracks = youtubeTracks.toMutableList().apply {
            if (none { it.videoId == track.videoId }) {
                add(0, track)
            }
        }
        val queueJson = org.json.JSONArray().apply {
            queueTracks.forEach { item ->
                put(org.json.JSONObject().apply {
                    put("videoId", item.videoId)
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                })
            }
        }.toString()
        val selectedIndex = queueTracks.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        saveYouTubeLastPlayed(track, queueJson, selectedIndex)

        // YouTube MORPHE-style chạy hoàn toàn trong NGỌC SĨ MUSIC.
        // Truyền cả danh sách kết quả để player hỗ trợ hàng đợi/next/previous.
        runCatching {
            startActivity(
                Intent(this, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, track.videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_TITLE, track.title)
                    putExtra(YouTubePlayerActivity.EXTRA_CHANNEL, track.channelTitle)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_JSON, queueJson)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_INDEX, selectedIndex)
                }
            )
        }.onFailure {
            errorMessage = "Không mở được trình phát YouTube trong NGỌC SĨ MUSIC."
        }
    }
    private fun toggleYouTubeWatchLater(track: YouTubeTrack) {
        if (youtubeWatchLater.any { it.videoId == track.videoId }) {
            youtubeWatchLater.removeAll { it.videoId == track.videoId }
            saveYouTubeWatchLater()
            errorMessage = "Đã bỏ khỏi Xem sau: " + track.title
            return
        }

        youtubeWatchLater.add(
            YouTubeWatchLaterMeta(
                track.videoId,
                track.title,
                track.channelTitle,
                track.thumbnailUrl.ifBlank {
                    "https://i.ytimg.com/vi/" + track.videoId + "/hqdefault.jpg"
                }
            )
        )
        saveYouTubeWatchLater()
        errorMessage = "Đã thêm vào Xem sau: " + track.title
    }

    private fun addYouTubeWatchLater(track: YouTubeTrack) {
        toggleYouTubeWatchLater(track)
    }

    private fun removeYouTubeWatchLater(videoId: String) {
        youtubeWatchLater.removeAll { it.videoId == videoId }
        saveYouTubeWatchLater()
    }

    private fun loadYouTubeWatchLater() {
        youtubeWatchLater.clear()
        val raw = prefs.getString("youtube_watch_later", null) ?: return
        runCatching {
            val root = org.json.JSONArray(raw)
            for (i in 0 until root.length()) {
                val obj = root.optJSONObject(i) ?: continue
                val id = obj.optString("videoId").trim()
                if (id.isBlank()) continue
                youtubeWatchLater.add(
                    YouTubeWatchLaterMeta(
                        id,
                        obj.optString("title").ifBlank { "Video YouTube" },
                        obj.optString("channelTitle").ifBlank { "YouTube" },
                        obj.optString("thumbnailUrl").ifBlank {
                            "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
                        }
                    )
                )
            }
        }
    }

    private fun saveYouTubeWatchLater() {
        val root = org.json.JSONArray()
        youtubeWatchLater.forEach { item ->
            root.put(
                org.json.JSONObject().apply {
                    put("videoId", item.videoId)
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                }
            )
        }
        prefs.edit().putString("youtube_watch_later", root.toString()).apply()
    }

    private fun youtubeWatchLaterAsTrack(item: YouTubeWatchLaterMeta): YouTubeTrack =
        YouTubeTrack(item.videoId, item.title, item.channelTitle, item.thumbnailUrl)

    private fun toggleYouTubeFavorite(track: YouTubeTrack) {
        if (youtubeFavoriteSet.contains(track.videoId)) {
            youtubeFavoriteSet.remove(track.videoId)
            youtubeFavoriteTracks.removeAll { it.videoId == track.videoId }
        } else {
            youtubeFavoriteSet[track.videoId] = true
            youtubeFavoriteTracks.removeAll { it.videoId == track.videoId }
            youtubeFavoriteTracks.add(
                YouTubeFavoriteMeta(
                    track.videoId,
                    track.title,
                    track.channelTitle,
                    track.thumbnailUrl.ifBlank {
                        "https://i.ytimg.com/vi/" + track.videoId + "/hqdefault.jpg"
                    }
                )
            )
        }
        youtubeFavoriteTracks.sortBy { it.title.lowercase() }
        saveYouTubeLibraryState()
    }

    private fun rememberYouTubeHistory(title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        val updated = buildList {
            add(clean)
            youtubeHistory.filterNot { it.equals(clean, ignoreCase = true) }.forEach { add(it) }
        }.take(8)
        youtubeHistory.clear()
        youtubeHistory.addAll(updated)
        val array = org.json.JSONArray().apply { updated.forEach { put(it) } }
        prefs.edit()
            .putString("youtube_history_json", array.toString())
            .putStringSet("youtube_history", updated.toSet())
            .apply()
    }

    private fun loadYouTubeLibraryState() {
        youtubeLastPlayed = prefs.getString("youtube_last_played_video_id", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                YouTubeTrack(
                    id,
                    prefs.getString("youtube_last_played_title", "Video YouTube").orEmpty().ifBlank { "Video YouTube" },
                    prefs.getString("youtube_last_played_channel", "YouTube").orEmpty().ifBlank { "YouTube" },
                    prefs.getString("youtube_last_played_thumbnail", "").orEmpty().ifBlank {
                        "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
                    }
                )
            }
        youtubeLastQueueJson = prefs.getString("youtube_last_played_queue_json", null)
        youtubeLastQueueIndex = prefs.getInt("youtube_last_played_queue_index", 0).coerceAtLeast(0)

        youtubeHistory.clear()
        val historyJson = prefs.getString("youtube_history_json", null)
        if (!historyJson.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(historyJson)
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let {
                        youtubeHistory.add(it)
                    }
                }
            }
        }
        if (youtubeHistory.isEmpty()) {
            youtubeHistory.addAll(
                (prefs.getStringSet("youtube_history", emptySet()) ?: emptySet()).toList().take(8)
            )
        }

        youtubeFavoriteSet.clear()
        youtubeFavoriteTracks.clear()
        val raw = prefs.getString("youtube_favorite_meta", null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val root = org.json.JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val obj = root.optJSONObject(id) ?: continue
                    youtubeFavoriteTracks.add(
                        YouTubeFavoriteMeta(
                            id,
                            obj.optString("title").ifBlank { "Video YouTube" },
                            obj.optString("channelTitle").ifBlank { "YouTube" },
                            obj.optString("thumbnailUrl").ifBlank {
                                "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
                            }
                        )
                    )
                    youtubeFavoriteSet[id] = true
                }
            }
        }
        if (youtubeFavoriteSet.isEmpty()) {
            (prefs.getStringSet("youtube_favorites", emptySet()) ?: emptySet())
                .forEach { youtubeFavoriteSet[it] = true }
        }
        youtubeFavoriteTracks.sortBy { it.title.lowercase() }
    }

    private fun saveYouTubeLibraryState() {
        val root = org.json.JSONObject()
        youtubeFavoriteTracks.forEach { item ->
            root.put(
                item.videoId,
                org.json.JSONObject().apply {
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                }
            )
        }
        prefs.edit()
            .putStringSet("youtube_favorites", youtubeFavoriteSet.keys)
            .putString("youtube_favorite_meta", root.toString())
            .apply()
    }

    private fun saveYouTubeLastPlayed(track: YouTubeTrack, queueJson: String, queueIndex: Int) {
        val thumbnail = track.thumbnailUrl.ifBlank {
            "https://i.ytimg.com/vi/" + track.videoId + "/hqdefault.jpg"
        }
        prefs.edit()
            .putString("youtube_last_played_video_id", track.videoId)
            .putString("youtube_last_played_title", track.title)
            .putString("youtube_last_played_channel", track.channelTitle)
            .putString("youtube_last_played_thumbnail", thumbnail)
            .putString("youtube_last_played_queue_json", queueJson)
            .putInt("youtube_last_played_queue_index", queueIndex.coerceAtLeast(0))
            .apply()
        youtubeLastPlayed = track.copy(thumbnailUrl = thumbnail)
        youtubeLastQueueJson = queueJson
        youtubeLastQueueIndex = queueIndex.coerceAtLeast(0)
    }

    private fun playLastYouTube() {
        val track = youtubeLastPlayed ?: return
        val fallbackQueue = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("videoId", track.videoId)
                put("title", track.title)
                put("channelTitle", track.channelTitle)
                put("thumbnailUrl", track.thumbnailUrl)
            })
        }.toString()
        val queueJson = youtubeLastQueueJson?.takeIf { it.isNotBlank() } ?: fallbackQueue
        val selectedIndex = runCatching {
            val array = org.json.JSONArray(queueJson)
            youtubeLastQueueIndex.coerceIn(0, (array.length() - 1).coerceAtLeast(0))
        }.getOrDefault(0)

        runCatching {
            startActivity(
                Intent(this, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, track.videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_TITLE, track.title)
                    putExtra(YouTubePlayerActivity.EXTRA_CHANNEL, track.channelTitle)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_JSON, queueJson)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_INDEX, selectedIndex)
                }
            )
        }.onFailure {
            errorMessage = "Không mở lại được video YouTube gần nhất."
        }
    }

    private fun playStoredYouTubeOffset(offset: Int) {
        val rawQueue = youtubeLastQueueJson ?: return
        runCatching {
            val array = JSONArray(rawQueue)
            val targetIndex = (youtubeLastQueueIndex + offset).coerceIn(0, array.length() - 1)
            val item = array.getJSONObject(targetIndex)
            val targetTrack = YouTubeTrack(
                videoId = item.optString("videoId"),
                title = item.optString("title").ifBlank { "YouTube" },
                channelTitle = item.optString("channelTitle").ifBlank { "YouTube" },
                thumbnailUrl = item.optString("thumbnailUrl")
            )
            if (targetTrack.videoId.isBlank()) return@runCatching
            startActivity(
                Intent(this, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, targetTrack.videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_TITLE, targetTrack.title)
                    putExtra(YouTubePlayerActivity.EXTRA_CHANNEL, targetTrack.channelTitle)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_JSON, rawQueue)
                    putExtra(YouTubePlayerActivity.EXTRA_QUEUE_INDEX, targetIndex)
                }
            )
        }.onFailure {
            errorMessage = "Không chuyển được video trong hàng đợi YouTube."
        }
    }

    private fun storedYouTubeQueueSize(): Int =
        runCatching { JSONArray(youtubeLastQueueJson.orEmpty()).length() }.getOrDefault(0)

    private fun clearYouTubeHistory() {
        youtubeHistory.clear()
        prefs.edit()
            .remove("youtube_history_json")
            .remove("youtube_history")
            .apply()
    }

    private fun youtubeFavoriteAsTrack(item: YouTubeFavoriteMeta): YouTubeTrack =
        YouTubeTrack(item.videoId, item.title, item.channelTitle, item.thumbnailUrl)


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
        queueSongs.add(song)
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
            bitmap = if (url.isBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 10000
                            useCaches = true
                            doInput = true
                            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.2")
                        }
                        try {
                            if (connection.responseCode in 200..299) {
                                connection.inputStream.use { BitmapFactory.decodeStream(it) }
                            } else {
                                null
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrNull()
                }
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
            Text("Trung tâm nhạc online • Audius + Jamendo + YouTube + Radio Việt Nam", color = Color(0xFF8F8F9A), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🎧 NGUỒN NHẠC VIỆT NAM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showVietnamRadioHub = true }) { Text("MỞ HUB") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        playVerifiedRadio(
                            "VOV1 • Thời sự",
                            verifiedRadioStreams("VOV1 • Thời sự")
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("▶ VOV1") }
                Button(
                    onClick = {
                        playVerifiedRadio(
                            "VOV2 • Văn hóa",
                            verifiedRadioStreams("VOV2 • Văn hóa")
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("▶ VOV2") }
                Button(
                    onClick = {
                        playVerifiedRadio(
                            "VOV3 • Âm nhạc",
                            verifiedRadioStreams("VOV3 • Âm nhạc")
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("▶ VOV3") }
                OutlinedButton(
                    onClick = { openOnlineSource("https://vovmedia.vn/") },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("VOV • Radio Việt Nam") }
                OutlinedButton(
                    onClick = { openOnlineSource("https://voh.com.vn/radios") },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("VOH • Radio") }
                OutlinedButton(
                    onClick = { openOnlineSource("https://htv.vn/radio.htm") },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("HTV • Radio") }
                OutlinedButton(
                    onClick = { openOnlineSource("https://vov3.vov.vn/podcast") },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("VOV3 • Podcast") }
                OutlinedButton(
                    onClick = { openOnlineSource("https://vov3.vov.vn/lich-phat-song") },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("VOV3 • Lịch phát") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "VOV1 có luồng HLS chính thức được đưa vào Media3; các đài khác vẫn mở nguồn chính thức cho đến khi có luồng trực tiếp được xác minh.",
                color = Color(0xFF777D8D),
                fontSize = 11.sp
            )
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("YOUTUBE MUSIC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Tìm kiếm • phát • hàng đợi",
                        color = Color(0xFF8F8F9A),
                        fontSize = 11.sp
                    )
                }
                Text(
                    "● TRÌNH PHÁT NHÚNG",
                    color = Color(0xFF8FD694),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Phát bằng trình phát YouTube nhúng; không tải hoặc tách luồng âm thanh.",
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
                        youtubeNextPageToken = null
                        errorMessage = null
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("XÓA") }
            }

            youtubeLastPlayed?.let { item ->
                val queueSize = storedYouTubeQueueSize().coerceAtLeast(1)
                val queuePosition = (youtubeLastQueueIndex + 1).coerceIn(1, queueSize)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF111111))
                        .clickable { playLastYouTube() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OnlineArtwork(item.thumbnailUrl, Modifier.size(48.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ĐANG PHÁT / TIẾP TỤC",
                            color = Color(0xFF8FD694),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            item.title,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.channelTitle,
                                color = Color(0xFF8F8F9A),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$queuePosition/$queueSize",
                                color = Color(0xFF8FD694),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = queuePosition > 1) {
                                playStoredYouTubeOffset(-1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "‹",
                            color = if (queuePosition > 1) Color.White else Color(0xFF4B4B52),
                            fontSize = 24.sp
                        )
                    }
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8FD694))
                            .clickable { playLastYouTube() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = queuePosition < queueSize) {
                                playStoredYouTubeOffset(1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "›",
                            color = if (queuePosition < queueSize) Color.White else Color(0xFF4B4B52),
                            fontSize = 24.sp
                        )
                    }
                }
            }

            if (youtubeWatchLater.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("YOUTUBE • XEM SAU (" + youtubeWatchLater.size + ")", color = Color(0xFFB18CFF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                youtubeWatchLater.take(8).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B1B23))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OnlineArtwork(item.thumbnailUrl, Modifier.size(58.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { playYouTube(youtubeWatchLaterAsTrack(item)) }) {
                            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.channelTitle, color = Color(0xFF8F8F9A), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { removeYouTubeWatchLater(item.videoId) }) {
                            Text("×", color = Color(0xFFFFB4AB), fontSize = 22.sp)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }

            if (youtubeFavoriteTracks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("YOUTUBE • YÊU THÍCH", color = Color(0xFFB18CFF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                youtubeFavoriteTracks.take(8).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B1B23))
                            .clickable { playYouTube(youtubeFavoriteAsTrack(item)) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OnlineArtwork(item.thumbnailUrl, Modifier.size(58.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.channelTitle, color = Color(0xFF8F8F9A), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = { toggleYouTubeFavorite(youtubeFavoriteAsTrack(item)) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(40.dp)
                        ) { Text("♥") }
                    }
                    Spacer(Modifier.height(5.dp))
                }
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
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = ::clearYouTubeHistory,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text("XÓA LỊCH SỬ", fontSize = 11.sp) }
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
                    val isCurrent = youtubeLastPlayed?.videoId == track.videoId
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCurrent) Color(0xFF1C2C20) else Color(0xFF1B1B23))
                            .clickable { playYouTube(track) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            OnlineArtwork(track.thumbnailUrl, Modifier.fillMaxSize())
                            if (isCurrent) {
                                Text(
                                    "ĐANG PHÁT",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xCC101014))
                                        .padding(horizontal = 5.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            if (isCurrent) {
                                Text(
                                    "▶ ĐANG PHÁT",
                                    color = Color(0xFF8FD694),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                track.title,
                                color = Color.White,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = { playYouTube(track) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(38.dp)
                            ) { Text("▶") }
                            FilledTonalButton(
                                onClick = { toggleYouTubeFavorite(track) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Text(if (youtubeFavoriteSet.contains(track.videoId)) "♥" else "♡")
                            }
                            FilledTonalButton(
                                onClick = { toggleYouTubeWatchLater(track) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Text(
                                    if (youtubeWatchLater.any { it.videoId == track.videoId }) "✓" else "🔖"
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (youtubeNextPageToken != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { searchYouTube(loadMore = true) },
                        enabled = !youtubeLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (youtubeLoading) "ĐANG TẢI..." else "XEM THÊM 50 VIDEO")
                    }
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
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1D1930), Color(0xFF11131A))))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GOOGLE DRIVE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("PRO", color = Color(0xFFC8B7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nhạc từ Drive của thiết bị • Drive chia sẻ • link thư mục",
                    color = Color(0xFF8F8F9A),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::openDrivePicker,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("☁ FILE") }
                    OutlinedButton(
                        onClick = ::openDriveFolderPicker,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("▣ THƯ MỤC") }
                }
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = ::openGoogleDrive,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("MỞ DRIVE") }
                    OutlinedButton(
                        onClick = {
                            loadDriveRecentLinks()
                            loadSongs()
                            errorMessage = "Đã làm mới thư viện Google Drive."
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("↻ LÀM MỚI") }
                }
                Spacer(Modifier.height(10.dp))
                Text("DRIVE CHIA SẺ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = driveSharedLink,
                    onValueChange = { driveSharedLink = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Dán link tệp hoặc thư mục người khác chia sẻ") },
                    label = { Text("Link Google Drive") },
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = ::importSharedDriveLink,
                    enabled = !driveSharedLoading && driveSharedLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (driveSharedLoading) "ĐANG KẾT NỐI DRIVE…" else "LIÊN KẾT NGUỒN CHIA SẺ")
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    driveSharedStatus,
                    color = if (driveSharedLoading) Color(0xFFC8B7FF) else Color(0xFF8F8F9A),
                    fontSize = 11.sp
                )
                if (driveRecentLinks.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text("LINK GẦN ĐÂY", color = Color(0xFFB8B3C7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    driveRecentLinks.take(3).forEach { link ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                link,
                                color = Color(0xFF9F9FAA),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).clickable { driveSharedLink = link }
                            )
                            TextButton(onClick = {
                                driveSharedLink = link
                                importSharedDriveLink()
                            }) { Text("MỞ") }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = ::clearSharedDriveLibrary,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("XÓA NHẠC DRIVE CHIA SẺ") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = onlineUrl, onValueChange = { onlineUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Dán URL luồng âm thanh HTTPS") }, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(6.dp))
            Button(onClick = ::playOnlineUrl, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("PHÁT LUỒNG ÂM THANH") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { onlineUrl = "https://stream.radioparadise.com/mp3-192"; playOnlineUrl() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("THỬ RADIO ONLINE") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = ::clearOnlineLibrary, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("XÓA URL ONLINE ĐÃ LƯU") }
        }
    }

    private suspend fun loadArtworkBitmap(song: Song?): androidx.compose.ui.graphics.ImageBitmap? {
        if (song == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val uri = song.artworkUri
                    ?: if (song.albumId >= 0L) {
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                            song.albumId
                        )
                    } else null
                uri?.let { contentResolver.openInputStream(it)?.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() } }
            } catch (_: Exception) {
                null
            }
        }
    }

    @Composable
    private fun SongArtwork(song: Song?, modifier: Modifier = Modifier) {
        var artwork by remember(song?.uri?.toString(), song?.albumId, song?.artworkUri?.toString()) {
            mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        }
        LaunchedEffect(song?.uri?.toString(), song?.albumId, song?.artworkUri?.toString()) {
            artwork = loadArtworkBitmap(song)
        }
        Box(
            modifier.clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6D4CC5), Color(0xFF24283A)))),
            contentAlignment = Alignment.Center
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork!!,
                    contentDescription = song?.title ?: "Ảnh bìa",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("♫", color = Color(0xFFC8B7FF), fontSize = 42.sp)
            }
        }
    }

    @Composable
    private fun MiniPlayer(song: Song) {
        val totalDuration = max(duration, song.duration).coerceAtLeast(1L)
        val progress = (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF161820))
                .clickable { showNowPlaying = true }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(song, Modifier.size(50.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        color = Color(0xFF8F8F9A),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = ::previous,
                    enabled = queueSongs.size > 1
                ) {
                    Text("⏮", color = Color(0xFFB8B3C7), fontSize = 18.sp)
                }
                IconButton(onClick = ::togglePlayPause) {
                    Text(
                        if (isPlaying) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 20.sp
                    )
                }
                IconButton(
                    onClick = ::next,
                    enabled = queueSongs.size > 1
                ) {
                    Text("⏭", color = Color(0xFFB8B3C7), fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = Color(0xFFB18CFF),
                trackColor = Color(0xFF2B2B35)
            )
        }
    }

    @Composable
    private fun PlayerCard(song: Song?) {
        // Media3 can resolve duration after playback starts (Drive/online streams).
        // Show the live value for the active item while retaining library metadata as fallback.
        val shownDuration = if (song != null && duration > 0L) duration else (song?.duration ?: 0L)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF211A35), Color(0xFF12151D)))).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(song, Modifier.size(82.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Chưa chọn bài hát", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(song?.artist ?: "Chọn một bài trong thư viện", color = Color(0xFFAAAAB5), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            var isSeeking by remember(song?.uri?.toString()) { mutableStateOf(false) }
            var sliderPosition by remember(song?.uri?.toString()) {
                mutableFloatStateOf(position.coerceIn(0L, max(1L, shownDuration)).toFloat())
            }
            LaunchedEffect(position, isSeeking, song?.uri?.toString()) {
                if (!isSeeking) {
                    sliderPosition = position.coerceIn(0L, max(1L, shownDuration)).toFloat()
                }
            }
            Slider(
                value = sliderPosition,
                onValueChange = {
                    isSeeking = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    seekTo(sliderPosition.toLong())
                    isSeeking = false
                },
                valueRange = 0f..max(1L, shownDuration).toFloat(),
                enabled = song != null
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color(0xFF9999A5), fontSize = 12.sp)
                Text(formatTime(shownDuration), color = Color(0xFF9999A5), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallControl(if (shuffleEnabled) "🔀" else "⇄", ::toggleShuffle, shuffleEnabled)
                SmallControl("⏮", ::previous)
                Button(
                    onClick = ::togglePlayPause,
                    enabled = song != null || songs.isNotEmpty(),
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7657D8))
                ) {
                    Text(if (isPlaying) "⏸" else "▶", fontSize = 23.sp)
                }
                SmallControl("⏭", ::next)
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
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("☷ HÀNG ĐỢI")
                }
                OutlinedButton(
                    onClick = ::stop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("■ DỪNG")
                }
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
    private fun PlaylistManagerDialog() {
        Dialog(onDismissRequest = { showPlaylists = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PLAYLIST", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text(playlists.size.toString() + " danh sách • lưu trên thiết bị", color = Color(0xFF888894), fontSize = 12.sp)
                        }
                        TextButton(onClick = { showCreatePlaylist = true }) { Text("+ Tạo") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (playlists.isEmpty()) {
                        Text("Chưa có playlist. Hãy tạo playlist rồi dùng nút ▣ cạnh bài hát để thêm nhạc.", color = Color(0xFF9A9AA5), fontSize = 13.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFF181922)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).clickable { playlistDetailId = playlist.id }) {
                                        Text(playlist.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(playlist.songUris.size.toString() + " bài • chạm để xem", color = Color(0xFF888894), fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { playPlaylist(playlist) }) { Text("▶") }
                                    TextButton(onClick = { deletePlaylist(playlist) }) { Text("×", color = Color(0xFFFF8A9A)) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showPlaylists = false }) { Text("Đóng") }
                }
            }
        }
    }

    @Composable
    private fun PlaylistDetailDialog(playlist: MusicPlaylist) {
        val playlistSongs = playlist.songUris.mapNotNull { uri ->
            songs.firstOrNull { it.uri.toString() == uri }
        }

        Dialog(onDismissRequest = { playlistDetailId = null }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.95f)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(playlist.songUris.size.toString() + " bài", color = Color(0xFF888894), fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { playPlaylist(playlist) },
                            enabled = playlistSongs.isNotEmpty()
                        ) { Text("▶ Phát") }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (playlistSongs.isEmpty()) {
                        Text(
                            "Playlist chưa có bài khả dụng. Hãy thêm nhạc bằng nút ▣ trong thư viện.",
                            color = Color(0xFF9999A5),
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(playlistSongs, key = { it.uri.toString() }) { song ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF181922))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(song.artist, color = Color(0xFF888894), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(
                                        onClick = {
                                            playlistStore.removeSong(playlist.id, song.uri.toString())
                                            refreshPlaylists()
                                        }
                                    ) { Text("XÓA", color = Color(0xFFFF8A9A)) }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { playlistDetailId = null }) { Text("Đóng") }
                }
            }
        }
    }

    @Composable
    private fun PlaylistPickerDialog(song: Song) {
        Dialog(onDismissRequest = { playlistTargetSongUri = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF101117),
                modifier = Modifier.fillMaxWidth(0.94f)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("THÊM VÀO PLAYLIST", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(song.title, color = Color(0xFF9999A5), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    if (playlists.isEmpty()) {
                        Text("Chưa có playlist.", color = Color(0xFF9999A5), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                                            newPlaylistName = ""
                            showCreatePlaylist = true
                        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text("TẠO PLAYLIST MỚI")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                OutlinedButton(
                                    onClick = { addSongToPlaylist(playlist.id, song) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(playlist.name + " • " + playlist.songUris.size.toString() + " bài", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        newPlaylistName = ""
                                        showCreatePlaylist = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("+ Tạo playlist mới") }
                            }
                        }
                    }
                    TextButton(onClick = { playlistTargetSongUri = null }) { Text("Hủy") }
                }
            }
        }
    }

    @Composable
    private fun CreatePlaylistDialog() {
        AlertDialog(
            onDismissRequest = {
                playlistTargetSongUri = null
                showCreatePlaylist = false
            },
            title = { Text("Tạo playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Tên playlist") },
                    placeholder = { Text("Ví dụ: Nhạc vàng yêu thích") },
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = { Button(onClick = ::createPlaylist) { Text("TẠO") } },
            dismissButton = {
                TextButton(onClick = {
                    playlistTargetSongUri = null
                    showCreatePlaylist = false
                }) { Text("HỦY") }
            }
        )
    }

    @Composable
    private fun SongRow(song: Song, index: Int, selected: Boolean) {
        val shownDuration = if (selected && duration > 0L) duration else song.duration
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0xFF252033) else Color(0xFF141419))
            .clickable { play(index) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("%02d", index + 1), color = if (selected) Color(0xFFC8B7FF) else Color(0xFF777783),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (libraryView == "Thư mục" && song.folder.isNotBlank()) "${song.folder} • ${song.artist}" else "${song.artist} • ${song.source}",
                    color = Color(0xFF8F8F9A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { playNext(song) },
            ) {
                Text("⏭", color = Color(0xFFB5A1FF), fontSize = 18.sp)
            }
            IconButton(onClick = { addToQueue(song) }) {
                Text("＋", color = Color(0xFFC8B7FF), fontSize = 22.sp)
            }
            IconButton(onClick = { playlistTargetSongUri = song.uri.toString() }) {
                Text("▣", color = Color(0xFF8FD3FF), fontSize = 18.sp)
            }
            IconButton(onClick = { toggleFavorite(song) }) {
                Text(if (favorites[song.id] == true) "♥" else "♡", color = if (favorites[song.id] == true) Color(0xFFFF6B81) else Color(0xFF777783), fontSize = 22.sp)
            }
            Text(formatTime(shownDuration), color = Color(0xFF858591), fontSize = 12.sp)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = max(0L, milliseconds) / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

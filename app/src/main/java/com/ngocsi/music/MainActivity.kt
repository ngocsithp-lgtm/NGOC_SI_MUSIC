package com.ngocsi.music

import android.app.Activity
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
        TvSource("VTV2 • Live", "VTV Go — kênh VTV2 trực tiếp", "https://vtvgo.vn/channel/2"),
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
    private var driveOAuthSignedIn by mutableStateOf(false)
    private var driveGoogleAccountEmail by mutableStateOf("")
    private lateinit var driveOAuthManager: DriveOAuthManager
    private var driveRecentLinks by mutableStateOf(listOf<String>())
    private val driveBrowserItems = mutableStateListOf<SharedDriveItem>()
    private var showDriveBrowser by mutableStateOf(false)
    private var driveBrowserTitle by mutableStateOf("GOOGLE DRIVE")
    private var driveBrowserFolderId by mutableStateOf<String?>(null)
    private var driveBrowserResourceKey by mutableStateOf<String?>(null)
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
    // Media3 1004 can be a transient invalid-player-state/runtime check. Retry the active item once.
    private var runtimeRecoveryInProgress = false
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

    private var pendingDriveAction: (() -> Unit)? = null

    private val driveSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Do not discard a non-OK result: Google Play services can return useful
        // ApiException diagnostics in the returned Intent even when the chooser
        // is canceled or the OAuth client is misconfigured.
        val data = result.data
        driveOAuthSignedIn = driveOAuthManager.isSignedIn()
        driveGoogleAccountEmail = driveOAuthManager.lastAccount()?.email.orEmpty()

        if (data == null) {
            pendingDriveAction = null
            driveSharedStatus = if (driveOAuthSignedIn) {
                "Đã kết nối Google Drive"
            } else {
                "Google Sign-In không trả về dữ liệu"
            }
            errorMessage = if (driveOAuthSignedIn) {
                null
            } else {
                "Google Sign-In chưa hoàn tất (resultCode=" + result.resultCode + "). " +
                    "Package: " + packageName + " • SHA-1: " + driveOAuthManager.signingCertificateSha1()
            }
            return@registerForActivityResult
        }

        driveOAuthManager.handleSignInResult(data)
            .onSuccess {
                driveOAuthSignedIn = driveOAuthManager.hasDriveScope()
                driveGoogleAccountEmail = driveOAuthManager.lastAccount()?.email.orEmpty()
                if (driveOAuthSignedIn) {
                    driveSharedStatus = "Đã đăng nhập Google Drive và cấp quyền Drive"
                    errorMessage = null
                    val action = pendingDriveAction
                    pendingDriveAction = null
                    action?.invoke()
                } else {
                    driveSharedStatus = "Đã chọn tài khoản • đang xin quyền Google Drive…"
                    errorMessage = null
                    val requested = driveOAuthManager.requestDrivePermission(this@MainActivity)
                    if (!requested) {
                        pendingDriveAction = null
                        driveSharedStatus = "Không thể mở yêu cầu quyền Google Drive"
                        errorMessage = "Tài khoản Google đã đăng nhập nhưng không mở được màn hình cấp quyền Drive. " +
                            "Package: " + packageName + " • SHA-1: " + driveOAuthManager.signingCertificateSha1()
                    }
                }
            }
            .onFailure { e ->
                pendingDriveAction = null
                driveOAuthSignedIn = false
                driveGoogleAccountEmail = ""
                driveSharedStatus = "Google Drive chưa đăng nhập"
                errorMessage = driveOAuthManager.signInErrorMessage(e)
            }
    }

    private fun signInGoogleDrive(afterSignIn: (() -> Unit)? = null) {
        pendingDriveAction = afterSignIn
        runCatching {
            driveSignInLauncher.launch(driveOAuthManager.signInIntent())
            driveSharedStatus = "Đang mở Google Sign-In…"
            errorMessage = null
        }.onFailure { e ->
            pendingDriveAction = null
            driveSharedStatus = "Không thể mở Google Sign-In"
            errorMessage = "Không mở được màn hình đăng nhập Google: " +
                (e.message ?: "lỗi không xác định")
        }
    }

    @Deprecated("Google Play services legacy additional-scope callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DriveOAuthManager.REQUEST_CODE) return

        driveOAuthSignedIn = driveOAuthManager.hasDriveScope()
        driveGoogleAccountEmail = driveOAuthManager.lastAccount()?.email.orEmpty()

        if (resultCode == Activity.RESULT_OK && driveOAuthSignedIn) {
            driveSharedStatus = "Đã cấp quyền Google Drive"
            errorMessage = null
            val action = pendingDriveAction
            pendingDriveAction = null
            action?.invoke()
        } else {
            driveSharedStatus = if (driveOAuthManager.lastAccount() != null)
                "Đã đăng nhập nhưng chưa cấp quyền Google Drive"
            else
                "Google Drive chưa đăng nhập"
            errorMessage = "Chưa cấp quyền Google Drive. Hãy bấm KẾT NỐI và chấp nhận quyền truy cập Drive."
            pendingDriveAction = null
        }
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

            // Media3 1004 is ERROR_CODE_FAILED_RUNTIME_CHECK. It is not a network
            // error and can be triggered by a transient player/decoder state.
            // Rebuild the active item once before surfacing the error to the user.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                !runtimeRecoveryInProgress
            ) {
                val activeItem = controller?.currentMediaItem
                val activeUri = activeItem?.localConfiguration?.uri
                if (activeItem != null && activeUri != null) {
                    runtimeRecoveryInProgress = true
                    val resumePosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    lifecycleScope.launch {
                        delay(250L)
                        runCatching {
                            controller?.setMediaItem(activeItem, resumePosition)
                            controller?.prepare()
                            controller?.play()
                        }.onFailure { recoveryError ->
                            errorMessage = "Media3 không thể khôi phục bài đang phát: " +
                                (recoveryError.message ?: recoveryError.javaClass.simpleName)
                        }
                        runtimeRecoveryInProgress = false
                    }
                    return
                }
            }
            runtimeRecoveryInProgress = false

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

            val activeTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: radioTitle
                ?: "bài hát"
            val detail = generatePlaybackErrorDetail(error)
            errorMessage = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Mất kết nối mạng khi phát $activeTitle. $detail"
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Máy chủ từ chối phát $activeTitle. $detail"
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "Không tìm thấy nguồn phát của $activeTitle. $detail"
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "Nguồn âm thanh của $activeTitle không được hỗ trợ hoặc máy chủ trả về dữ liệu không hợp lệ. $detail"
                else ->
                    "Không thể phát $activeTitle. Mã Media3 ${error.errorCode}. $detail"
            }

            activeRadioTitle = null
            activeRadioStreams = emptyList()
            activeRadioStreamIndex = 0
        }
    }

    private fun generatePlaybackErrorDetail(error: androidx.media3.common.PlaybackException): String {
        var cause: Throwable? = error
        var depth = 0
        repeat(8) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val http = cause as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                val body = http.headerFields["Content-Type"]?.firstOrNull().orEmpty()
                return "HTTP ${http.responseCode}" +
                    (http.responseMessage?.takeIf { it.isNotBlank() }?.let { " ${it}" } ?: "") +
                    (if (body.isNotBlank()) " • ${body}" else "")
            }
            if (depth > 0 && cause != null) {
                val causeMessage = cause?.message?.trim().orEmpty()
                if (causeMessage.isNotBlank()) {
                    return cause!!.javaClass.simpleName + ": " + causeMessage.take(180)
                }
            }
            cause = cause?.cause
            depth++
        }
        val message = error.message?.trim().orEmpty()
        return if (message.isNotBlank()) message.take(180) else "Không có thông tin chi tiết từ Media3."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        driveOAuthManager = DriveOAuthManager(this)
        driveOAuthSignedIn = driveOAuthManager.isSignedIn()
        driveGoogleAccountEmail = driveOAuthManager.lastAccount()?.email.orEmpty()

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
        val sharedAccessToken = prefs.getString("drive_access_token", "").orEmpty()
        if (sharedItems.isNotEmpty() && (sharedApiKey.isNotBlank() || sharedAccessToken.isNotBlank())) {
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

    private fun play(index: Int, driveTokenReady: Boolean = false) {
        if (index !in songs.indices) return

        // Google Drive media uses a short-lived OAuth bearer token. Refresh it
        // immediately before playback, including after an app restart, so a
        // previously saved Drive item does not fail because prefs contains an
        // expired token. The recursive call is guarded by driveTokenReady.
        if (!driveTokenReady && songs[index].source == "Google Drive" && (songs[index].uri.scheme.equals("content", true) || songs[index].uri.scheme.equals("https", true))) {
            errorMessage = "Đang xác thực Google Drive để phát…"
            lifecycleScope.launch {
                val token = driveOAuthManager.accessToken()
                if (token.isNullOrBlank()) {
                    errorMessage = "Phiên Google Drive đã hết hạn. Hãy đăng nhập lại."
                    signInGoogleDrive()
                    return@launch
                }
                prefs.edit().putString("drive_access_token", token).apply()
                play(index, driveTokenReady = true)
            }
            return
        }

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
        } else {
            // Some local DocumentsProvider/MediaStore URIs do not expose a
            // useful filename extension. Supplying the provider MIME type
            // helps Media3 select the correct progressive audio path.
            val providerMime = runCatching { contentResolver.getType(song.uri) }
                .getOrNull()
                ?.trim()
                .orEmpty()
            if (providerMime.startsWith("audio/")) {
                builder.setMimeType(providerMime)
            }
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
        var displayName = "drive_audio_" + kotlin.math.abs(uri.toString().hashCode())

        val metadataReadable = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex).orEmpty()
                        title = displayName.substringBeforeLast(".").ifBlank { "Nhạc online" }
                    }
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex)
                }
            }
            true
        }.getOrDefault(false)

        if (!metadataReadable) return null

        // Drive DocumentsProvider content:// streams can be unreliable with
        // Media3 on some phones/providers. Materialize picker-selected audio
        // into app-private storage first; importDriveSongs() runs this on IO.
        val playbackUri = if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                val sourceName = displayName.ifBlank {
                    "drive_audio_" + kotlin.math.abs(uri.toString().hashCode())
                }
                val safeName = sourceName
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .ifBlank {
                        "drive_audio_" + kotlin.math.abs(uri.toString().hashCode())
                    }
                val importsDir = File(filesDir, "drive_imports").apply { mkdirs() }
                val target = File(importsDir, safeName)
                if (!target.exists() || target.length() <= 0L) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        }
                    } ?: throw java.io.IOException("Không mở được dữ liệu file Google Drive")
                }
                Uri.fromFile(target)
            }.getOrNull()
        } else {
            uri
        }

        if (playbackUri == null) return null

        return Song(
            id = -kotlin.math.abs(uri.toString().hashCode().toLong()),
            title = title,
            artist = "Google Drive",
            duration = 0L,
            uri = playbackUri,
            source = "Google Drive Local",
            folder = "Google Drive"
        )
    }

    private data class SharedDriveItem(
        val id: String,
        val name: String,
        val mimeType: String,
        val resourceKey: String,
        val size: Long,
        val webContentLink: String = ""
    )

    private fun driveApiKey(): String = BuildConfig.DRIVE_API_KEY.trim()

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
        // Google Drive links may use either resourcekey or resourceKey casing.
        // Android Uri query-parameter lookup is case-sensitive, so normalize the key.
        val resourceKey = uri.queryParameterNames
            .firstOrNull { it.equals("resourcekey", ignoreCase = true) }
            ?.let { uri.getQueryParameter(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return id to resourceKey
    }

    private fun driveApiGet(
        url: String,
        accessToken: String? = null,
        resourceKeys: String? = null
    ): org.json.JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NGOC-SI-MUSIC/5.7 (Android)")
            accessToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            resourceKeys?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("X-Goog-Drive-Resource-Keys", it)
            }
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
        apiKey: String,
        accessToken: String? = null
    ): org.json.JSONObject {
        val url = "https://www.googleapis.com/drive/v3/files/" +
            Uri.encode(id) +
            "?fields=id,name,mimeType,size,resourceKey,webContentLink,trashed,capabilities/canDownload" +
            "&supportsAllDrives=true" +
            apiKey.takeIf { it.isNotBlank() }?.let {
                "&key=" + URLEncoder.encode(it, "UTF-8")
            }.orEmpty() +
            (resourceKey?.takeIf { it.isNotBlank() }?.let {
                "&resourceKey=" + URLEncoder.encode(it, "UTF-8")
            } ?: "")
        return driveApiGet(
            url,
            accessToken,
            resourceKey?.takeIf { it.isNotBlank() }?.let { "$id/$it" }
        )
    }

    private fun listSharedDriveFolder(
        folderId: String,
        resourceKey: String?,
        apiKey: String,
        result: MutableList<SharedDriveItem>,
        visited: MutableSet<String>,
        accessToken: String? = null,
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
                "&fields=nextPageToken,files(id,name,mimeType,size,resourceKey,webContentLink,trashed,capabilities/canDownload)" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                apiKey.takeIf { it.isNotBlank() }?.let {
                    "&key=" + URLEncoder.encode(it, "UTF-8")
                }.orEmpty() +
                (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
                (resourceKey?.takeIf { it.isNotBlank() }?.let {
                    "&resourceKeys=" + URLEncoder.encode("$folderId/$it", "UTF-8")
                } ?: "")

            val resourceKeysHeader = resourceKey?.takeIf { it.isNotBlank() }?.let {
                "$folderId/$it"
            }
            val json = driveApiGet(url, accessToken, resourceKeysHeader)
            val files = json.optJSONArray("files") ?: org.json.JSONArray()
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").ifBlank { "Google Drive" }
                val mime = item.optString("mimeType").trim()
                val childKey = item.optString("resourceKey").trim()
                val canDownload = item.optJSONObject("capabilities")
                    ?.optBoolean("canDownload", true) ?: true
                if (id.isBlank()) continue

                if (mime == "application/vnd.google-apps.folder") {
                    listSharedDriveFolder(
                        id,
                        childKey.ifBlank { null },
                        apiKey,
                        result,
                        visited,
                        accessToken,
                        depth + 1
                    )
                } else if (canDownload && isSupportedDriveAudio(name, mime)) {
                    result += SharedDriveItem(
                        id,
                        name,
                        mime,
                        childKey,
                        item.optLong("size", 0L),
                        item.optString("webContentLink").trim()
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
        // Use the Drive REST media endpoint for both public and private files.
        // MusicService adds the current OAuth Bearer token to the HTTP request,
        // avoiding browser-only webContentLink redirects for shared files.
        val builder = Uri.parse(
            "https://www.googleapis.com/drive/v3/files/" + Uri.encode(item.id)
        ).buildUpon()
            .appendQueryParameter("alt", "media")
            .appendQueryParameter("supportsAllDrives", "true")

        if (apiKey.isNotBlank()) {
            builder.appendQueryParameter("key", apiKey)
        }
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
                    put("webContentLink", item.webContentLink)
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
        driveSharedStatus = when {
            count > 0 && driveOAuthSignedIn -> "Đã liên kết $count tệp Drive chia sẻ"
            count > 0 -> "Đã lưu $count tệp Drive; đăng nhập lại để phát nguồn riêng tư"
            driveOAuthSignedIn -> "Đã đăng nhập Google Drive"
            else -> "Chưa đăng nhập Google Drive"
        }
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

    private fun loadSharedWithMeDrive() {
        if (!driveOAuthManager.hasDriveScope()) {
            driveSharedStatus = "Cần đăng nhập Google Drive để xem mục “Được chia sẻ với tôi”."
            signInGoogleDrive { loadSharedWithMeDrive() }
            return
        }

        driveImportJob?.cancel()
        driveSharedLoading = true
        errorMessage = "Đang tải Google Drive • Được chia sẻ với tôi…"

        driveImportJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                runCatching {
                    val accessToken = driveOAuthManager.accessToken()
                        ?: error("Không lấy được quyền truy cập Google Drive. Hãy đăng nhập lại.")
                    prefs.edit().putString("drive_access_token", accessToken).apply()

                    val collected = mutableListOf<SharedDriveItem>()
                    val visited = mutableSetOf<String>()
                    var pageToken: String? = null

                    do {
                        val query = URLEncoder.encode(
                            "sharedWithMe = true and trashed = false",
                            "UTF-8"
                        )
                        val url = "https://www.googleapis.com/drive/v3/files?q=$query" +
                            "&pageSize=1000" +
                            "&orderBy=folder,name" +
                            "&fields=nextPageToken,files(id,name,mimeType,size,resourceKey,webContentLink,trashed,capabilities/canDownload)" +
                            "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                            (pageToken?.let {
                                "&pageToken=" + URLEncoder.encode(it, "UTF-8")
                            } ?: "")

                        val json = driveApiGet(url, accessToken)
                        val files = json.optJSONArray("files") ?: org.json.JSONArray()

                        for (i in 0 until files.length()) {
                            val item = files.optJSONObject(i) ?: continue
                            val id = item.optString("id").trim()
                            val name = item.optString("name").ifBlank { "Google Drive" }
                            val mime = item.optString("mimeType").trim()
                            val resourceKey = item.optString("resourceKey").trim()
                            val canDownload = item.optJSONObject("capabilities")
                                ?.optBoolean("canDownload", true) ?: true

                            if (id.isBlank()) continue

                            if (mime == "application/vnd.google-apps.folder") {
                                listSharedDriveFolder(
                                    id,
                                    resourceKey.ifBlank { null },
                                    "",
                                    collected,
                                    visited,
                                    accessToken
                                )
                            } else if (canDownload && isSupportedDriveAudio(name, mime)) {
                                collected += SharedDriveItem(
                                    id,
                                    name,
                                    mime,
                                    resourceKey,
                                    item.optLong("size", 0L),
                                    item.optString("webContentLink").trim()
                                )
                            }
                        }

                        pageToken = json.optString("nextPageToken").ifBlank { null }
                    } while (!pageToken.isNullOrBlank())

                    collected.distinctBy { it.id }
                }.getOrElse { throw it }
            }

            val currentUris = songs.map { it.uri.toString() }.toMutableSet()
            val savedItems = loadSharedDriveItems().toMutableList()
            var added = 0

            result.forEach { item ->
                val uri = sharedDriveMediaUri(item, "")
                val raw = uri.toString()
                if (!currentUris.add(raw)) return@forEach

                val song = Song(
                    id = -kotlin.math.abs(raw.hashCode().toLong()),
                    title = item.name.substringBeforeLast(".").ifBlank { item.name },
                    artist = "Google Drive • Được chia sẻ",
                    duration = 0L,
                    uri = uri,
                    source = "Google Drive",
                    folder = "Được chia sẻ với tôi"
                )
                songs.add(song)
                if (queueSongs.none { it.uri == uri }) queueSongs.add(song)
                savedItems.removeAll { it.id == item.id }
                savedItems.add(item)
                added++
            }

            saveSharedDriveItems(savedItems)
            driveBrowserItems.clear()
            driveBrowserItems.addAll(result)
            driveBrowserTitle = "ĐƯỢC CHIA SẺ VỚI TÔI"
            driveBrowserFolderId = null
            driveBrowserResourceKey = null
            showDriveBrowser = result.isNotEmpty()
            syncControllerQueue()
            driveSharedLoading = false
            driveImportJob = null
            driveSharedStatus = if (result.isNotEmpty()) {
                "Đã tìm thấy " + result.size + " tệp Drive được chia sẻ với tài khoản"
            } else {
                "Không có tệp âm thanh trong “Được chia sẻ với tôi”"
            }
            errorMessage = if (added > 0) {
                "Đã thêm " + added + " bài từ Google Drive • Được chia sẻ với tôi."
            } else {
                "Không có bài mới từ Google Drive được chia sẻ."
            }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                driveSharedLoading = false
                errorMessage = when {
                    e.message.orEmpty().contains("403") ->
                        "Tài khoản Google không có quyền xem/tải các nguồn được chia sẻ."
                    e.message.orEmpty().contains("401") ->
                        "Phiên Google Drive đã hết hạn. Hãy đăng nhập lại."
                    else ->
                        "Không tải được “Được chia sẻ với tôi”: " +
                            (e.message ?: "lỗi không xác định")
                }
            } finally {
                driveImportJob = null
                driveSharedLoading = false
            }
        }
    }

    private fun ensureDriveSignedIn(action: String = "duyệt Google Drive"): Boolean {
        if (!driveOAuthManager.hasDriveScope()) {
            signInGoogleDrive()
            driveSharedStatus = "Cần đăng nhập Google Drive để " + action + "."
            return false
        }
        return true
    }

    private fun loadDriveRoot() {
        if (!ensureDriveSignedIn()) return
        driveSharedLoading = true
        errorMessage = "Đang tải Drive của tôi…"
        driveImportJob?.cancel()
        driveImportJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val token = driveOAuthManager.accessToken() ?: error("Không lấy được phiên Google Drive. Hãy đăng nhập lại.")
                    prefs.edit().putString("drive_access_token", token).apply()
                    val q = URLEncoder.encode("'root' in parents and trashed = false", "UTF-8")
                    val url = "https://www.googleapis.com/drive/v3/files?q=" + q +
                        "&pageSize=1000&orderBy=folder,name" +
                        "&fields=files(id,name,mimeType,size,resourceKey,webContentLink,capabilities/canDownload)" +
                        "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    val json = driveApiGet(url, token)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    buildList {
                        for (i in 0 until files.length()) {
                            val item = files.optJSONObject(i) ?: continue
                            val id = item.optString("id").trim()
                            val name = item.optString("name").ifBlank { "Google Drive" }
                            val mime = item.optString("mimeType").trim()
                            val canDownload = item.optJSONObject("capabilities")?.optBoolean("canDownload", true) ?: true
                            if (id.isNotBlank() &&
                                (mime == "application/vnd.google-apps.folder" ||
                                    (canDownload && isSupportedDriveAudio(name, mime)))) {
                                add(SharedDriveItem(id, name, mime, item.optString("resourceKey").trim(),
                                    item.optLong("size", 0L), item.optString("webContentLink").trim()))
                            }
                        }
                    }
                }
                driveBrowserItems.clear()
                driveBrowserItems.addAll(loaded)
                driveBrowserTitle = "DRIVE CỦA TÔI"
                driveBrowserFolderId = null
                driveBrowserResourceKey = null
                showDriveBrowser = true
                driveSharedStatus = "Đã tải " + loaded.size + " mục từ Drive"
            } catch (e: Exception) {
                errorMessage = "Không tải được Drive: " + (e.message ?: "lỗi không xác định")
            } finally {
                driveSharedLoading = false
                driveImportJob = null
            }
        }
    }

    private fun openDriveFolder(item: SharedDriveItem) {
        if (!ensureDriveSignedIn()) return
        driveSharedLoading = true
        errorMessage = "Đang mở thư mục " + item.name + "…"
        driveImportJob?.cancel()
        driveImportJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val token = driveOAuthManager.accessToken() ?: error("Không lấy được phiên Google Drive.")
                    val q = URLEncoder.encode("'" + item.id + "' in parents and trashed = false", "UTF-8")
                    val url = "https://www.googleapis.com/drive/v3/files?q=" + q +
                        "&pageSize=1000&orderBy=folder,name" +
                        "&fields=files(id,name,mimeType,size,resourceKey,webContentLink,capabilities/canDownload)" +
                        "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    val json = driveApiGet(url, token)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    buildList {
                        for (i in 0 until files.length()) {
                            val f = files.optJSONObject(i) ?: continue
                            val id = f.optString("id").trim()
                            val name = f.optString("name").ifBlank { "Google Drive" }
                            val mime = f.optString("mimeType").trim()
                            val canDownload = f.optJSONObject("capabilities")?.optBoolean("canDownload", true) ?: true
                            if (id.isNotBlank() &&
                                (mime == "application/vnd.google-apps.folder" ||
                                    (canDownload && isSupportedDriveAudio(name, mime)))) {
                                add(SharedDriveItem(id, name, mime, f.optString("resourceKey").trim(),
                                    f.optLong("size", 0L), f.optString("webContentLink").trim()))
                            }
                        }
                    }
                }
                driveBrowserItems.clear()
                driveBrowserItems.addAll(loaded)
                driveBrowserTitle = item.name
                driveBrowserFolderId = item.id
                driveBrowserResourceKey = item.resourceKey.ifBlank { null }
                showDriveBrowser = true
            } catch (e: Exception) {
                errorMessage = "Không mở được thư mục Drive: " + (e.message ?: "lỗi không xác định")
            } finally {
                driveSharedLoading = false
                driveImportJob = null
            }
        }
    }

    private suspend fun addSharedDriveItemToLibrary(item: SharedDriveItem, playNow: Boolean = false) {
        val token = driveOAuthManager.accessToken() ?: run { signInGoogleDrive(); return }
        val uri = sharedDriveMediaUri(item, "")
        val existing = songs.firstOrNull { it.uri == uri }
        val song = existing ?: Song(
            -kotlin.math.abs(uri.toString().hashCode().toLong()),
            item.name.substringBeforeLast(".").ifBlank { item.name },
            "Google Drive", 0L, uri, "Google Drive", folder = driveBrowserTitle
        )
        if (existing == null) {
            songs.add(song)
            if (queueSongs.none { it.uri == song.uri }) queueSongs.add(song)
            saveSharedDriveItems(loadSharedDriveItems().filterNot { it.id == item.id } + item)
        }
        prefs.edit().putString("drive_access_token", token).apply()
        syncControllerQueue()
        if (playNow) {
            val index = songs.indexOfFirst { it.uri == song.uri }
            if (index >= 0) play(index)
            showDriveBrowser = false
        } else {
            errorMessage = "Đã thêm “" + item.name + "” vào thư viện Google Drive."
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
        if (!driveOAuthSignedIn && apiKey.isBlank()) {
            driveSharedStatus = "Cần đăng nhập Google Drive để đọc nguồn riêng tư/chia sẻ trực tiếp."
            signInGoogleDrive { importSharedDriveLink() }
            return
        }

        driveImportJob?.cancel()
        driveSharedLoading = true
        errorMessage = "Đang kết nối Google Drive được chia sẻ…"

        driveImportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (id, suppliedResourceKey) = parsed
                    val accessToken = driveOAuthManager.accessToken()
                    if (accessToken == null && apiKey.isBlank()) error("Cần đăng nhập Google Drive.")
                    accessToken?.let { prefs.edit().putString("drive_access_token", it).apply() }
                    val root = inspectSharedDriveItem(id, suppliedResourceKey, apiKey, accessToken)
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
                            mutableSetOf(),
                            accessToken
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
                            root.optLong("size", 0L),
                            root.optString("webContentLink").trim()
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
                        if (driveOAuthManager.hasDriveScope()) {
                            "Tài khoản Google hiện tại không có quyền xem/tải nguồn này."
                        } else {
                            "Nguồn này yêu cầu tài khoản Google có quyền truy cập. Hãy bấm ĐĂNG NHẬP DRIVE rồi thử lại."
                        }
                    message.contains("401") || message.contains("unauthorized", true) ->
                        "Phiên Google Drive đã hết hạn. Hãy đăng nhập Google Drive lại."
                    else ->
                        "Không đọc được link Google Drive: " +
                            message.ifBlank { "nguồn không cho phép truy cập trực tiếp" }
                }
            }
        }
    }

    private fun openDrivePicker() {
        // Do not restrict the system picker to audio/*: some Google Drive
        // DocumentsProvider versions expose shared files with a generic MIME.
        // Let Drive show the shared file, then validate the selected URI locally.
        drivePickerLauncher.launch(arrayOf("*/*"))
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

            // Use OpenStreetMap's dedicated embed endpoint instead of the
            // full OSM SPA/hash URL. The embed page is more reliable inside
            // Android WebView and does not depend on the site's client router.
            radioWebTitle = "NGỌC SĨ MAP • VỊ TRÍ HIỆN TẠI"
            errorMessage = null

            // Prefer the native geo handler: Google Maps and other installed
            // map apps can render the exact location more reliably than a
            // WebView, while the OSM embed remains the fallback.
            val geoUri = Uri.parse(
                "geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(Vị trí hiện tại)"
            )
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, geoUri))
            }.onFailure {
                radioWebUrl =
                    "https://www.openstreetmap.org/export/embed.html" +
                        "?bbox=" + left + "," + bottom + "," + right + "," + top +
                        "&layer=mapnik&marker=" + lat + "," + lon
            }
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
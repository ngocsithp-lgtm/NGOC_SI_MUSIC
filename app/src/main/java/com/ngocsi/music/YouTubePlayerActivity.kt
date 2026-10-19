package com.ngocsi.music

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color as AndroidColor
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@UnstableApi
class YouTubePlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_title"
        const val EXTRA_CHANNEL = "youtube_channel"
        const val EXTRA_QUEUE_JSON = "youtube_queue_json"
        const val EXTRA_QUEUE_INDEX = "youtube_queue_index"
        const val EXTRA_AI_ACTION = "youtube_ai_action"
        const val EXTRA_PIP_COMMAND = "youtube_pip_command"
        private const val ACTION_PIP_PLAY_PAUSE = "com.ngocsi.music.action.YOUTUBE_PIP_PLAY_PAUSE"
        private const val ACTION_PIP_PLAY_PAUSE_INTERNAL = "com.ngocsi.music.action.YOUTUBE_PIP_PLAY_PAUSE_INTERNAL"
    }

    private lateinit var root: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var videoId: String = ""
    private var title: String = "YouTube"
    private var channel: String = "YouTube"
    private lateinit var titleView: TextView
    private var pipHeader: View? = null
    private var pipInfo: View? = null
    private var pipTransport: View? = null
    private var pipUtilityRow: View? = null
    private var pipQueueActions: View? = null
    // Keep a handle to the page scroll container so PiP always frames the video,
    // rather than an off-screen section of the watch page.
    private var contentScrollView: ScrollView? = null
    private var pipScrollY = 0
    private lateinit var channelView: TextView
    private lateinit var favoriteButton: Button
    private lateinit var watchLaterButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var playPauseButton: Button
    private data class QueueItem(val videoId: String, val title: String, val channelTitle: String, val thumbnailUrl: String)
    private val queue = mutableListOf<QueueItem>()
    private var queueIndex = 0
    private var errorView: LinearLayout? = null
    private var loadingBar: ProgressBar? = null
    private var pageErrorVisible = false
    private var youtubePlayerReady = false
    private var youtubePlayerState = -1
    // New play/pause requests invalidate older delayed retries so PiP controls
    // cannot leave multiple competing playback loops active at the same time.
    private var playbackRequestGeneration = 0
    private var restoredPositionVideoId: String? = null
    private var positionRestoreAttempts = 0
    private var pendingAiAction: String? = null
    private var queueTransitionInFlight = false
    // If the user selects another item while a queue transition is saving position,
    // queue the latest request and apply it after the current transition completes.
    private var pendingQueueLoad = false
    private var lastHandledEndedVideoId: String? = null
    private var youtubeShuffleEnabled = false
    private var youtubeRepeatMode = 0 // 0=tắt, 1=lặp hàng đợi, 2=lặp một bài
    private val thumbnailExecutor = Executors.newFixedThreadPool(3)
    private val youtubeThumbnailCache = object : android.util.LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val playbackPositionHandler = Handler(Looper.getMainLooper())
    private val playbackPositionSaver = object : Runnable {
        override fun run() {
            saveCurrentPlaybackPosition()
            if (!isFinishing && !isDestroyed) {
                playbackPositionHandler.postDelayed(this, 5000L)
            }
        }
    }

    private inner class YoutubeJsBridge {
        // Called by the WebView polling loop. The explicit PiP Play/Pause receiver
        // updates this intent, so recovery never overrides a deliberate user pause.
        @JavascriptInterface
        // The JS watchdog is only for an active PiP window. After PiP closes,
        // the dedicated native exit timer owns recovery; mixing the two caused
        // duplicate play/pause requests during the return-to-app transition.
        fun shouldResumeAfterPip(): Boolean =
            isInPictureInPictureMode && resumeVideoAfterPipEntry

        @JavascriptInterface
        fun onRecoveryClockAdvanced() {
            runOnUiThread {
                // Do not clear the post-PiP recovery guard just because YouTube says
                // "playing"; wait until the HTML media clock really advances.
                if (!isFinishing && !isDestroyed && !isInPictureInPictureMode &&
                    resumeVideoAfterPipExit
                ) {
                    resumeVideoAfterPipExit = false
                    pipRecoveryAttempts = 0
                }
            }
        }

        @JavascriptInterface
        fun onPipPlaybackStalled(mediaElementPaused: Boolean) {
            runOnUiThread {
                // Ignore stale JS callbacks after PiP closes. The post-exit timers are
                // the only recovery owners after the window has been dismissed.
                if (!isInPictureInPictureMode || !resumeVideoAfterPipEntry ||
                    isFinishing || isDestroyed
                ) return@runOnUiThread

                // Only publish "paused" when the actual HTML media element is paused.
                // A frozen clock with paused=false is a stall, not a user pause; changing
                // native state to PAUSED every few seconds made PiP flap Play/Pause.
                if (mediaElementPaused) {
                    youtubePlayerState = 2
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                        .putBoolean("youtube_playback_is_playing", false)
                        .apply()
                    updatePlaybackButton()
                    syncYouTubeBackgroundPlayback(2)
                    updatePipParamsIfSupported()
                }

                // One automatic restart per PiP session, never a repeating restart loop.
                // If YouTube briefly advances and stalls again, let the user explicitly
                // tap Play rather than continually pausing and restarting the same stream.
                if (pipRecoveryAttempts >= 1) return@runOnUiThread
                pipRecoveryAttempts++
                webView?.postDelayed({
                    if (isFinishing || isDestroyed || !isInPictureInPictureMode ||
                        !resumeVideoAfterPipEntry || webView == null
                    ) return@postDelayed

                    // Buffering may resolve by itself while the delay is pending. Recheck
                    // the live media clock before issuing a forced pause/play cycle.
                    webView?.evaluateJavascript(
                        """(function(){
                            try {
                              var v = (typeof window.__ngocSiFindActiveVideo === 'function')
                                ? window.__ngocSiFindActiveVideo()
                                : (document.querySelector('video.html5-main-video') ||
                                   document.querySelector('#movie_player video') ||
                                   document.querySelector('video'));
                              var age = Date.now() -
                                Number(window.__ngocSiVideoLastProgressAt || Date.now());
                              var shouldResume = false;
                              try { shouldResume = AndroidBridge.shouldResumeAfterPip() === true; }
                              catch (_) {}
                              return String(!!shouldResume && !!v && !v.ended &&
                                v.readyState >= 2 &&
                                ((v.paused && age >= 1200) || (!v.paused && age >= 4000)));
                            } catch (_) { return 'false'; }
                        })();""".trimIndent()
                    ) { result ->
                        if (!isFinishing && !isDestroyed && isInPictureInPictureMode &&
                            resumeVideoAfterPipEntry && result?.contains("true") == true
                        ) {
                            requestYouTubePlayback(forcePlaybackRecovery = true)
                        }
                    }
                }, 450L)
            }
        }

        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                youtubePlayerReady = true
                updatePlaybackButton()
                updatePipParamsIfSupported()
                restoreSavedPlaybackPosition()
                startPlaybackPositionSaver()
                executePendingAiAction()
            }
        }

        @JavascriptInterface
        fun onStateChanged(state: Int) {
            runOnUiThread {
                youtubePlayerState = state
                // Do NOT reset pipRecoveryAttempts just because YouTube reports state=1.
                // The iframe can report "playing" while currentTime advances briefly and
                // then freezes again; resetting here makes the watchdog restart forever.
                // Recovery attempts stay bounded for the entire PiP session and are reset
                // only when Android enters a new PiP session.
                // Persist the actual embedded YouTube playback state so the
                // compact player's button can pause/resume YouTube instead of
                // accidentally toggling the independent Media3 local player.
                val preservePipResumeIntent =
                    state == 2 && (resumeVideoAfterPipEntry || resumeVideoAfterPipExit)
                // Chromium/YouTube can emit a transient pause while Android changes the
                // Activity into PiP. Do not erase the pre-transition "was playing" signal
                // or publish a false paused session before the PiP recovery call can run.
                if (!preservePipResumeIntent) {
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                        .putBoolean("youtube_playback_is_playing", state == 1)
                        .apply()
                }
                updatePlaybackButton()
                if (!preservePipResumeIntent) {
                    syncYouTubeBackgroundPlayback(state)
                }
                updatePipParamsIfSupported()
                // The IFrame API can report onReady before the video is seekable.
                // Retry position restoration when the item becomes cued or starts.
                if (state == 5 || state == 1) restoreSavedPlaybackPosition()
                if (state == 0 && queue.isNotEmpty()) {
                    val endedId = sanitizeVideoId(videoId)
                    if (endedId.isNotBlank() && lastHandledEndedVideoId != endedId) {
                        lastHandledEndedVideoId = endedId
                        advanceAfterYoutubeEnded()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            runOnUiThread {
                showError(
                    when (code) {
                        153 -> "YouTube không xác thực được trình phát (Error 153). Hãy bấm THỬ LẠI."
                        152 -> "YouTube báo lỗi xác thực/trình phát (Error 152). Hãy bấm THỬ LẠI."
                        101, 150 -> "Video này không cho phép phát trong trình phát nhúng."
                        100 -> "Video không tồn tại hoặc đã bị gỡ."
                        else -> "YouTube báo lỗi trình phát ($code). Hãy bấm THỬ LẠI."
                    }
                )
            }
        }

        @JavascriptInterface
        fun onAutoplayBlocked() {
            runOnUiThread {
                youtubePlayerState = -1
                updatePlaybackButton()
            }
        }
    }

    private val youtubeJsBridge = YoutubeJsBridge()
    private var pipActionReceiverRegistered = false
    // Remember that playback was active before Android shrinks this Activity into PiP.
    // YouTube's mobile page may pause its HTML5 video during the resize transition.
    @Volatile
    private var resumeVideoAfterPipEntry = false
    // Remains active after PiP closes until the HTML media clock advances again.
    @Volatile
    private var resumeVideoAfterPipExit = false
    // Bound recovery calls so transient buffering does not trigger rapid play/pause cycles.
    private var pipRecoveryAttempts = 0
    private val pipActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE_INTERNAL -> {
                    // Honor the exact action shown in the PiP overlay. Do not toggle a
                    // possibly stale/hidden video element when the user explicitly taps Play.
                    if (webView != null && !isFinishing && !isDestroyed) {
                        when (intent.getStringExtra(EXTRA_PIP_COMMAND)) {
                            "play" -> requestYouTubePlayback(forcePlaybackRecovery = true)
                            "pause" -> pauseYouTubePlayback()
                            else -> toggleYoutubePlayback()
                        }
                    }
                }
                YouTubeBackgroundService.ACTION_CONTROL -> {
                    if (!youtubePlayerReady || webView == null) return
                    when (intent.getStringExtra(YouTubeBackgroundService.EXTRA_CONTROL)) {
                        "play" -> requestYouTubePlayback(forcePlaybackRecovery = isInPictureInPictureMode)
                        "pause" -> pauseYouTubePlayback()
                        "next" -> playNext()
                        "previous" -> playPrevious()
                    }
                }
            }
        }
    }
    private var youtubeBackgroundServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pipFilter = android.content.IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE_INTERNAL)
            addAction(YouTubeBackgroundService.ACTION_CONTROL)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            pipFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipActionReceiverRegistered = true

        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        // Remember that the selected playback source is YouTube so the compact
        // player routes play/pause here instead of toggling the local Media3 player.
        prefs.edit().putBoolean("youtube_active_source", true).apply()
        youtubeShuffleEnabled = savedInstanceState?.getBoolean(
            "youtube_shuffle",
            prefs.getBoolean("youtube_shuffle", false)
        ) ?: prefs.getBoolean("youtube_shuffle", false)
        youtubeRepeatMode = savedInstanceState?.getInt(
            "youtube_repeat_mode",
            prefs.getInt("youtube_repeat_mode", 0)
        )?.coerceIn(0, 2) ?: prefs.getInt("youtube_repeat_mode", 0).coerceIn(0, 2)

        videoId = sanitizeVideoId(
            savedInstanceState?.getString(EXTRA_VIDEO_ID)
                ?: intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        )
        title = (
            savedInstanceState?.getString(EXTRA_TITLE)
                ?: intent.getStringExtra(EXTRA_TITLE)
        ).orEmpty().ifBlank { "YouTube" }
        channel = (
            savedInstanceState?.getString(EXTRA_CHANNEL)
                ?: intent.getStringExtra(EXTRA_CHANNEL)
        ).orEmpty().ifBlank { "YouTube" }
        queueIndex = (
            savedInstanceState?.getInt(
                EXTRA_QUEUE_INDEX,
                intent.getIntExtra(EXTRA_QUEUE_INDEX, 0)
            ) ?: intent.getIntExtra(EXTRA_QUEUE_INDEX, 0)
        ).coerceAtLeast(0)

        val restoredQueueJson = savedInstanceState?.getString(EXTRA_QUEUE_JSON)
        parseQueue(restoredQueueJson ?: intent.getStringExtra(EXTRA_QUEUE_JSON))
        pendingAiAction = intent.getStringExtra(EXTRA_AI_ACTION)
        if (queue.isNotEmpty()) {
            queueIndex = queueIndex.coerceIn(0, queue.lastIndex)
            val selected = queue[queueIndex]
            videoId = selected.videoId
            title = selected.title
            channel = selected.channelTitle
        }

        if (videoId.isBlank()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    exitFullscreen()
                } else {
                    finish()
                }
            }
        })
        createPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // A new search result can target this existing Activity because the
        // caller uses SINGLE_TOP/REORDER_TO_FRONT. Update the current queue and
        // player instead of silently keeping the previously selected video.
        val incomingVideoId = sanitizeVideoId(intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty())
        if (incomingVideoId.isNotBlank()) {
            val incomingQueueJson = intent.getStringExtra(EXTRA_QUEUE_JSON)
            if (!incomingQueueJson.isNullOrBlank()) {
                parseQueue(incomingQueueJson)
            }

            if (queue.none { it.videoId == incomingVideoId }) {
                queue.add(
                    0,
                    QueueItem(
                        incomingVideoId,
                        intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Video YouTube" },
                        intent.getStringExtra(EXTRA_CHANNEL).orEmpty().ifBlank { "YouTube" },
                        "https://i.ytimg.com/vi/$incomingVideoId/hqdefault.jpg"
                    )
                )
                queueIndex = 0
            } else {
                val requestedIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
                queueIndex = if (requestedIndex in queue.indices &&
                    queue[requestedIndex].videoId == incomingVideoId
                ) requestedIndex else queue.indexOfFirst { it.videoId == incomingVideoId }
            }

            queueIndex = queueIndex.coerceIn(0, queue.lastIndex.coerceAtLeast(0))
            val selected = queue.getOrNull(queueIndex)
            if (selected != null) {
                val incomingTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val incomingChannel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
                // Keep queue metadata in sync with the selected result.
                queue[queueIndex] = selected.copy(
                    title = incomingTitle.ifBlank { selected.title },
                    channelTitle = incomingChannel.ifBlank { selected.channelTitle }
                )
                updateQueueButton()
                updateQueueModeButtons()
                if (youtubePlayerReady && webView != null &&
                    incomingVideoId != sanitizeVideoId(videoId)
                ) {
                    // Only reload when the target actually changes. Re-loading the
                    // same video for a pause/play voice command can race the command
                    // against the IFrame player's ready state.
                    loadQueueItem()
                } else if (!youtubePlayerReady || webView == null) {
                    val next = queue[queueIndex]
                    videoId = next.videoId
                    title = next.title
                    channel = next.channelTitle
                    titleView.text = title
                    channelView.text = channel
                    updateActionState()
                    createPlayer()
                }
            }
        }

        if (intent.action == ACTION_PIP_PLAY_PAUSE) {
            // PiP transport actions must control the embedded YouTube player directly,
            // not the separate Media3 session used for local music.
            toggleYoutubePlayback()
            updatePipParamsIfSupported()
            return
        }

        intent.getStringExtra(EXTRA_AI_ACTION)?.let { action ->
            pendingAiAction = action
            executePendingAiAction()
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
            setPadding(0, 0, 0, dp(12))
        }

        // Minimal app bar: keep navigation, but remove the redundant HOME button.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(16), dp(5))
            setBackgroundColor(AndroidColor.rgb(17, 18, 23))
        }

        val backButton = Button(this).apply {
            text = "‹"
            isAllCaps = false
            textSize = 28f
            setTextColor(AndroidColor.WHITE)
            background = roundedButtonDrawable(
                fill = AndroidColor.rgb(24, 26, 33),
                stroke = AndroidColor.rgb(42, 46, 56),
                radius = dp(13)
            )
            backgroundTintList = null
            contentDescription = "Quay lại"
            setOnClickListener {
                if (customView != null) exitFullscreen() else finish()
            }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        val headerInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val headerTitle = TextView(this).apply {
            text = "YouTube"
            setTextColor(AndroidColor.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val headerSubtitle = TextView(this).apply {
            text = "NGỌC SĨ MUSIC"
            setTextColor(AndroidColor.rgb(145, 148, 160))
            textSize = 10f
            letterSpacing = 0.08f
        }
        headerInfo.addView(headerTitle)
        headerInfo.addView(headerSubtitle)
        header.addView(headerInfo, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        pipHeader = header

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            contentDescription = "Trình phát video YouTube"
        }

        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Preserve the native 16:9 video ratio.
        val playerHeight = (resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        content.addView(playerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            playerHeight.coerceAtLeast(dp(200))
        ))

        // Separate metadata from navigation so the video remains the visual focus.
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(5))
            setBackgroundColor(AndroidColor.rgb(11, 12, 16))
        }
        pipInfo = info
        val nowPlaying = TextView(this).apply {
            tag = "youtube_playback_status"
            text = "●  ĐANG KẾT NỐI YOUTUBE"
            setTextColor(AndroidColor.rgb(171, 175, 188))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            contentDescription = "Trạng thái phát video YouTube"
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(AndroidColor.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(3))
        }
        channelView = TextView(this).apply {
            text = channel
            setTextColor(AndroidColor.rgb(158, 160, 171))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val queueHint = TextView(this).apply {
            tag = "youtube_queue_hint"
            text = "HÀNG ĐỢI • " + (queueIndex + 1) + "/" + queue.size.coerceAtLeast(1)
            setTextColor(AndroidColor.rgb(141, 238, 255))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
            setPadding(0, dp(8), 0, 0)
        }
        info.addView(nowPlaying)
        info.addView(titleView)
        info.addView(channelView)
        info.addView(queueHint)
        content.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // The three essential transport actions get the strongest visual emphasis.
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        pipTransport = transport
        previousButton = actionButton("⏮") { playPrevious() }
        playPauseButton = actionButton("▶") { toggleYoutubePlayback() }
        nextButton = actionButton("⏭") { playNext() }
        styleWideButton(playPauseButton, emphasized = true)
        previousButton.contentDescription = "Video trước"
        nextButton.contentDescription = "Video tiếp theo"
        previousButton.textSize = 19f
        playPauseButton.textSize = 22f
        nextButton.textSize = 19f
        transport.addView(previousButton, LinearLayout.LayoutParams(dp(58), dp(48)).apply {
            marginEnd = dp(12)
        })
        transport.addView(playPauseButton, LinearLayout.LayoutParams(dp(72), dp(52)).apply {
            marginEnd = dp(12)
        })
        transport.addView(nextButton, LinearLayout.LayoutParams(dp(58), dp(48)))
        content.addView(transport)

        // Keep YouTube playback inside the app's embedded player.
        // Do not hand off to an external browser: this screen must remain the app player.

        // Secondary actions are compact and visually subordinate to play/pause.
        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(1), dp(10), dp(3))
        }
        pipUtilityRow = utilityRow
        favoriteButton = actionButton("♡\nYêu thích") { toggleFavorite() }.apply {
            textSize = 11f
            contentDescription = "Yêu thích"
        }
        watchLaterButton = actionButton("🔖\nXem sau") { toggleWatchLater() }.apply {
            textSize = 11f
            contentDescription = "Lưu vào danh sách xem sau"
        }
        val shuffleButton = actionButton("🔀\nTrộn") { toggleYoutubeShuffle() }.apply {
            tag = "youtube_shuffle_button"
            textSize = 11f
            contentDescription = "Phát ngẫu nhiên hàng đợi YouTube"
        }
        val repeatButton = actionButton("🔁\nLặp") { toggleYoutubeRepeat() }.apply {
            tag = "youtube_repeat_button"
            textSize = 11f
            contentDescription = "Chế độ lặp hàng đợi YouTube"
        }
        listOf(favoriteButton, watchLaterButton, shuffleButton, repeatButton)
            .forEachIndexed { index, button ->
                utilityRow.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    if (index < 4) marginEnd = dp(4)
                })
            }
        content.addView(utilityRow)

        // Queue is one clear entry point; share stays available but no longer competes as a large button.
        val queueActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(3))
        }
        pipQueueActions = queueActions
        val queueButton = Button(this).apply {
            text = "☷  Hàng đợi  •  " + (queueIndex + 1) + "/" + queue.size
            tag = "queue_button"
            setOnClickListener { showQueueDialog() }
        }
        styleWideButton(queueButton)
        queueButton.textSize = 14f
        queueActions.addView(queueButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginEnd = dp(8)
        })

        val pipButton = actionButton("▣ PiP") { enterYouTubePip() }.apply {
            contentDescription = "Mở trình phát YouTube thu nhỏ"
            textSize = 12f
        }
        queueActions.addView(pipButton, LinearLayout.LayoutParams(dp(72), dp(46)).apply {
            marginEnd = dp(8)
        })

        val share = actionButton("↗") {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + videoId)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
        }.apply {
            contentDescription = "Chia sẻ video"
            textSize = 20f
        }
        queueActions.addView(share, LinearLayout.LayoutParams(dp(48), dp(46)))
        content.addView(queueActions)

        // A slim position indicator makes the current place in the YouTube queue
        // visible without competing with the video or the primary transport buttons.
        val queueProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            tag = "queue_progress"
            isIndeterminate = false
            max = queue.size.coerceAtLeast(1)
            progress = (queueIndex + 1).coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(AndroidColor.rgb(141, 238, 255))
            progressBackgroundTintList = ColorStateList.valueOf(AndroidColor.rgb(39, 43, 53))
        }
        content.addView(
            queueProgress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = dp(2)
            }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
            clipToPadding = false
            addView(content)
        }
        contentScrollView = scroll
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = View.GONE
        }
        root.addView(loadingBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(3),
            Gravity.TOP
        ))

        setContentView(root)
        updateActionState()
        updateQueueModeButtons()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(AndroidColor.WHITE)
            background = roundedButtonDrawable(
                fill = AndroidColor.rgb(22, 25, 34),
                stroke = AndroidColor.rgb(48, 55, 70),
                radius = dp(12)
            )
            backgroundTintList = null
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }

    private fun roundedButtonDrawable(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = radius.toFloat()
        }

    private fun styleWideButton(button: Button, emphasized: Boolean = false) {
        button.isAllCaps = false
        button.setTextColor(
            if (emphasized) AndroidColor.BLACK else AndroidColor.WHITE
        )
        button.background = roundedButtonDrawable(
            fill = if (emphasized) AndroidColor.rgb(141, 238, 255) else AndroidColor.rgb(17, 20, 28),
            stroke = if (emphasized) AndroidColor.rgb(141, 238, 255) else AndroidColor.rgb(43, 49, 62),
            radius = dp(13)
        )
        button.backgroundTintList = null
        button.minHeight = dp(44)
        button.setPadding(dp(8), 0, dp(8), 0)
    }

    private fun styleModeButton(button: Button, active: Boolean) {
        button.isAllCaps = false
        button.setTextColor(
            if (active) AndroidColor.rgb(143, 214, 148) else AndroidColor.WHITE
        )
        button.background = roundedButtonDrawable(
            fill = if (active) AndroidColor.rgb(24, 45, 29) else AndroidColor.rgb(18, 21, 29),
            stroke = if (active) AndroidColor.rgb(72, 120, 78) else AndroidColor.rgb(43, 49, 62),
            radius = dp(11)
        )
        button.backgroundTintList = null
        button.setPadding(dp(5), 0, dp(5), 0)
    }

    private fun parseQueue(raw: String?) {
        queue.clear()
        if (raw.isNullOrBlank()) return
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = sanitizeVideoId(obj.optString("videoId"))
                if (id.isBlank()) continue
                queue.add(QueueItem(
                    id,
                    obj.optString("title").ifBlank { "Video YouTube" },
                    obj.optString("channelTitle").ifBlank { "YouTube" },
                    obj.optString("thumbnailUrl").ifBlank { "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg" }
                ))
            }
        }
    }

    private fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (queueTransitionInFlight) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return

        val movingCurrent = queueIndex == fromIndex
        val targetCurrent = queueIndex == toIndex

        val moving = queue.removeAt(fromIndex)
        queue.add(toIndex, moving)

        queueIndex = when {
            movingCurrent -> toIndex
            targetCurrent -> fromIndex
            else -> queueIndex
        }.coerceIn(0, queue.lastIndex.coerceAtLeast(0))

        saveLastPlayedState()
        updateQueueButton()
        updateQueueModeButtons()
    }

    private fun showQueueDialog() {
        if (queue.isEmpty()) return

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AndroidColor.rgb(18, 18, 22))
            addView(list)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("HÀNG ĐỢI YOUTUBE • " + (queueIndex + 1) + "/" + queue.size)
            .setView(scroll)
            .setNegativeButton("ĐÓNG", null)
            .create()

        dialog.setOnShowListener {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.56f).toInt().coerceAtLeast(dp(260))
            scroll.post {
                val desiredHeight = list.measuredHeight
                val targetHeight = desiredHeight.coerceAtMost(maxHeight)
                if (targetHeight > 0) {
                    scroll.layoutParams = scroll.layoutParams.apply {
                        height = targetHeight
                    }
                    scroll.requestLayout()
                }
            }
        }

        queue.forEachIndexed { index, item ->
            val isCurrent = index == queueIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setBackgroundColor(
                    if (isCurrent) AndroidColor.rgb(28, 44, 32)
                    else AndroidColor.rgb(24, 24, 28)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (queueTransitionInFlight) return@setOnClickListener
                    queueIndex = index
                    loadQueueItem()
                    dialog.dismiss()
                }
            }

            val thumb = ImageView(this).apply {
                setBackgroundColor(AndroidColor.rgb(42, 42, 48))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Ảnh thu nhỏ video " + (index + 1)
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(78), dp(52)).apply {
                marginEnd = dp(8)
            })

            val number = TextView(this).apply {
                text = if (isCurrent) "▶" else (index + 1).toString()
                setTextColor(
                    if (isCurrent) AndroidColor.rgb(143, 214, 148) else AndroidColor.rgb(170, 170, 180)
                )
                textSize = if (isCurrent) 14f else 13f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(number, LinearLayout.LayoutParams(dp(28), dp(58)))

            val thumbnailUrl = item.thumbnailUrl.ifBlank {
                "https://i.ytimg.com/vi/" + item.videoId + "/hqdefault.jpg"
            }
            thumb.tag = thumbnailUrl
            val cachedThumbnail = youtubeThumbnailCache.get(thumbnailUrl)
            if (cachedThumbnail != null) {
                thumb.setImageBitmap(cachedThumbnail)
            } else {
                thumbnailExecutor.execute {
                    val bitmap = runCatching {
                        (URL(thumbnailUrl).openConnection() as? HttpURLConnection)?.let { connection ->
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.instanceFollowRedirects = true
                            connection.useCaches = true
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                            connection.connect()
                            if (connection.responseCode in 200..299) {
                                connection.inputStream.use { BitmapFactory.decodeStream(it) }
                            } else {
                                null
                            }.also {
                                connection.disconnect()
                            }
                        }
                    }.getOrNull()

                    if (bitmap != null) {
                        youtubeThumbnailCache.put(thumbnailUrl, bitmap)
                    }

                    if (bitmap != null && !isFinishing && !isDestroyed) {
                        runOnUiThread {
                            if (thumb.parent != null && thumb.tag == thumbnailUrl) {
                                thumb.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val rowTitle = TextView(this).apply {
                text = item.title
                setTextColor(AndroidColor.WHITE)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = if (isCurrent) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            }
            info.addView(rowTitle)

            val rowChannel = TextView(this).apply {
                text = if (isCurrent) "ĐANG PHÁT • " + item.channelTitle else item.channelTitle
                setTextColor(
                    if (isCurrent) AndroidColor.rgb(143, 214, 148) else AndroidColor.rgb(155, 155, 166)
                )
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            info.addView(rowChannel)

            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val queueControls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val moveUpButton = Button(this).apply {
                text = "↑"
                isAllCaps = false
                textSize = 14f
                setTextColor(
                    if (index > 0) AndroidColor.WHITE else AndroidColor.rgb(75, 75, 82)
                )
                background = roundedButtonDrawable(
                    fill = AndroidColor.rgb(25, 27, 34),
                    stroke = AndroidColor.rgb(48, 52, 62),
                    radius = dp(9)
                )
                backgroundTintList = null
                isEnabled = index > 0
                contentDescription = "Đưa " + item.title + " lên"
                setOnClickListener {
                    if (queueTransitionInFlight) return@setOnClickListener
                    moveQueueItem(index, index - 1)
                    dialog.dismiss()
                    showQueueDialog()
                }
            }

            val moveDownButton = Button(this).apply {
                text = "↓"
                isAllCaps = false
                textSize = 14f
                setTextColor(
                    if (index < queue.lastIndex) AndroidColor.WHITE else AndroidColor.rgb(75, 75, 82)
                )
                background = roundedButtonDrawable(
                    fill = AndroidColor.rgb(25, 27, 34),
                    stroke = AndroidColor.rgb(48, 52, 62),
                    radius = dp(9)
                )
                backgroundTintList = null
                isEnabled = index < queue.lastIndex
                contentDescription = "Đưa " + item.title + " xuống"
                setOnClickListener {
                    if (queueTransitionInFlight) return@setOnClickListener
                    moveQueueItem(index, index + 1)
                    dialog.dismiss()
                    showQueueDialog()
                }
            }

            queueControls.addView(
                moveUpButton,
                LinearLayout.LayoutParams(dp(38), dp(30))
            )
            queueControls.addView(
                moveDownButton,
                LinearLayout.LayoutParams(dp(38), dp(30)).apply { topMargin = dp(2) }
            )
            row.addView(
                queueControls,
                LinearLayout.LayoutParams(dp(38), dp(62)).apply {
                    marginStart = dp(4)
                }
            )

            val removeButton = Button(this).apply {
                text = "×"
                isAllCaps = false
                textSize = 18f
                setTextColor(AndroidColor.rgb(255, 138, 154))
                background = roundedButtonDrawable(
                    fill = AndroidColor.rgb(30, 23, 27),
                    stroke = AndroidColor.rgb(70, 43, 50),
                    radius = dp(10)
                )
                backgroundTintList = null
                contentDescription = "Xóa " + item.title + " khỏi hàng đợi"
                isEnabled = !isCurrent
                setOnClickListener {
                    if (queueTransitionInFlight || isCurrent) return@setOnClickListener
                    queue.removeAt(index)
                    if (index < queueIndex) {
                        queueIndex--
                    }
                    queueIndex = queueIndex.coerceIn(0, (queue.lastIndex).coerceAtLeast(0))
                    saveLastPlayedState()
                    updateQueueButton()
                    updateQueueModeButtons()
                    dialog.dismiss()
                    showQueueDialog()
                }
            }
            row.addView(
                removeButton,
                LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginStart = dp(5)
                }
            )

            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(3)
            })
        }

        dialog.show()
    }

    private fun playPrevious() {
        if (queueTransitionInFlight || queue.size <= 1) return

        queueIndex = when {
            youtubeShuffleEnabled -> {
                queue.indices.filter { it != queueIndex }.random()
            }
            queueIndex > 0 -> queueIndex - 1
            youtubeRepeatMode == 1 -> queue.lastIndex
            else -> return
        }
        loadQueueItem()
    }

    private fun playNext() {
        if (queueTransitionInFlight || queue.size <= 1) return

        queueIndex = when {
            youtubeShuffleEnabled -> {
                queue.indices.filter { it != queueIndex }.random()
            }
            queueIndex < queue.lastIndex -> queueIndex + 1
            youtubeRepeatMode == 1 -> 0
            else -> return
        }
        loadQueueItem()
    }

    private fun advanceAfterYoutubeEnded() {
        if (queue.isEmpty()) return
        when {
            youtubeRepeatMode == 2 -> loadQueueItem()
            youtubeShuffleEnabled && queue.size > 1 -> {
                val candidates = queue.indices.filter { it != queueIndex }
                queueIndex = candidates.random()
                loadQueueItem()
            }
            queueIndex < queue.lastIndex -> {
                queueIndex++
                loadQueueItem()
            }
            youtubeRepeatMode == 1 -> {
                queueIndex = 0
                loadQueueItem()
            }
        }
    }

    private fun toggleYoutubeShuffle() {
        youtubeShuffleEnabled = !youtubeShuffleEnabled
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit()
            .putBoolean("youtube_shuffle", youtubeShuffleEnabled)
            .apply()
        updateQueueModeButtons()
    }

    private fun toggleYoutubeRepeat() {
        youtubeRepeatMode = (youtubeRepeatMode + 1) % 3
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit()
            .putInt("youtube_repeat_mode", youtubeRepeatMode)
            .apply()
        updateQueueModeButtons()
    }

    private fun updateQueueModeButtons() {
        root.findViewWithTag<Button>("youtube_shuffle_button")?.apply {
            text = if (youtubeShuffleEnabled) "🔀\nTrộn ✓" else "🔀\nTrộn"
            styleModeButton(this, youtubeShuffleEnabled)
        }
        root.findViewWithTag<Button>("youtube_repeat_button")?.apply {
            text = when (youtubeRepeatMode) {
                1 -> "🔁\nLặp hàng đợi"
                2 -> "🔂\nLặp bài"
                else -> "🔁\nLặp"
            }
            styleModeButton(this, youtubeRepeatMode != 0)
        }
    }

    private fun loadQueueItem() {
        val selected = queue.getOrNull(queueIndex) ?: return

        if (!youtubePlayerReady || webView == null) {
            videoId = selected.videoId
            title = selected.title
            channel = selected.channelTitle
            createPlayer()
            return
        }

        if (queueTransitionInFlight) {
            pendingQueueLoad = true
            return
        }
        queueTransitionInFlight = true

        val previousVideoId = videoId
        // Capture the outgoing item's position before changing videoId. Waiting for
        // the JS callback prevents a delayed position read from becoming associated
        // with the newly selected queue item.
        saveCurrentPlaybackPosition(previousVideoId) {
            runOnUiThread {
                queueTransitionInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread

                // A new search result may have changed queueIndex while the outgoing
                // position was being saved. Apply the newest selection directly so
                // we don't briefly load a stale item or persist mismatched metadata.
                val target = if (pendingQueueLoad) {
                    pendingQueueLoad = false
                    queue.getOrNull(queueIndex) ?: selected
                } else {
                    selected
                }
                videoId = target.videoId
                title = target.title
                channel = target.channelTitle
                restoredPositionVideoId = null
                positionRestoreAttempts = 0
                lastHandledEndedVideoId = null

                // Keep one IFrame player alive while moving through the queue.
                // This reduces WebView churn and makes next/previous transitions
                // much less prone to blank surfaces or repeated player handshakes.
                // The mobile site navigates to a fresh document for each queue item.
                // Clear the previous video's ready/playing state so commands do not target
                // a stale HTMLVideoElement while the next page is still loading.
                youtubePlayerReady = false
                youtubePlayerState = -1
                getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                    .putBoolean("youtube_playback_is_playing", false)
                    .putBoolean("youtube_is_playing", false)
                    .apply()

                titleView.text = title
                channelView.text = channel
                updateActionState()
                updateQueueButton()
                updatePlaybackButton()
                saveLastPlayedState()

                val safeId = sanitizeVideoId(videoId)
                webView?.loadUrl("https://m.youtube.com/watch?v=" + safeId)
            }
        }
    }

    private fun saveCurrentPlaybackPosition(
        videoIdOverride: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (!youtubePlayerReady || webView == null) {
            onComplete?.invoke()
            return
        }
        val targetId = sanitizeVideoId(videoIdOverride ?: videoId)
        if (targetId.isBlank()) {
            onComplete?.invoke()
            return
        }

        webView?.evaluateJavascript(
            """(function(){
                try {
                  var v = (typeof window.__ngocSiFindActiveVideo === 'function')
                    ? window.__ngocSiFindActiveVideo()
                    : (document.querySelector('video.html5-main-video') ||
                       document.querySelector('#movie_player video') ||
                       document.querySelector('video'));
                  var m = location.href.match(/[?&]v=([A-Za-z0-9_-]+)/);
                  return JSON.stringify({id:m?m[1]:'',p:v?Math.floor(v.currentTime*1000):0});
                } catch(e) { return '{}'; }
            })();""".trimIndent()
        ) { value ->
            val payload = runCatching {
                org.json.JSONObject(value.trim('"').replace("\\\"", "\""))
            }.getOrNull()

            if (payload != null) {
                val actualId = sanitizeVideoId(payload.optString("id"))
                val positionMs = payload.optLong("p", 0L).coerceAtLeast(0L)
                // Bind the saved position to the actual video reported by the player.
                // This prevents delayed JS callbacks from writing one video's position
                // into another video's slot.
                if (actualId == targetId) {
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                        .edit()
                        .putLong("youtube_position_ms_" + targetId, positionMs)
                        .apply()
                }
            }
            onComplete?.invoke()
        }
    }

    private fun restoreSavedPlaybackPosition() {
        if (!youtubePlayerReady || videoId.isBlank() || webView == null) return
        val safeId = sanitizeVideoId(videoId)
        if (restoredPositionVideoId == safeId || positionRestoreAttempts >= 12) return
        val positionMs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .getLong("youtube_position_ms_" + safeId, 0L)
        if (positionMs < 3000L) {
            restoredPositionVideoId = safeId
            return
        }

        positionRestoreAttempts++
        val seconds = positionMs / 1000.0
        webView?.evaluateJavascript(
            """(function(){
                try {
                  var v = (typeof window.__ngocSiFindActiveVideo === 'function')
                    ? window.__ngocSiFindActiveVideo()
                    : (document.querySelector('video.html5-main-video') ||
                       document.querySelector('#movie_player video') ||
                       document.querySelector('video'));
                  if(!v || !isFinite(v.duration)) return 'wait';
                  v.currentTime = $seconds;
                  return 'ok';
                } catch(e) { return 'wait'; }
            })();""".trimIndent()
        ) { result ->
            if (isFinishing || isDestroyed || safeId != sanitizeVideoId(videoId)) return@evaluateJavascript
            if (result?.contains("ok") == true) {
                restoredPositionVideoId = safeId
            } else if (positionRestoreAttempts < 12) {
                playbackPositionHandler.postDelayed({
                    if (safeId == sanitizeVideoId(videoId)) restoreSavedPlaybackPosition()
                }, 400L)
            }
        }
    }

    private fun startPlaybackPositionSaver() {
        playbackPositionHandler.removeCallbacks(playbackPositionSaver)
        playbackPositionHandler.post(playbackPositionSaver)
    }

    private fun stopPlaybackPositionSaver() {
        playbackPositionHandler.removeCallbacks(playbackPositionSaver)
    }

    private fun buildQueueJson(): String =
        org.json.JSONArray().apply {
            queue.forEach { item ->
                put(org.json.JSONObject().apply {
                    put("videoId", item.videoId)
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                })
            }
        }.toString()

    private fun saveLastPlayedState() {
        val queueJson = buildQueueJson()

        val thumbnail = queue.getOrNull(queueIndex)?.thumbnailUrl?.ifBlank {
            "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"
        } ?: "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"

        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putString("youtube_last_played_video_id", videoId)
            .putString("youtube_last_played_title", title)
            .putString("youtube_last_played_channel", channel)
            .putString("youtube_last_played_thumbnail", thumbnail)
            .putString("youtube_last_played_queue_json", queueJson)
            .putInt("youtube_last_played_queue_index", queueIndex.coerceAtLeast(0))
            .apply()
    }

    private fun isFavorite(): Boolean =
        (getSharedPreferences("ngoc_si_music", MODE_PRIVATE).getStringSet("youtube_favorites", emptySet()) ?: emptySet()).contains(videoId)

    private fun toggleFavorite() {
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val ids = (prefs.getStringSet("youtube_favorites", emptySet()) ?: emptySet()).toMutableSet()
        val metadata = runCatching { org.json.JSONObject(prefs.getString("youtube_favorite_meta", "{}").orEmpty()) }.getOrElse { org.json.JSONObject() }
        if (ids.contains(videoId)) {
            ids.remove(videoId)
            metadata.remove(videoId)
        } else {
            ids.add(videoId)
            metadata.put(videoId, org.json.JSONObject().apply {
                put("title", title)
                put("channelTitle", channel)
                put("thumbnailUrl", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg")
            })
        }
        prefs.edit().putStringSet("youtube_favorites", ids).putString("youtube_favorite_meta", metadata.toString()).apply()
        updateActionState()
    }

    private fun isWatchLater(): Boolean {
        val raw = getSharedPreferences("ngoc_si_music", MODE_PRIVATE).getString("youtube_watch_later", null) ?: return false
        val array = runCatching { org.json.JSONArray(raw) }.getOrElse { return false }
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optString("videoId") == videoId) return true
        }
        return false
    }

    private fun toggleWatchLater() {
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val source = runCatching { org.json.JSONArray(prefs.getString("youtube_watch_later", "[]").orEmpty()) }.getOrElse { org.json.JSONArray() }
        val updated = org.json.JSONArray()
        var removed = false
        for (i in 0 until source.length()) {
            val obj = source.optJSONObject(i) ?: continue
            if (obj.optString("videoId") == videoId) removed = true else updated.put(obj)
        }
        if (!removed) updated.put(org.json.JSONObject().apply {
            put("videoId", videoId)
            put("title", title)
            put("channelTitle", channel)
            put("thumbnailUrl", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg")
        })
        prefs.edit().putString("youtube_watch_later", updated.toString()).apply()
        updateActionState()
    }


    private fun toggleYoutubePlayback() {
        runYoutubePlaybackCommand("toggle")
    }

    private fun pauseYouTubePlayback() {
        // An explicit user pause overrides automatic PiP recovery and cancels
        // every pending play retry before the pause command is sent.
        resumeVideoAfterPipEntry = false
        resumeVideoAfterPipExit = false
        pipRecoveryAttempts = 0
        playbackRequestGeneration++
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit().putBoolean("youtube_playback_is_playing", false).apply()
        runYoutubePlaybackCommand("pause")
    }

    /**
     * All WebView playback paths use the same active-video selection. The YouTube mobile
     * page can keep a hidden/stale video element, which made a PiP Play tap target the wrong
     * element or accidentally pause instead of play.
     */
    private fun youtubePlaybackScript(command: String): String {
        val safeCommand = when (command) {
            "play" -> "play"
            "resume" -> "resume"
            "force-resume" -> "force-resume"
            "pause" -> "pause"
            else -> "toggle"
        }
        return """
            (function(){
              try {
                function isVisibleVideo(video) {
                  if (!video || !video.isConnected) return false;
                  var rect = video.getBoundingClientRect();
                  if (rect.width < 2 || rect.height < 2) return false;
                  var node = video;
                  while (node && node.nodeType === 1 && node !== document.documentElement) {
                    var style = window.getComputedStyle(node);
                    if (style.display === 'none' || style.visibility === 'hidden' ||
                        Number(style.opacity || 1) === 0) return false;
                    node = node.parentElement;
                  }
                  return true;
                }
                function findVideo() {
                  try {
                    if (typeof window.__ngocSiFindActiveVideo === 'function') {
                      var current = window.__ngocSiFindActiveVideo();
                      if (current && current.isConnected) return current;
                    }
                  } catch (_) {}
                  var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                  var visible = videos.filter(isVisibleVideo);
                  // Lock onto the actual YouTube player video first. Do not let a playing
                  // ad/preview video outrank the paused main video during PiP.
                  var primary = visible.find(function(video) {
                    return video.classList.contains('html5-main-video') ||
                      !!video.closest('#movie_player') ||
                      !!video.closest('.html5-video-player') ||
                      !!video.closest('ytm-player');
                  });
                  if (primary) return primary;
                  function score(video) {
                    var rect = video.getBoundingClientRect();
                    var value = rect.width * rect.height;
                    if (!video.paused && !video.ended) value += 100000000;
                    if (video.readyState >= 2) value += 1000000;
                    if (video.currentTime > 0) value += 100000;
                    return value;
                  }
                  visible.sort(function(a,b){ return score(b) - score(a); });
                  return visible[0] || videos[0] || null;
                }
                function getPlayerApi() {
                  return document.querySelector('#movie_player') ||
                         document.querySelector('.html5-video-player') ||
                         document.querySelector('ytm-player');
                }
                function clickPlayButton() {
                  var selectors = [
                    '.ytp-play-button',
                    'button[aria-label*="play" i]',
                    'button[aria-label*="phát" i]',
                    'button[aria-label*="resume" i]',
                    'button[aria-label*="tiếp tục" i]',
                    '[role="button"][aria-label*="play" i]',
                    '[role="button"][aria-label*="phát" i]',
                    'button[title*="play" i]',
                    'button[title*="phát" i]'
                  ];
                  for (var i = 0; i < selectors.length; i++) {
                    var buttons = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < buttons.length; j++) {
                      var button = buttons[j];
                      var rect = button.getBoundingClientRect();
                      if (rect.width < 2 || rect.height < 2) continue;
                      var label = ((button.getAttribute('aria-label') || '') + ' ' +
                                   (button.getAttribute('title') || '')).toLowerCase();
                      if (label.indexOf('pause') >= 0 || label.indexOf('tạm dừng') >= 0) continue;
                      try { button.click(); return true; } catch (_) {}
                    }
                  }
                  return false;
                }

                var video = findVideo();
                if (!video) return 'no-video';
                var api = getPlayerApi();

                // An explicit Play tap inside PiP must not be swallowed by a stale
                // "already playing" flag. Restart the same active media element exactly
                // once; do not also call the YouTube API, which would create overlapping
                // playback requests and cause audio stutter.
                // Compare the real HTML media clock, not only video.paused. A healthy
                // video must never be interrupted by an Android PiP Play action.
                var lastProgressAt = Number(window.__ngocSiVideoLastProgressAt || 0);
                var progressAge = lastProgressAt > 0 ? Date.now() - lastProgressAt : 999999;
                var mediaIsPlaying = !video.paused && !video.ended &&
                    video.readyState >= 2;
                // A recently replaced DOM video element is not proof that playback advanced.
                // Only a measured forward media-clock movement confirms that its stream is live.
                var progressAdvanced = window.__ngocSiVideoHasAdvancedSinceChange === true;
                var progressIsFresh = progressAge < 1400 && progressAdvanced;
                var stalledInPip = mediaIsPlaying && !progressIsFresh;

                if ('$safeCommand' === 'force-resume') {
                  // Refresh native controls after actual media time moves, even if the
                  // element's paused/playing flag stayed unchanged through PiP.
                  window.__ngocSiForceStateRefresh = true;
                  if (mediaIsPlaying && progressIsFresh) return 'playing';
                  if (video.paused || video.ended || video.readyState < 2) {
                    try {
                      var directPlayPromise = video.play();
                      if (directPlayPromise && typeof directPlayPromise.catch === 'function') {
                        directPlayPromise.catch(function(){});
                      }
                      return 'requested-video';
                    } catch (_) {}
                  }
                  // A still-"playing" element whose clock has stopped needs one restart.
                  try { video.pause(); } catch (_) {}
                  window.setTimeout(function(){
                    try {
                      if (video.isConnected && findVideo() === video) {
                        var forcedPromise = video.play();
                        if (forcedPromise && typeof forcedPromise.catch === 'function') {
                          forcedPromise.catch(function(){});
                        }
                      }
                    } catch (_) {}
                  }, 140);
                  return 'restart-requested';
                }

                // WebView can report "playing" while the PiP surface is frozen.
                // Only issue a recovery when its clock has stopped advancing.
                var stalledResume = '$safeCommand' === 'resume' && mediaIsPlaying &&
                    stalledInPip;
                if ('$safeCommand' === 'resume' && mediaIsPlaying && !stalledInPip) {
                  return 'playing';
                }
                if (stalledResume) {
                  // A frozen frame may leave video.paused=false, so a plain play()
                  // is a no-op. Perform one bounded media-element restart instead.
                  try { video.pause(); } catch (_) {}
                  window.setTimeout(function(){
                    try {
                      if (video.isConnected && findVideo() === video) {
                        var resumePromise = video.play();
                        if (resumePromise && typeof resumePromise.catch === 'function') {
                          resumePromise.catch(function(){});
                        }
                      }
                    } catch (_) {}
                  }, 120);
                  return 'restart-requested';
                }

                var wantsPause = '$safeCommand' === 'pause' ||
                    ('$safeCommand' === 'toggle' && !video.paused && !video.ended);

                if (wantsPause) {
                  try { if (api && typeof api.pauseVideo === 'function') api.pauseVideo(); } catch (_) {}
                  try { video.pause(); } catch (_) {}
                  return 'paused';
                }

                // PiP was audibly stuttering because one tap triggered three overlapping
                // playback mechanisms (DOM click, YouTube player API, and video.play()).
                // Choose exactly one mechanism per request. The YouTube player API is the
                // preferred path; only use the visible button or HTML media API when it is
                // unavailable. Never start a second path from a rejected play promise.
                if ('$safeCommand' !== 'resume' &&
                    !video.paused && !video.ended && video.readyState >= 2) return 'playing';

                if (api && typeof api.playVideo === 'function') {
                  try {
                    api.playVideo();
                    return 'requested-api';
                  } catch (_) {}
                }
                if (clickPlayButton()) return 'requested-button';
                try {
                  var promise = video.play();
                  if (promise && typeof promise.catch === 'function') {
                    promise.catch(function(){});
                  }
                  return !video.paused ? (video.readyState >= 2 ? 'playing' : 'buffering') : 'requested-video';
                } catch (_) {
                  return 'retry';
                }
              } catch (_) {
                return 'error';
              }
            })();
        """.trimIndent()
    }

    private fun runYoutubePlaybackCommand(command: String) {
        if (!youtubePlayerReady || webView == null || isFinishing || isDestroyed) return
        webView?.evaluateJavascript(youtubePlaybackScript(command)) { result ->
            if ((result?.contains("retry") == true || result?.contains("no-video") == true) &&
                !isFinishing && !isDestroyed && command != "pause"
            ) {
                playbackPositionHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        webView?.evaluateJavascript(youtubePlaybackScript(command), null)
                    }
                }, 350L)
            }
        }
    }

    /**
     * Promotes user-started in-app YouTube playback to a media foreground service.
     * The HTML5 video remains the audio renderer; the service preserves process
     * priority, holds a partial wake lock while it reports playing, and publishes
     * its own lock-screen controls instead of reusing the local/Drive Media3 session.
     */
    private fun syncYouTubeBackgroundPlayback(state: Int) {
        val serviceIntent = Intent(this, YouTubeBackgroundService::class.java).apply {
            action = YouTubeBackgroundService.ACTION_UPDATE
            putExtra(YouTubeBackgroundService.EXTRA_VIDEO_ID, videoId)
            putExtra(YouTubeBackgroundService.EXTRA_TITLE, title)
            putExtra(YouTubeBackgroundService.EXTRA_CHANNEL, channel)
            putExtra(YouTubeBackgroundService.EXTRA_QUEUE_JSON, buildQueueJson())
            putExtra(YouTubeBackgroundService.EXTRA_QUEUE_INDEX, queueIndex)
            putExtra(YouTubeBackgroundService.EXTRA_PLAYER_STATE, state)
            putExtra(YouTubeBackgroundService.EXTRA_IS_PLAYING, state == 1)
        }

        if (state == 1 && !youtubeBackgroundServiceStarted) {
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
            youtubeBackgroundServiceStarted = true
        } else if (youtubeBackgroundServiceStarted) {
            startService(serviceIntent)
        }
    }

    private fun requestYouTubePlayback(forcePlaybackRecovery: Boolean = false) {
        if (webView == null || isFinishing || isDestroyed) return
        val requestGeneration = ++playbackRequestGeneration
        // Use the same guarded force-resume path for explicit PiP Play and returning
        // to the app after PiP. Normal Play requests remain non-disruptive.
        val playbackCommand = when {
            forcePlaybackRecovery -> "force-resume"
            isInPictureInPictureMode -> "resume"
            else -> "play"
        }
        if (isInPictureInPictureMode) {
            resumeVideoAfterPipEntry = true
            // Keep Chromium's media and JavaScript timers resumed when the PiP
            // transport action arrives while the Activity is backgrounded.
            webView?.onResume()
            webView?.resumeTimers()
        }
        fun attempt(remaining: Int) {
            if (requestGeneration != playbackRequestGeneration ||
                isFinishing || isDestroyed || webView == null
            ) return
            webView?.evaluateJavascript(youtubePlaybackScript(playbackCommand)) { result ->
                if (requestGeneration != playbackRequestGeneration) return@evaluateJavascript
                // The JS command returns requested-api/button/video when a playback
                // request was successfully dispatched but the media element has not yet
                // changed state. Treat these as accepted; retrying them as failures sent
                // up to six overlapping play commands and caused audio stutter/freeze.
                val accepted = result?.contains("playing") == true ||
                    result?.contains("buffering") == true ||
                    result?.contains("requested-api") == true ||
                    result?.contains("requested-button") == true ||
                    result?.contains("requested-video") == true ||
                    result?.contains("restart-requested") == true
                // Recheck PiP once after the transition, but stop after a media-clock
                // recovery so a second play command cannot interrupt the restarted stream.
                val didRestartStalledMedia = result?.contains("restart-requested") == true
                val shouldRetry = if (playbackCommand == "resume") {
                    remaining > 0 && !didRestartStalledMedia
                } else {
                    playbackCommand != "force-resume" && !accepted && remaining > 0
                }
                if (shouldRetry && !isFinishing && !isDestroyed &&
                    requestGeneration == playbackRequestGeneration
                ) {
                    playbackPositionHandler.postDelayed({ attempt(remaining - 1) }, 750L)
                }
            }
        }
        // PiP gets two bounded resume attempts; normal playback retries only on rejection.
        attempt(if (playbackCommand == "resume") 1 else 5)
    }

    /**
     * Explicit voice actions are idempotent: "play" always plays and "pause" always pauses.
     * The request is held until the embedded player reports ready.
     */
    private fun executePendingAiAction() {
        val action = pendingAiAction ?: return
        if (!youtubePlayerReady || isFinishing || isDestroyed) return
        pendingAiAction = null
        // Prevent a one-shot voice action from running again after a configuration change.
        intent.removeExtra(EXTRA_AI_ACTION)

        when (action) {
            // Reopening the compact YouTube player destroys/recreates the WebView
            // after Back, so the mobile site's video element may exist before it
            // is ready to accept play(). Retry instead of swallowing the first
            // rejected play() promise and leaving the play button ineffective.
            "play" -> requestYouTubePlayback()
            "pause" -> pauseYouTubePlayback()
            "next" -> {
                if (queue.size > 1) {
                    playNext()
                } else {
                    Toast.makeText(this, "Hãy thêm video vào hàng đợi để phát bài tiếp theo.", Toast.LENGTH_SHORT).show()
                }
            }
            "previous" -> {
                if (queue.size > 1) {
                    playPrevious()
                } else {
                    Toast.makeText(this, "Chưa có video trước trong hàng đợi.", Toast.LENGTH_SHORT).show()
                }
            }
            "shuffle_on" -> if (!youtubeShuffleEnabled) toggleYoutubeShuffle()
            "shuffle_off" -> if (youtubeShuffleEnabled) toggleYoutubeShuffle()
            "repeat_off" -> setYoutubeRepeatMode(0)
            "repeat_all" -> setYoutubeRepeatMode(1)
            "repeat_one" -> setYoutubeRepeatMode(2)
            "favorite" -> if (!isFavorite()) toggleFavorite()
            "watch_later" -> if (!isWatchLater()) toggleWatchLater()
        }
    }

    private fun setYoutubeRepeatMode(mode: Int) {
        youtubeRepeatMode = mode.coerceIn(0, 2)
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit()
            .putInt("youtube_repeat_mode", youtubeRepeatMode)
            .apply()
        updateQueueModeButtons()
    }

    private fun updatePlaybackButton() {
        if (youtubePlayerReady) {
            // Shared state lets the PRO mini-player send play/pause to YouTube,
            // rather than accidentally toggling the separate Media3 music player.
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                .edit()
                .putBoolean("youtube_is_playing", youtubePlayerState == 1)
                .apply()
        }
        if (::playPauseButton.isInitialized) {
            playPauseButton.text = when {
                !youtubePlayerReady -> "…"
                youtubePlayerState == 1 -> "⏸"
                else -> "▶"
            }
        }

        root.findViewWithTag<TextView>("youtube_playback_status")?.apply {
            val status = when {
                !youtubePlayerReady -> "●  ĐANG KẾT NỐI YOUTUBE" to AndroidColor.rgb(171, 175, 188)
                youtubePlayerState == 1 -> "●  ĐANG PHÁT" to AndroidColor.rgb(143, 214, 148)
                youtubePlayerState == 2 -> "Ⅱ  ĐANG TẠM DỪNG" to AndroidColor.rgb(255, 198, 109)
                youtubePlayerState == 3 -> "◌  ĐANG TẢI VIDEO" to AndroidColor.rgb(141, 238, 255)
                youtubePlayerState == 0 -> "✓  VIDEO ĐÃ KẾT THÚC" to AndroidColor.rgb(171, 175, 188)
                youtubePlayerState == 5 -> "●  VIDEO ĐÃ SẴN SÀNG" to AndroidColor.rgb(141, 238, 255)
                else -> "●  TRÌNH PHÁT YOUTUBE" to AndroidColor.rgb(171, 175, 188)
            }
            text = status.first
            setTextColor(status.second)
        }
    }

    private fun updateActionState() {
        if (::favoriteButton.isInitialized) favoriteButton.text = if (isFavorite()) "♥\nYêu thích" else "♡\nYêu thích"
        if (::watchLaterButton.isInitialized) watchLaterButton.text = if (isWatchLater()) "✓\nXem sau" else "🔖\nXem sau"

        val canNavigate = queue.size > 1
        if (::previousButton.isInitialized) {
            previousButton.isEnabled = canNavigate && (
                youtubeShuffleEnabled || queueIndex > 0 || youtubeRepeatMode == 1
            )
        }
        if (::nextButton.isInitialized) {
            nextButton.isEnabled = canNavigate && (
                youtubeShuffleEnabled || queueIndex < queue.lastIndex || youtubeRepeatMode == 1
            )
        }
        updatePlaybackButton()
    }
    private fun createPlayer() {
        removePlayer()
        pageErrorVisible = false
        youtubePlayerReady = false
        youtubePlayerState = -1
        restoredPositionVideoId = null
        positionRestoreAttempts = 0
        lastHandledEndedVideoId = null

        val player = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            // Bound renderer priority reduces WebView renderer eviction while the
            // user-started media session continues in the background.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                allowContentAccess = true
                allowFileAccess = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            CookieManager.getInstance().flush()

            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
                runCatching {
                    val integrityConfig = WebViewMediaIntegrityApiStatusConfig.Builder(
                        WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED
                    )
                        .addOverrideRule(
                            "https://www.youtube.com",
                            WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED
                        )
                        .addOverrideRule(
                            "https://*.youtube.com",
                            WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED
                        )
                        .build()
                    WebSettingsCompat.setWebViewMediaIntegrityApiStatus(
                        settings,
                        integrityConfig
                    )
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!pageErrorVisible) {
                        errorView?.visibility = View.GONE
                    }
                    loadingBar?.progress = 100
                    loadingBar?.visibility = View.GONE
                    view.requestLayout()
                    view.invalidate()
                    // Preview experiment: use YouTube's mobile website in the app's
                    // Chromium WebView instead of the IFrame API player.
                    view.evaluateJavascript("""
                        (function(){
                          // Keep state tracking and transport commands bound to the same
                          // active YouTube video element to avoid controlling a stale/hidden video.
                          function findActiveVideo() {
                            var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                            function visible(video) {
                              if (!video || !video.isConnected) return false;
                              var rect = video.getBoundingClientRect();
                              if (rect.width < 2 || rect.height < 2) return false;
                              var node = video;
                              while (node && node.nodeType === 1 && node !== document.documentElement) {
                                var style = window.getComputedStyle(node);
                                if (style.display === 'none' || style.visibility === 'hidden' ||
                                    Number(style.opacity || 1) === 0) return false;
                                node = node.parentElement;
                              }
                              return true;
                            }
                            var rendered = videos.filter(visible);
                            // Always bind commands/state to YouTube's real primary video before
                            // considering other video elements (ads, previews or hidden players).
                            // A secondary element can still be "playing" while the main video is
                            // paused during the Android PiP transition; score-only selection then
                            // targets the wrong element and leaves the PiP image frozen.
                            var primary = rendered.find(function(video) {
                              return video.classList.contains('html5-main-video') ||
                                !!video.closest('#movie_player') ||
                                !!video.closest('.html5-video-player') ||
                                !!video.closest('ytm-player');
                            });
                            if (primary) return primary;
                            function score(video) {
                              var rect = video.getBoundingClientRect();
                              var value = rect.width * rect.height;
                              if (!video.paused && !video.ended) value += 100000000;
                              if (video.readyState >= 2) value += 1000000;
                              if (video.currentTime > 0) value += 100000;
                              return value;
                            }
                            rendered.sort(function(a,b){ return score(b) - score(a); });
                            return rendered[0] || videos[0] || null;
                          }
                          window.__ngocSiFindActiveVideo = findActiveVideo;
                          // PiP presentation changes only the webpage scroll position. Never
                          // add CSS classes or hide YouTube DOM nodes around the playing video.
                          window.__ngocSiPipOriginalScrollY = null;
                          window.__ngocSiFocusVideoForPip = function(enabled) {
                            try {
                              if (!enabled) {
                                var saved = window.__ngocSiPipOriginalScrollY;
                                window.__ngocSiPipOriginalScrollY = null;
                                if (typeof saved === 'number') window.scrollTo(0, saved);
                                return 'restored';
                              }
                              if (typeof window.__ngocSiPipOriginalScrollY !== 'number') {
                                window.__ngocSiPipOriginalScrollY = window.scrollY || window.pageYOffset || 0;
                              }
                              var video = findActiveVideo();
                              if (!video || !video.isConnected) return 'wait';
                              var rect = video.getBoundingClientRect();
                              if (rect.width < 2 || rect.height < 2) return 'wait';
                              var targetY = (window.scrollY || window.pageYOffset || 0) + rect.top;
                              window.scrollTo(0, Math.max(0, targetY));
                              return 'focused';
                            } catch (_) {
                              return 'wait';
                            }
                          };

                          // Install the Android bridge only after YouTube has created a video
                          // element. A previous edit left a stray closing brace here, making the
                          // entire script fail to parse: onReady/state callbacks never ran, so
                          // PiP parameters and the background media service were never enabled.
                          function installNgocSiBridge(retries) {
                            if (window.__ngocSiBridgeInstalled) return;
                            var initialVideo = findActiveVideo();
                            if (!initialVideo) {
                              if (retries < 30) {
                                setTimeout(function(){ installNgocSiBridge(retries + 1); }, 700);
                              }
                              return;
                            }
                            window.__ngocSiBridgeInstalled = true;
                            try { AndroidBridge.onReady(); } catch(e) {}
                            var lastState = -99;
                            var lastVideo = null;
                            setInterval(function(){
                              var e = findActiveVideo();
                              if (!e) return;
                              var videoChanged = e !== lastVideo;
                              if (videoChanged) {
                                lastVideo = e;
                                lastState = -99;
                              }
                              // Track actual media-clock progress separately from the
                              // paused/playing flag; WebView may keep that flag true even when
                              // the PiP video surface has stopped advancing.
                              var mediaTime = Number(e.currentTime) || 0;
                              var trackedTime = Number(window.__ngocSiVideoLastTime);
                              var mediaAdvancedThisTick = false;
                              if (videoChanged || !Number.isFinite(trackedTime)) {
                                // PiP can replace the DOM node while its playback clock is frozen.
                                // Reset the movement proof, but do not treat the replacement as progress.
                                window.__ngocSiVideoLastProgressAt = Date.now();
                                window.__ngocSiVideoHasAdvancedSinceChange = false;
                              } else if (mediaTime > trackedTime + 0.04) {
                                // Only forward media-clock movement proves playback is progressing;
                                // seeking backward alone must not clear a stall.
                                mediaAdvancedThisTick = true;
                                window.__ngocSiVideoLastProgressAt = Date.now();
                                window.__ngocSiVideoHasAdvancedSinceChange = true;
                              }
                              window.__ngocSiVideoLastTime = mediaTime;
                              var state = e.ended ? 0 : (e.paused ? 2 : 1);
                              var shouldResumeForRecovery = false;
                              try {
                                shouldResumeForRecovery = AndroidBridge.shouldResumeAfterPip() === true;
                              } catch (_) {}
                              var progressAge = Date.now() -
                                Number(window.__ngocSiVideoLastProgressAt || Date.now());
                              // Let ordinary YouTube buffering recover naturally. A still-playing
                              // element can pause its media clock briefly while the stream refills;
                              // recovering at 2.5s restarted healthy streams and caused audible stutter.
                              // A truly paused element still gets the shorter transition-recovery path.
                              var stalledInRecovery = shouldResumeForRecovery && !e.ended &&
                                e.readyState >= 2 &&
                                ((e.paused && progressAge >= 1200) ||
                                 (!e.paused && progressAge >= 4000));
                              // A frozen currentTime with paused=false is not a real
                              // pause. Keep the reported playback state stable and send
                              // the stall signal separately, otherwise the UI flaps between
                              // Playing and Paused while the decoder is merely buffering.
                              if (stalledInRecovery && e.paused) state = 2;
                              if (state === 1 && mediaAdvancedThisTick) {
                                window.__ngocSiPipRecoverySent = false;
                                if (window.__ngocSiForceStateRefresh) {
                                  window.__ngocSiForceStateRefresh = false;
                                  lastState = -99;
                                  try { AndroidBridge.onRecoveryClockAdvanced(); } catch (_) {}
                                }
                              }
                              if (state !== lastState) {
                                lastState = state;
                                try { AndroidBridge.onStateChanged(state); } catch(e) {}
                              }
                              // A transition can finish with paused=false while currentTime
                              // remains frozen. Notify native only once per stall; native code
                              // publishes the correct PiP Play action and uses a bounded retry.
                              if (stalledInRecovery && !window.__ngocSiPipRecoverySent) {
                                window.__ngocSiPipRecoverySent = true;
                                try { AndroidBridge.onPipPlaybackStalled(!!e.paused); } catch (_) {}
                              }
                              // This loop only reports state. Playback commands are sent by
                              // one bounded Android retry path or an explicit user tap. Keeping
                              // a second auto-play loop here repeatedly restarted media in PiP,
                              // producing audio stutter and racing the native Play/Pause action.
                            }, 500);
                          }
                          installNgocSiBridge(0);
                        })();
                    """.trimIndent(), null)
                    // A queue navigation can finish while PiP is already active.
                    // Move the page viewport to the active video, without DOM/CSS surgery.
                    if (isInPictureInPictureMode && customView == null) {
                        updateWebViewPipPresentation(true)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        showError("Không tải được trình phát YouTube.")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        showError("YouTube từ chối tải trình phát (HTTP " + errorResponse.statusCode + ").")
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    showError(
                        if (detail.didCrash()) {
                            "Trình render WebView gặp lỗi. Hãy thử lại."
                        } else {
                            "Android đã dừng bộ render WebView. Hãy thử lại."
                        }
                    )
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    loadingBar?.progress = newProgress
                    loadingBar?.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    val message = consoleMessage.message().orEmpty()
                    val lower = message.lowercase()
                    if (lower.contains("error 153") ||
                        lower.contains("code: 153") ||
                        lower.contains("missing referer") ||
                        lower.contains("missing referrer")
                    ) {
                        showError("YouTube không xác thực được trình phát (Error 153). Hãy bấm THỬ LẠI.")
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onShowCustomView(
                    view: View,
                    callback: CustomViewCallback
                ) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }

                    customView = view
                    customViewCallback = callback
                    playerContainer.visibility = View.GONE
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    enterFullscreen()
                }

                override fun onHideCustomView() {
                    exitFullscreen(notifyCallback = false)
                }
            }

            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(youtubeJsBridge, "AndroidBridge")

            // Preview experiment: load YouTube's mobile site inside this WebView.
            // This keeps navigation inside NGỌC SĨ MUSIC and uses the site's own
            // Chromium-compatible HTML5 player controls. Background playback is not guaranteed.
            val safeId = sanitizeVideoId(videoId)
            loadUrl("https://m.youtube.com/watch?v=" + safeId)
        }

        webView = player
        playerContainer.addView(
            player,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        if (::titleView.isInitialized) titleView.text = title
        if (::channelView.isInitialized) channelView.text = channel
        updateActionState()
        updateQueueButton()
        updateQueueModeButtons()
        saveLastPlayedState()
    }

    private fun updateQueueButton() {
        val positionText = (queueIndex + 1).coerceAtLeast(1).toString() + "/" + queue.size.coerceAtLeast(1)
        root.findViewWithTag<Button>("queue_button")?.text = "☷  HÀNG ĐỢI  •  " + positionText
        root.findViewWithTag<TextView>("youtube_queue_hint")?.text = "Hàng đợi YouTube • " + positionText
        root.findViewWithTag<ProgressBar>("queue_progress")?.apply {
            max = queue.size.coerceAtLeast(1)
            progress = (queueIndex + 1).coerceIn(0, max)
        }
    }

    private fun showError(message: String, allowExternal: Boolean = false) {
        pageErrorVisible = true
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(AndroidColor.BLACK)

                val messageView = TextView(this@YouTubePlayerActivity).apply {
                    setTextColor(AndroidColor.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }

                val retry = Button(this@YouTubePlayerActivity).apply {
                    text = "THỬ LẠI"
                    tag = "retry_button"
                    setOnClickListener {
                        errorView?.visibility = View.GONE
                        createPlayer()
                    }
                }

                val external = Button(this@YouTubePlayerActivity).apply {
                    text = "MỞ TRÊN YOUTUBE"
                    tag = "external_button"
                    setOnClickListener {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=" + sanitizeVideoId(videoId))
                        )
                        runCatching {
                            startActivity(intent)
                        }.onFailure {
                            errorMessageFallback()
                        }
                    }
                }

                addView(messageView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(retry, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                })
                addView(external, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                })
                tag = messageView
            }

            root.addView(
                errorView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        (errorView?.tag as? TextView)?.text = message
        errorView?.findViewWithTag<View>("retry_button")?.visibility = View.VISIBLE
        errorView?.findViewWithTag<View>("external_button")?.visibility =
            if (allowExternal) View.VISIBLE else View.GONE
        errorView?.visibility = View.VISIBLE
    }

    private fun errorMessageFallback() {
        // Keep the in-app error surface visible when Android has no external
        // handler for the YouTube URL.
        (errorView?.tag as? TextView)?.text =
            "Không mở được YouTube bên ngoài trên thiết bị này."
    }

    override fun onResume() {
        super.onResume()
        // Let wake-word commands follow the currently foregrounded YouTube player.
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit().putBoolean("youtube_player_foreground", true).apply()
        // Chromium can leave the HTML5 video element paused after screen-off/unlock.
        // Resume the existing WebView first; never reload the video just to recover.
        webView?.onResume()
        webView?.resumeTimers()
        updatePipParamsIfSupported()
        if (youtubePlayerReady && webView != null) {
            startPlaybackPositionSaver()
            val wasPlaying = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                .getBoolean("youtube_playback_is_playing", false)
            if (youtubePlayerState == 1) {
                syncYouTubeBackgroundPlayback(youtubePlayerState)
            } else if (wasPlaying && !isInPictureInPictureMode &&
                !resumeVideoAfterPipEntry && !resumeVideoAfterPipExit
            ) {
                // Ordinary screen-off/unlock recovery only. PiP exit has its own single,
                // force-resume path; running both timers together can stutter the stream.
                webView?.postDelayed({
                    // onResume can run before onPictureInPictureModeChanged(false).
                    // Re-check both PiP recovery guards when the timer fires, otherwise
                    // this normal retry races the dedicated PiP-exit force-resume and
                    // produces a pause/play stutter or leaves the page paused.
                    if (!isFinishing && !isDestroyed && !isInPictureInPictureMode &&
                        !resumeVideoAfterPipEntry && !resumeVideoAfterPipExit &&
                        youtubePlayerReady && webView != null
                    ) {
                        requestYouTubePlayback()
                    }
                }, 500L)
            }
        }
    }

    private fun enterYouTubePip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            Toast.makeText(this, "Thiết bị không hỗ trợ PiP.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isFinishing || isDestroyed || webView == null) {
            Toast.makeText(this, "Trình phát YouTube chưa sẵn sàng.", Toast.LENGTH_SHORT).show()
            return
        }
        // Capture playback intent BEFORE asking Android to enter PiP. The WebView may
        // report a transient pause during the transition; if we wait until
        // onPictureInPictureModeChanged(), that callback can already have overwritten
        // the last-playing preference and the video then freezes as a still frame.
        val wasPlaying = youtubePlayerState == 1 ||
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                .getBoolean("youtube_playback_is_playing", false)
        resumeVideoAfterPipEntry = wasPlaying
        if (wasPlaying) {
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                .putBoolean("youtube_playback_is_playing", true)
                .apply()
        }
        webView?.onResume()
        webView?.resumeTimers()
        runCatching {
            updatePipParamsIfSupported()
            enterPictureInPictureMode(buildPipParams())
        }.onFailure {
            resumeVideoAfterPipEntry = false
            Toast.makeText(this, "Không mở được PiP. Kiểm tra quyền Hình trong hình của ứng dụng.", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        // Tell Android which on-screen region is the actual video surface. This
        // avoids animating/cropping the whole watch page into the PiP window.
        if (::playerContainer.isInitialized) {
            val sourceView = customView ?: playerContainer
            val sourceRect = Rect()
            if (sourceView.visibility == View.VISIBLE &&
                sourceView.getGlobalVisibleRect(sourceRect) &&
                sourceRect.width() > 0 && sourceRect.height() > 0
            ) {
                builder.setSourceRectHint(sourceRect)
            }
        }

        // Give the PiP window its own explicit play/pause action. Without this,
        // Android may expose the app's active Media3 session controls, which belong
        // to local music rather than this WebView-based YouTube player.
        if (youtubePlayerReady) {
            val playing = youtubePlayerState == 1
            val actionIntent = Intent(this, YouTubePipActionReceiver::class.java).apply {
                action = ACTION_PIP_PLAY_PAUSE
                putExtra(EXTRA_PIP_COMMAND, if (playing) "pause" else "play")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                7101,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val iconRes = if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            val label = if (playing) "Tạm dừng YouTube" else "Phát YouTube"
            builder.setActions(listOf(
                RemoteAction(Icon.createWithResource(this, iconRes), label, label, pendingIntent)
            ))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                youtubePlayerReady && (youtubePlayerState == 1 || resumeVideoAfterPipEntry)
            )
        }
        return builder.build()
    }

    private fun updatePipParamsIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            // Start a fresh, bounded recovery budget only for a new PiP entry.
            // Transient "playing" callbacks must never reset it.
            pipRecoveryAttempts = 0
            loadingBar?.visibility = View.GONE
            pipScrollY = contentScrollView?.scrollY ?: 0

            // Preserve playback intent before WebView/Chromium reports a transition pause.
            if (!resumeVideoAfterPipEntry) {
                val lastObservedPlaying = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                    .getBoolean("youtube_playback_is_playing", false)
                resumeVideoAfterPipEntry = youtubePlayerState == 1 || lastObservedPlaying
            }

            val fullscreenVideo = customView
            if (fullscreenVideo != null) {
                // CRITICAL: WebChromeClient's custom view can be the actual video SurfaceView.
                // Do not call exitFullscreen() here: that removes the video surface and leaves
                // PiP with a black/empty inline WebView. Keep the same view attached to root.
                contentScrollView?.visibility = View.GONE
                fullscreenVideo.visibility = View.VISIBLE
                (fullscreenVideo.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.width = FrameLayout.LayoutParams.MATCH_PARENT
                    params.height = FrameLayout.LayoutParams.MATCH_PARENT
                    fullscreenVideo.layoutParams = params
                }
                fullscreenVideo.requestLayout()
            } else {
                // Inline mode: keep the WebView attached to its original parent so Chromium's
                // video compositor surface is not detached during PiP.
                val content = contentScrollView?.getChildAt(0) as? ViewGroup
                content?.let { column ->
                    for (i in 0 until column.childCount) {
                        val child = column.getChildAt(i)
                        child.visibility = if (child === playerContainer) View.VISIBLE else View.GONE
                    }
                }
                contentScrollView?.visibility = View.VISIBLE
                contentScrollView?.scrollTo(0, 0)
                contentScrollView?.clipChildren = true
                contentScrollView?.clipToPadding = true
                playerContainer.visibility = View.VISIBLE
                val pipWindowWidthPx = newConfig.screenWidthDp
                    .takeIf { it > 0 }
                    ?.let { (it * resources.displayMetrics.density).toInt() }
                    ?: 0
                // Keep the WebView's existing video surface/layout intact during the PiP
                // transition. Resizing the nested WebView container and scrolling the page
                // at the same time can detach Chromium's hardware video layer, yielding a
                // black PiP window with no audio. The YouTube watch page places the player
                // at the top, so the 16:9 PiP viewport naturally clips to the video region.
                webView?.onResume()
                webView?.resumeTimers()
                // Bring the actual YouTube video into the PiP viewport immediately.
                // The outer app scroll and the mobile YouTube page scroll are separate;
                // restore the video region before the bounded playback reassertion.
                contentScrollView?.post {
                    contentScrollView?.scrollTo(0, playerContainer.top.coerceAtLeast(0))
                }
                updateWebViewPipPresentation(true)
            }

            // Do not force a pause/play cycle merely because PiP opened. The media-clock
            // watchdog above recovers only if the stream actually stalls; an unconditional
            // restart here was competing with that watchdog and caused audible stutter.
        } else {
            // Keep the pre-exit playback intent before clearing the PiP recovery flag.
            // Without this, returning from PiP can leave the WebView video frozen/paused
            // even though the user never pressed Pause.
            val shouldResumeAfterPipExit = resumeVideoAfterPipEntry
            resumeVideoAfterPipEntry = false
            resumeVideoAfterPipExit = shouldResumeAfterPipExit
            val content = contentScrollView?.getChildAt(0) as? ViewGroup
            content?.let { column ->
                for (i in 0 until column.childCount) {
                    column.getChildAt(i).visibility = View.VISIBLE
                }
            }
            contentScrollView?.visibility = View.VISIBLE
            contentScrollView?.post { contentScrollView?.scrollTo(0, pipScrollY) }

            if (customView != null) {
                // If PiP started from YouTube fullscreen, keep the same video surface.
                playerContainer.visibility = View.GONE
                customView?.visibility = View.VISIBLE
            } else {
                playerContainer.visibility = View.VISIBLE
                webView?.onResume()
                webView?.resumeTimers()
                // Avoid moving/resizing the WebView surface during Android's PiP exit
                // animation. Restore the normal page viewport only after the window settles.
                webView?.postDelayed({
                    if (!isFinishing && !isDestroyed && !isInPictureInPictureMode) {
                        updateWebViewPipPresentation(false)
                        resizePlayerContainerForPip(false)
                    }
                }, 350L)
            }

            // Restore playback intent only if it was active before PiP closed. Force recovery
            // after the layout transition so stale "playing" flags cannot swallow the request.
            if (shouldResumeAfterPipExit) {
                // First recovery waits for Android's PiP-exit transition to settle.
                webView?.postDelayed({
                    if (!isFinishing && !isDestroyed && !isInPictureInPictureMode &&
                        resumeVideoAfterPipExit && webView != null
                    ) {
                        webView?.onResume()
                        webView?.resumeTimers()
                        requestYouTubePlayback(forcePlaybackRecovery = true)
                    }
                }, 750L)
                // A rejected play() promise can leave the WebView reporting a stale
                // "playing" state without advancing currentTime. Give that failure one
                // final bounded retry; the media-clock bridge clears this guard as soon
                // as playback really advances, and explicit Pause clears it immediately.
                webView?.postDelayed({
                    if (!isFinishing && !isDestroyed && !isInPictureInPictureMode &&
                        resumeVideoAfterPipExit && webView != null
                    ) {
                        webView?.onResume()
                        webView?.resumeTimers()
                        requestYouTubePlayback(forcePlaybackRecovery = true)
                    }
                }, 2400L)
            }
        }
        updatePipParamsIfSupported()
    }

    /**
     * Focus the inline video's viewport for PiP using page scrolling only. CSS/DOM mutation
     * around an active HTML5 video can invalidate Chromium's compositor surface.
     */
    private fun updateWebViewPipPresentation(inPip: Boolean, attempt: Int = 0) {
        if (customView != null) return
        val player = webView ?: return
        val script = if (inPip) {
            """(function(){
                try {
                  if (typeof window.__ngocSiFocusVideoForPip !== 'function') return 'wait';
                  return window.__ngocSiFocusVideoForPip(true);
                } catch (_) { return 'wait'; }
            })();"""
        } else {
            """(function(){
                try {
                  if (typeof window.__ngocSiFocusVideoForPip !== 'function') return 'restored';
                  return window.__ngocSiFocusVideoForPip(false);
                } catch (_) { return 'restored'; }
            })();"""
        }
        player.postDelayed({
            if (isFinishing || isDestroyed || (inPip != isInPictureInPictureMode)) return@postDelayed
            player.evaluateJavascript(script) { result ->
                if (inPip && result?.contains("wait") == true && attempt < 6 &&
                    !isFinishing && !isDestroyed && isInPictureInPictureMode
                ) {
                    updateWebViewPipPresentation(true, attempt + 1)
                }
            }
        }, if (inPip) 220L else 0L)
    }

    /**
     * Resize the existing video container without detaching WebView from Chromium's
     * video surface. In PiP, derive the video height from the actual small-window width.
     */
    private fun resizePlayerContainerForPip(
        inPip: Boolean,
        pipWindowWidthPx: Int = 0,
        remainingAttempts: Int = 8
    ) {
        if (!::playerContainer.isInitialized) return
        playerContainer.post {
            // A View can still report its previous full-screen width immediately
            // after the Activity enters PiP. Prefer the PiP Configuration width,
            // then the container's parent/root, and only then the stale child width.
            val width = if (inPip) {
                pipWindowWidthPx.takeIf { it > 0 }
                    ?: contentScrollView?.width?.takeIf { it > 0 }
                    ?: root.width.takeIf { it > 0 }
                    ?: playerContainer.width
            } else {
                resources.displayMetrics.widthPixels.takeIf { it > 0 }
                    ?: root.width.takeIf { it > 0 }
                    ?: playerContainer.width
            }
            if (width <= 0 && remainingAttempts > 0 && !isFinishing && !isDestroyed) {
                playerContainer.postDelayed({
                    resizePlayerContainerForPip(
                        inPip = inPip,
                        pipWindowWidthPx = pipWindowWidthPx,
                        remainingAttempts = remainingAttempts - 1
                    )
                }, 80L)
                return@post
            }
            val params = playerContainer.layoutParams as? LinearLayout.LayoutParams ?: return@post
            params.height = if (inPip) {
                (width * 9f / 16f).toInt().coerceAtLeast(dp(90))
            } else {
                (resources.displayMetrics.widthPixels * 9f / 16f).toInt().coerceAtLeast(dp(200))
            }
            playerContainer.layoutParams = params
            playerContainer.requestLayout()
            webView?.requestLayout()
            playerContainer.invalidate()
            contentScrollView?.requestLayout()
        }
    }

    override fun onUserLeaveHint() {
        // Capture playback before Android/WebView can report a pause caused by
        // PiP resizing. The JS state poll can lag by up to one interval, so use
        // the last observed player state as a fallback, but never override an
        // explicit paused state reported by the player.
        val lastObservedPlaying = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .getBoolean("youtube_playback_is_playing", false)
        // The WebView bridge state can lag behind the HTML5 video during a
        // PiP transition. Prefer the persisted last-playing signal as a fallback,
        // and do not require the bridge-ready flag just to remember playback intent.
        resumeVideoAfterPipEntry = youtubePlayerState == 1 || lastObservedPlaying
        // Persist playback state, then use PiP as a visible fallback on Android 8–11.
        saveLastPlayedState()
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            youtubePlayerReady && youtubePlayerState == 1 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    override fun onPause() {
        // Keep WebView media lifecycle untouched here. Calling WebView.onPause()
        // would explicitly suspend WebView media/JS and can make recovery worse.
        saveCurrentPlaybackPosition()
        saveLastPlayedState()
        stopPlaybackPositionSaver()
        super.onPause()
    }

    override fun onStop() {
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit().putBoolean("youtube_player_foreground", false).apply()
        // Keep the last queue/index and playback position durable across process
        // pressure and relaunch. This does not keep YouTube playing in background.
        saveCurrentPlaybackPosition()
        saveLastPlayedState()
        stopPlaybackPositionSaver()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_VIDEO_ID, videoId)
        outState.putString(EXTRA_TITLE, title)
        outState.putString(EXTRA_CHANNEL, channel)
        outState.putInt(EXTRA_QUEUE_INDEX, queueIndex.coerceAtLeast(0))
        outState.putString(EXTRA_QUEUE_JSON, buildQueueJson())
        outState.putBoolean("youtube_shuffle", youtubeShuffleEnabled)
        outState.putInt("youtube_repeat_mode", youtubeRepeatMode)
        super.onSaveInstanceState(outState)
    }

    private fun removePlayer() {
        webView?.let { view ->
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.removeAllViews()
                view.destroy()
            } catch (_: Exception) {
            }
        }
        webView = null
        playerContainer.takeIf { ::playerContainer.isInitialized }?.removeAllViews()
    }

    private fun enterFullscreen() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen(notifyCallback: Boolean = true) {
        customView?.let { root.removeView(it) }
        customView = null
        if (notifyCallback) {
            customViewCallback?.onCustomViewHidden()
        }
        customViewCallback = null
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerContainer.visibility = View.VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun sanitizeVideoId(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (isFinishing) {
            stopService(
                Intent(this, YouTubeBackgroundService::class.java)
                    .setAction(YouTubeBackgroundService.ACTION_STOP)
            )
            // Finishing destroys the WebView, so it cannot still be considered
            // actively playing. Keep YouTube selected as the user's last source,
            // however: the compact play button can reopen the saved video and
            // resume it instead of unexpectedly switching to the local player.
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                .putBoolean("youtube_playback_is_playing", false)
                .putBoolean("youtube_active_source", true)
                .apply()
        }
        if (customView != null) exitFullscreen(notifyCallback = false)
        removePlayer()
        thumbnailExecutor.shutdownNow()
        youtubeThumbnailCache.evictAll()
        stopPlaybackPositionSaver()
        if (pipActionReceiverRegistered) {
            runCatching { unregisterReceiver(pipActionReceiver) }
            pipActionReceiverRegistered = false
        }
        loadingBar = null
        super.onDestroy()
    }
}

/**
 * Receives the PiP transport tap without launching/bringing the Activity to the
 * foreground, then forwards it to the still-live PiP Activity using a package-
 * scoped broadcast.
 */
class YouTubePipActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent?) {
        if (intent?.action != "com.ngocsi.music.action.YOUTUBE_PIP_PLAY_PAUSE") return
        context.sendBroadcast(
            Intent("com.ngocsi.music.action.YOUTUBE_PIP_PLAY_PAUSE_INTERNAL")
                .setPackage(context.packageName)
                .putExtra("youtube_pip_command", intent.getStringExtra("youtube_pip_command") ?: "toggle")
        )
    }
}
package com.ngocsi.music

import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.ByteArrayInputStream
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
    }

    private lateinit var root: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private var webView: WebView? = null
    private var inAppBackgroundPlaybackEnabled = false

    /**
     * A responsive inline video host. Its height is derived from the measured width,
     * not displayMetrics, so Android and the WebView always share the same 16:9 box.
     */
    private inner class InlinePlayerFrameLayout(context: android.content.Context) :
        FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (fullscreenLayoutActive &&
                View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY
            ) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            if (width > 0) {
                val aspectHeight = (width * 9f / 16f).toInt().coerceAtLeast(1)
                // A 16:9 frame based only on the long landscape width can be taller than
                // the phone display. Bound its height to the current short-edge viewport;
                // the WebView then calculates a matching 16:9 width instead of overflowing.
                val landscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val screenHeightPx = (resources.configuration.screenHeightDp *
                    resources.displayMetrics.density).toInt()
                val measuredHeight = if (landscape && screenHeightPx > 0) {
                    minOf(aspectHeight, screenHeightPx)
                } else {
                    aspectHeight
                }
                val exactHeight = View.MeasureSpec.makeMeasureSpec(
                    measuredHeight.coerceAtLeast(1), View.MeasureSpec.EXACTLY
                )
                super.onMeasure(widthMeasureSpec, exactHeight)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    /**
     * WebView normally receives GONE when its Activity goes to the background.
     * Chromium may then suspend the HTML5 media element. When the user enables
     * background audio, keep the renderer's window-visibility state alive.
     */
    private inner class BackgroundAudioWebView(context: android.content.Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            if (inAppBackgroundPlaybackEnabled) {
                if (visibility != View.GONE) {
                    super.onWindowVisibilityChanged(View.VISIBLE)
                }
                return
            }
            super.onWindowVisibilityChanged(visibility)
        }
    }
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    // The mobile YouTube page may request a Chromium custom view before our in-WebView
    // fullscreen CSS is applied. Ignore the matching hide callback for that handoff.
    private var suppressNextCustomViewHide = false
    private var isCssFullscreen = false
    // Preserve the pre-fullscreen playback state even if YouTube reports PAUSED while rotating.
    private var wasPlayingBeforeFullscreen = false
    private var fullscreenRecoveryGeneration = 0
    // Coalesce duplicate fullscreen-dismiss callbacks so only one recovery cycle runs.
    private var fullscreenExitGeneration = 0
    // Prevent onConfigurationChanged from starting a second recovery cycle for the same fullscreen exit.
    private var fullscreenExitRecoveryPending = false
    // Runs one post-rotation geometry verification; a broken video surface can freeze
    // visually while the HTML5 media clock continues advancing.
    private var fullscreenExitGeometryCheckGeneration = 0
    // Avoid starting a second orientation recovery while the fullscreen-exit recovery
    // and geometry verification are already in progress.
    private var fullscreenExitRecoveryActiveUntilMs = 0L
    // Coalesce orientation/layout syncs so stale delayed callbacks cannot overwrite newer sizes.
    private var inlineLayoutSyncGeneration = 0
    // Track bounded layout/recovery work during CSS fullscreen rotation.
    private var fullscreenViewportSyncGeneration = 0
    // Preserve playback intent while orientation changes temporarily report PAUSED.
    private var resumePlaybackOnNextOrientationChange = false
    private var orientationRecoveryWindowUntilMs = 0L
    // Debounce WebView resize recovery against the native orientation callback.
    private var lastOrientationConfigurationChangeAtMs = 0L
    // Keep explicit user pauses separate from temporary WebView surface/viewport pauses.
    private var explicitUserPause = false
    private var orientationPlaybackRecoveryGeneration = 0
    private var fullscreenLayoutActive = false
    private var contentContainer: LinearLayout? = null
    private var playerScrollView: ScrollView? = null
    private var inlinePlayerLayoutParams: ViewGroup.LayoutParams? = null
    private val fullscreenHiddenChildVisibilities = linkedMapOf<View, Int>()
    private var inlineContentPadding: IntArray? = null
    private var inlineLoadingBarVisibility: Int? = null
    // A single page-reload fallback is allowed per selected video to prevent reload loops.
    private var geometryReloadFallbackAttemptedForCurrentPage = false
    private var recoverAfterPageReload = false
    private var playbackCommandGeneration = 0
    private var videoId: String = ""
    private var title: String = "YouTube"
    private var channel: String = "YouTube"
    private lateinit var titleView: TextView
    private lateinit var channelView: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var moreButton: Button
    private data class QueueItem(val videoId: String, val title: String, val channelTitle: String, val thumbnailUrl: String)
    private val queue = mutableListOf<QueueItem>()
    private var queueIndex = 0
    private var errorView: LinearLayout? = null
    private var loadingBar: ProgressBar? = null
    private var pageErrorVisible = false
    private var youtubePlayerReady = false
    private var youtubePlayerState = -1
    // New play/pause requests invalidate older delayed retries so native and voice
    // controls cannot leave multiple competing playback loops active at the same time.
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
        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                youtubePlayerReady = true
                updatePlaybackButton()
                restoreSavedPlaybackPosition()
                startPlaybackPositionSaver()
                executePendingAiAction()
                if (recoverAfterPageReload) {
                    recoverAfterPageReload = false
                    playbackPositionHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && webView != null) {
                            // The page reload already used the one allowed reload fallback.
                            // Probe and recover once more without starting a reload loop.
                            recoverFullscreenPlayback(allowPageReloadFallback = false)
                        }
                    }, 900L)
                }
            }
        }

        @JavascriptInterface
        fun onViewportPlaybackHandoff() {
            runOnUiThread {
                noteViewportPlaybackHandoff()
            }
        }

        @JavascriptInterface
        fun onExplicitPauseRequested() {
            runOnUiThread {
                markExplicitPauseIntent()
            }
        }

        @JavascriptInterface
        fun onExplicitPlayRequested() {
            runOnUiThread {
                markExplicitPlayIntent()
            }
        }

        @JavascriptInterface
        fun onStateChanged(state: Int) {
            runOnUiThread {
                val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                val isForeground = prefs.getBoolean("youtube_player_foreground", true)
                val keepBackgroundPlayback = prefs.getBoolean(
                    "youtube_background_playback_enabled", false
                )
                // Some WebView/YouTube page lifecycles pause the HTML5 element when the
                // Activity goes behind another app. Keep the media session marked as
                // playing while the user has explicitly enabled in-app background mode,
                // and make a bounded set of recovery attempts instead of a tight retry loop.
                if ((state == 2 || state == 3) && !isForeground && keepBackgroundPlayback &&
                    prefs.getBoolean("youtube_playback_is_playing", false) &&
                    youtubePlayerState == 1
                ) {
                    scheduleBackgroundPlaybackRecovery()
                    return@runOnUiThread
                }
                val orientationHandoffActive =
                    System.currentTimeMillis() <= orientationRecoveryWindowUntilMs ||
                    customView != null || isCssFullscreen || fullscreenExitRecoveryPending
                val playbackIntentStillActive =
                    prefs.getBoolean("youtube_playback_is_playing", false)

                // Rotation can cause WebView to report PAUSED before Android delivers
                // onConfigurationChanged. If that callback clears the last confirmed PLAYING
                // state first, the later orientation recovery has no intent to restore.
                // A PAUSED callback can arrive just before the orientation callback.
                // Preserve intent only for that transition; normal BUFFERING state changes
                // must still reach the media-session/UI state tracker.
                if (state == 2 &&
                    (youtubePlayerState == 1 || youtubePlayerState == 3) &&
                    !explicitUserPause && playbackIntentStillActive
                ) {
                    orientationRecoveryWindowUntilMs = maxOf(
                        orientationRecoveryWindowUntilMs,
                        System.currentTimeMillis() + 5000L
                    )
                    resumePlaybackOnNextOrientationChange = true
                    return@runOnUiThread
                }

                // Do not publish a temporary PAUSED/BUFFERING state to the UI or media
                // session while the viewport is handing off between portrait and landscape.
                if ((state == 2 || state == 3) && orientationHandoffActive &&
                    !explicitUserPause && playbackIntentStillActive
                ) {
                    return@runOnUiThread
                }

                if (state == 1) {
                    explicitUserPause = false
                    orientationPlaybackRecoveryGeneration++
                    backgroundPlaybackRecoveryGeneration++
                    resumePlaybackOnNextOrientationChange = true
                    if (customView != null || isCssFullscreen) {
                        wasPlayingBeforeFullscreen = true
                    }
                    prefs.edit().putBoolean("youtube_playback_is_playing", true).apply()
                } else if (state == 0 || (state == 2 && (!orientationHandoffActive || explicitUserPause))) {
                    // ENDED always clears intent. PAUSED clears it only when it is not a viewport
                    // handoff, or when the user explicitly asked to pause the player.
                    if (state == 0) explicitUserPause = false
                    resumePlaybackOnNextOrientationChange = false
                    if (customView != null || isCssFullscreen) {
                        wasPlayingBeforeFullscreen = false
                    }
                    prefs.edit().putBoolean("youtube_playback_is_playing", false).apply()
                }
                // State 3 (BUFFERING), and transient state 2 during rotation, preserve intent.
                youtubePlayerState = state
                updatePlaybackButton()
                syncYouTubeBackgroundPlayback(state)
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

        @JavascriptInterface
        fun onFullscreenToggle() {
            runOnUiThread {
                if (isFinishing || isDestroyed || webView == null) return@runOnUiThread
                val host = runCatching { Uri.parse(webView?.url.orEmpty()).host.orEmpty() }
                    .getOrDefault("")
                if (host != "youtube.com" && !host.endsWith(".youtube.com")) {
                    return@runOnUiThread
                }
                // Route YouTube's fullscreen control into the same-WebView CSS mode.
                // Avoid moving playback to Chromium's separate custom video surface.
                requestYoutubeFullscreen()
            }
        }
    }

    private val youtubeJsBridge = YoutubeJsBridge()
    private var youtubeBackgroundControlReceiverRegistered = false
    private val youtubeBackgroundControlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != YouTubeBackgroundService.ACTION_CONTROL ||
                !youtubePlayerReady || webView == null || isFinishing || isDestroyed
            ) return
            when (intent.getStringExtra(YouTubeBackgroundService.EXTRA_CONTROL)) {
                "play" -> requestYouTubePlayback()
                "pause" -> pauseYouTubePlayback()
                "next" -> playNext()
                "previous" -> playPrevious()
            }
        }
    }
    private var youtubeBackgroundServiceStarted = false
    private var backgroundPlaybackRecoveryGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val backgroundControlFilter = android.content.IntentFilter(
            YouTubeBackgroundService.ACTION_CONTROL
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            youtubeBackgroundControlReceiver,
            backgroundControlFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        youtubeBackgroundControlReceiverRegistered = true

        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        inAppBackgroundPlaybackEnabled = prefs.getBoolean(
            "youtube_background_playback_enabled", false
        )
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
                if (customView != null || isCssFullscreen) {
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
            setPadding(0, 0, 0, dp(4))
        }
        contentContainer = content

        // Compact header: one navigation control and one title, without duplicated branding.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(12), dp(3))
            setBackgroundColor(AndroidColor.rgb(15, 16, 20))
        }

        val backButton = actionButton("‹") {
            if (customView != null || isCssFullscreen) exitFullscreen() else finish()
        }.apply {
            textSize = 27f
            contentDescription = "Quay lại"
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(42), dp(42)))

        val headerTitle = TextView(this).apply {
            text = "YouTube"
            setTextColor(AndroidColor.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        playerContainer = InlinePlayerFrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            contentDescription = "Trình phát video YouTube"
        }

        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // The host frame measures its own actual width and enforces 16:9.
        // This avoids stale physical-screen measurements after rotation or inset changes.
        content.addView(playerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        inlinePlayerLayoutParams = playerContainer.layoutParams

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(3))
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
        }
        titleView = TextView(this).apply {
            text = title
            setTextColor(AndroidColor.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(2))
        }
        channelView = TextView(this).apply {
            text = channel
            setTextColor(AndroidColor.rgb(158, 160, 171))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(titleView)
        info.addView(channelView)
        content.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Keep only the essential playback controls visible.
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        previousButton = actionButton("⏮") { playPrevious() }.apply {
            textSize = 18f
            contentDescription = "Video trước"
        }
        playPauseButton = actionButton("▶") { toggleYoutubePlayback() }.apply {
            textSize = 21f
            contentDescription = "Phát hoặc tạm dừng"
        }
        nextButton = actionButton("⏭") { playNext() }.apply {
            textSize = 18f
            contentDescription = "Video tiếp theo"
        }
        // YouTube's own player already provides fullscreen; do not duplicate it here.
        styleWideButton(playPauseButton, emphasized = true)

        transport.addView(previousButton, LinearLayout.LayoutParams(dp(54), dp(44)).apply {
            marginEnd = dp(8)
        })
        transport.addView(playPauseButton, LinearLayout.LayoutParams(dp(66), dp(46)).apply {
            marginEnd = dp(8)
        })
        transport.addView(nextButton, LinearLayout.LayoutParams(dp(54), dp(44)))
        content.addView(transport)

        // One compact action row replaces four large utility buttons.
        val queueActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        val queueButton = actionButton(
            "☷  Hàng đợi  •  " + (queueIndex + 1) + "/" + queue.size.coerceAtLeast(1)
        ) { showQueueDialog() }.apply {
            tag = "queue_button"
            textSize = 13f
            contentDescription = "Mở hàng đợi YouTube"
        }
        styleWideButton(queueButton)
        queueActions.addView(queueButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(7)
        })

        val backgroundButton = actionButton("🎧 Nền") {
            enableInAppBackgroundPlayback()
        }.apply {
            contentDescription = "Bật phát nền trong ứng dụng"
            textSize = 12f
        }
        queueActions.addView(backgroundButton, LinearLayout.LayoutParams(dp(78), dp(42)).apply {
            marginEnd = dp(7)
        })

        moreButton = actionButton("⋯") { showMoreOptions(moreButton) }.apply {
            contentDescription = "Tùy chọn YouTube"
            textSize = 22f
        }
        queueActions.addView(moreButton, LinearLayout.LayoutParams(dp(46), dp(42)))
        content.addView(queueActions)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
            clipToPadding = false
            addView(content)
        }
        playerScrollView = scroll
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

    private fun showMoreOptions(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (isFavorite()) "♥  Bỏ yêu thích" else "♡  Yêu thích")
        popup.menu.add(0, 2, 1, if (isWatchLater()) "✓  Bỏ khỏi xem sau" else "🔖  Lưu xem sau")
        popup.menu.add(0, 3, 2, if (youtubeShuffleEnabled) "🔀  Tắt phát trộn" else "🔀  Bật phát trộn")
        popup.menu.add(0, 4, 3, when (youtubeRepeatMode) {
            1 -> "🔁  Lặp hàng đợi (đang bật)"
            2 -> "🔂  Lặp bài (đang bật)"
            else -> "🔁  Chế độ lặp: tắt"
        })
        popup.menu.add(0, 5, 4, "🎧  Bật phát nền trong ứng dụng")
        popup.menu.add(0, 6, 5, "↗  Chia sẻ video")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleFavorite()
                2 -> toggleWatchLater()
                3 -> toggleYoutubeShuffle()
                4 -> toggleYoutubeRepeat()
                5 -> enableInAppBackgroundPlayback()
                6 -> shareCurrentVideo()
                else -> return@setOnMenuItemClickListener false
            }
            updateQueueModeButtons()
            true
        }
        popup.show()
    }

    private fun shareCurrentVideo() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + videoId)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
    }

    private fun requestYoutubeFullscreen() {
        val player = webView
        if (player == null) {
            Toast.makeText(this, "Trình phát chưa sẵn sàng.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isCssFullscreen) {
            exitFullscreen()
            return
        }

        wasPlayingBeforeFullscreen = youtubePlayerState == 1 ||
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                .getBoolean("youtube_playback_is_playing", false)
        resumePlaybackOnNextOrientationChange = wasPlayingBeforeFullscreen ||
            resumePlaybackOnNextOrientationChange
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 5000L
        if (wasPlayingBeforeFullscreen && !explicitUserPause) {
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                .putBoolean("youtube_playback_is_playing", true)
                .apply()
        }

        // Keep the existing YouTube WebView/media element alive. Do not invoke
        // YouTube's native fullscreen button or video.webkitEnterFullscreen(),
        // which can hand playback to a separate custom view and pause the stream.
        player.evaluateJavascript(
            """
            (function() {
              var style = document.getElementById('ngocsi-css-fullscreen');
              if (!style) {
                style = document.createElement('style');
                style.id = 'ngocsi-css-fullscreen';
                document.head.appendChild(style);
              }
              style.textContent =
                'html,body { width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important;margin:0!important;padding:0!important; }' +
                'ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer,ytm-slim-video-metadata-section-renderer,ytm-watch-metadata,#masthead-container,' +
                'ytm-comments-entry-point-header-renderer,ytm-comments-entry-point-teaser-renderer,' +
                'ytm-comment-section-renderer,ytm-comment-renderer,ytm-comment-thread-renderer,' +
                'ytm-comments-entry-point-renderer,ytm-comment-simplebox-renderer,' +
                '.ytp-title,.ytp-title-text,.ytp-title-link,.ytp-chrome-top,.ytp-pause-overlay,' +
                '.ytp-pause-overlay-container,.ytp-large-play-button,.ytp-cued-thumbnail-overlay,' +
                '.ytp-next-button,.ytp-prev-button,.ytp-next-button-container,.ytp-prev-button-container,' +
                '.ytp-endscreen-content,.ytp-endscreen-previous,.ytp-endscreen-next,.ytp-ce-covering-overlay,' +
                '.ytp-cards-teaser,.ytp-ce-element,.ytp-gradient-top,' +
                'ytm-play-button-renderer,' +
                '.ytp-show-cards-title,ytm-player-overlay-renderer,ytm-video-overlay-renderer,' +
                'ytm-mobile-player-overlay-renderer { display:none!important; }' +
                'ytm-player { position:fixed!important; inset:0!important; width:auto!important;height:auto!important;min-width:0!important;min-height:0!important;max-width:none!important;max-height:none!important;aspect-ratio:auto!important;z-index:2147483647!important;background:#000!important;overflow:hidden!important;margin:0!important;padding:0!important; }' +
                'ytm-player,#player-container,#player-container-inner,#movie_player,.html5-video-player,.html5-video-container { box-sizing:border-box!important;max-width:100%!important;max-height:100%!important;overflow:hidden!important;margin:0!important;padding:0!important; }' +
                'ytm-player video,video.html5-main-video { object-fit:contain!important;object-position:center center!important;max-width:100%!important;max-height:100%!important; }';
              return 'ok';
            })();
            """.trimIndent()
        ) { result ->
            if (result == "\"ok\"") {
                isCssFullscreen = true
                enterFullscreen()
            } else {
                Toast.makeText(this, "Không thể mở toàn màn hình. Hãy thử lại.", Toast.LENGTH_SHORT).show()
            }
        }
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
        if (::moreButton.isInitialized) {
            val shuffleState = if (youtubeShuffleEnabled) "bật" else "tắt"
            val repeatState = when (youtubeRepeatMode) {
                1 -> "lặp hàng đợi"
                2 -> "lặp bài"
                else -> "không lặp"
            }
            moreButton.contentDescription = "Tùy chọn YouTube; trộn $shuffleState; $repeatState"
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

    /**
     * Enables bounded recovery for the current YouTube HTML5 video while this Activity
     * is backgrounded. Playback stays in this WebView; no external browser is launched.
     */
    private fun enableInAppBackgroundPlayback() {
        if (isFinishing || isDestroyed || webView == null || !youtubePlayerReady) {
            Toast.makeText(this, "Trình phát YouTube chưa sẵn sàng.", Toast.LENGTH_SHORT).show()
            return
        }
        val safeId = sanitizeVideoId(videoId)
        if (safeId.isBlank()) {
            Toast.makeText(this, "Không xác định được video YouTube hiện tại.", Toast.LENGTH_SHORT).show()
            return
        }
        if (youtubePlayerState != 1) {
            Toast.makeText(
                this,
                "Video YouTube chưa phát. Hãy bấm Phát trước rồi bấm Nền.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Keep this Activity and its WebView alive: finish() would destroy the
        // HTML5 video element. Move the app task behind other apps after the
        // foreground media service is updated, so the same YouTube session keeps playing.
        inAppBackgroundPlaybackEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView?.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false
            )
        }
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_background_playback_enabled", true)
            .putBoolean("youtube_active_source", true)
            .putBoolean("youtube_playback_is_playing", true)
            .putBoolean("youtube_is_playing", true)
            .apply()
        backgroundPlaybackRecoveryGeneration++
        syncYouTubeBackgroundPlayback(1)

        Toast.makeText(
            this,
            "Đã bật phát nền. YouTube tiếp tục phát khi ứng dụng được thu nhỏ.",
            Toast.LENGTH_SHORT
        ).show()

        // Return to the main NGỌC SĨ MUSIC screen without finishing this Activity.
        // FLAG_ACTIVITY_REORDER_TO_FRONT reuses MainActivity if it is already in the task;
        // this YouTube Activity remains underneath, keeping the same WebView/video alive.
        // Calling finish() would destroy the HTML5 media element and stop playback.
        root.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, 250L)
    }

    /**
     * A hidden YouTube page sometimes pauses its HTML5 video when Android backgrounds the
     * Activity. Try restoring that same element a maximum of three times, with delays.
     * Explicit Pause disables this recovery, so lock-screen Pause is respected.
     */
    private fun scheduleBackgroundPlaybackRecovery() {
        val generation = ++backgroundPlaybackRecoveryGeneration
        fun attempt(index: Int) {
            if (index >= 3 || generation != backgroundPlaybackRecoveryGeneration ||
                isFinishing || isDestroyed
            ) return

            val delay = when (index) {
                0 -> 800L
                1 -> 1500L
                else -> 2500L
            }
            playbackPositionHandler.postDelayed({
                val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                if (generation != backgroundPlaybackRecoveryGeneration ||
                    isFinishing || isDestroyed || webView == null ||
                    prefs.getBoolean("youtube_player_foreground", true) ||
                    !prefs.getBoolean("youtube_background_playback_enabled", false)
                ) return@postDelayed

                val script = """
                    (function(){
                      try {
                        var v = (typeof window.__ngocSiFindActiveVideo === 'function')
                          ? window.__ngocSiFindActiveVideo()
                          : (document.querySelector('video.html5-main-video') ||
                             document.querySelector('#movie_player video') ||
                             document.querySelector('video'));
                        if (!v || v.ended) return 'no-video';
                        if (!v.paused && !v.ended) return 'already-playing';
                        var p = document.querySelector('#movie_player') ||
                                document.querySelector('.html5-video-player') ||
                                document.querySelector('ytm-player');
                        // Use only one playback path per attempt to avoid overlapping
                        // play requests that previously caused stutter on this device.
                        if (p && typeof p.playVideo === 'function') {
                          try { p.playVideo(); return 'requested-api'; } catch (_) {}
                        }
                        try {
                          var promise = v.play();
                          if (promise && typeof promise.catch === 'function') promise.catch(function(){});
                          return 'requested-video';
                        } catch (_) {
                          return 'recovery-error';
                        }
                      } catch(e) { return 'recovery-error'; }
                    })();
                """.trimIndent()
                webView?.evaluateJavascript(script) { result ->
                    if (result?.contains("already-playing") == true) {
                        backgroundPlaybackRecoveryGeneration++
                    } else if (index < 2) {
                        attempt(index + 1)
                    }
                }
            }, delay)
        }
        attempt(0)
    }

    private fun markExplicitPauseIntent() {
        explicitUserPause = true
        resumePlaybackOnNextOrientationChange = false
        wasPlayingBeforeFullscreen = false
        orientationRecoveryWindowUntilMs = 0L
        fullscreenExitGeneration++
        fullscreenExitRecoveryPending = false
        fullscreenRecoveryGeneration++
        orientationPlaybackRecoveryGeneration++
        playbackRequestGeneration++
        backgroundPlaybackRecoveryGeneration++
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", false)
            .putBoolean("youtube_is_playing", false)
            .apply()
        youtubePlayerState = 2
        updatePlaybackButton()
        syncYouTubeBackgroundPlayback(2)
    }

    private fun markExplicitPlayIntent() {
        explicitUserPause = false
        resumePlaybackOnNextOrientationChange = true
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 2200L
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", true)
            .apply()
    }

    private fun noteViewportPlaybackHandoff() {
        if (isFinishing || isDestroyed || webView == null || explicitUserPause) return
        val now = System.currentTimeMillis()
        orientationRecoveryWindowUntilMs = maxOf(orientationRecoveryWindowUntilMs, now + 5000L)
        resumePlaybackOnNextOrientationChange = true
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", true)
            .apply()

        // A native configuration callback already schedules its own recovery. Do not start
        // a second probe loop from WebView.resize a few hundred milliseconds later; these
        // competing loops previously invalidated one another and delayed playback restoration.
        if (now - lastOrientationConfigurationChangeAtMs < 1800L) return

        val generation = ++orientationPlaybackRecoveryGeneration
        playbackPositionHandler.postDelayed({
            if (generation != orientationPlaybackRecoveryGeneration ||
                explicitUserPause || isFinishing || isDestroyed || webView == null ||
                !getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                    .getBoolean("youtube_playback_is_playing", false)
            ) return@postDelayed
            recoverFullscreenPlayback(allowPageReloadFallback = false)
        }, 1100L)
    }

    private fun pauseYouTubePlayback() {
        // A deliberate app-level Pause invalidates every pending rotation recovery.
        markExplicitPauseIntent()
        runYoutubePlaybackCommand("pause")
    }

    /**
     * All WebView playback paths use the same active-video selection. The YouTube mobile
     * page can keep a hidden/stale video element, which previously made playback controls
     * target the wrong element or accidentally pause instead of play.
     */
    private fun youtubePlaybackScript(command: String): String {
        val safeCommand = when (command) {
            "play" -> "play"
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
                      // Fullscreen transitions can leave an old but still-connected video
                      // node behind. Never send transport commands to that hidden/stale node.
                      if (current && isVisibleVideo(current)) return current;
                    }
                  } catch (_) {}
                  var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                  var visible = videos.filter(isVisibleVideo);
                  // Lock onto the actual YouTube player video first. Do not let a playing
                  // ad/preview video outrank the main video.
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
                  return visible[0] || null;
                }
                function getPlayerApi() {
                  return document.querySelector('#movie_player') ||
                         document.querySelector('.html5-video-player') ||
                         document.querySelector('ytm-player');
                }
                function isActuallyPlaying(video) {
                  if (!video || video.paused || video.ended || video.readyState < 2) return false;
                  if (window.__ngocSiTrackedVideo === video &&
                      typeof window.__ngocSiPlaybackActuallyPlaying === 'boolean') {
                    var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                    return window.__ngocSiPlaybackActuallyPlaying &&
                      progressAt > 0 && Date.now() - progressAt < 1800;
                  }
                  // A media element can remain paused=false while its clock is frozen after
                  // a fullscreen handoff. Without recent clock-progress evidence, treat it as
                  // unknown/stalled rather than "playing"; otherwise Toggle can pause a frozen
                  // video and the user's Play tap appears to do nothing.
                  return false;
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

                var api = getPlayerApi();
                var video = findVideo();
                var foundRenderableVideo = !!video;
                if (!video) {
                  // During landscape-to-portrait handoff Chromium can temporarily report a
                  // zero-size rectangle for the still-connected YouTube video. Keep Play/Pause
                  // attached to the real primary media node instead of returning "no-video".
                  var selectors = [
                    'video.html5-main-video',
                    '#movie_player video',
                    '.html5-video-player video',
                    'ytm-player video'
                  ];
                  for (var s = 0; s < selectors.length && !video; s++) {
                    var candidates = document.querySelectorAll(selectors[s]);
                    for (var j = 0; j < candidates.length; j++) {
                      var candidate = candidates[j];
                      if (!candidate || !candidate.isConnected) continue;
                      var hidden = false;
                      var node = candidate;
                      while (node && node.nodeType === 1 && node !== document.documentElement) {
                        var style = window.getComputedStyle(node);
                        if (style.display === 'none' || style.visibility === 'hidden' ||
                            Number(style.opacity || 1) === 0) {
                          hidden = true;
                          break;
                        }
                        node = node.parentElement;
                      }
                      if (!hidden) {
                        video = candidate;
                        break;
                      }
                    }
                  }
                }
                if (!video) {
                  // If YouTube has temporarily replaced the media element, its player API can
                  // still accept Play/Pause while the new video node is being attached.
                  if ('$safeCommand' === 'pause' && api && typeof api.pauseVideo === 'function') {
                    try { api.pauseVideo(); return 'paused-no-video'; } catch (_) {}
                  }
                  if (api && typeof api.playVideo === 'function') {
                    try { api.playVideo(); return 'requested-api-no-video'; } catch (_) {}
                  }
                  return 'no-video';
                }

                var actuallyPlaying = isActuallyPlaying(video);
                var wantsPause = '$safeCommand' === 'pause' ||
                    ('$safeCommand' === 'toggle' && actuallyPlaying);

                if (wantsPause) {
                  try { if (api && typeof api.pauseVideo === 'function') api.pauseVideo(); } catch (_) {}
                  try { video.pause(); } catch (_) {}
                  return 'paused';
                }

                // Multiple simultaneous playback mechanisms caused stutter. Use one mechanism
                // per request: prefer the YouTube player API, then the visible button or HTML
                // media API only when the preferred mechanism is unavailable.
                if ('$safeCommand' === 'play' && isActuallyPlaying(video)) return 'playing';

                // Use one transport path per request. When rotation temporarily leaves the
                // selected HTML5 video with a zero-size surface, do not call the player API and
                // then video.play() as a second competing command; that sequence can stutter.
                if (foundRenderableVideo && api && typeof api.playVideo === 'function') {
                  try { api.playVideo(); return 'requested-api'; } catch (_) {}
                }
                if (foundRenderableVideo && clickPlayButton()) return 'requested-button';
                try {
                  var promise = video.play();
                  if (promise && typeof promise.catch === 'function') {
                    promise.catch(function(){});
                  }
                  return isActuallyPlaying(video) ? 'playing' :
                    (!video.paused ? 'buffering' : 'requested-video');
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
        if (webView == null || isFinishing || isDestroyed) return
        if (command == "toggle") {
            orientationPlaybackRecoveryGeneration++
        }
        val generation = ++playbackCommandGeneration
        // A button press sends one playback command. Do not repeatedly call playVideo()
        // while YouTube is buffering: repeated commands can cause stutter and stop playback.
        val retryCommand = if (command == "toggle") "play" else command

        webView?.evaluateJavascript(youtubePlaybackScript(command)) { result ->
            if (generation != playbackCommandGeneration ||
                webView == null || isFinishing || isDestroyed
            ) return@evaluateJavascript
            val response = result.orEmpty()
            if (command == "toggle") {
                runOnUiThread {
                    if (generation == playbackCommandGeneration &&
                        !isFinishing && !isDestroyed
                    ) {
                        if (response.contains("paused")) {
                            markExplicitPauseIntent()
                        } else if (response.contains("requested-api") ||
                            response.contains("requested-button") ||
                            response.contains("requested-video") ||
                            response.contains("playing") ||
                            response.contains("buffering")
                        ) {
                            markExplicitPlayIntent()
                        }
                    }
                }
            }
            val accepted = response.contains("playing") ||
                response.contains("buffering") ||
                response.contains("requested-api") ||
                response.contains("requested-button") ||
                response.contains("requested-video") ||
                response.contains("paused")
            val rejected = response.contains("retry") || response.contains("no-video") ||
                response.contains("error")
            if (!accepted && rejected && command != "pause") {
                playbackPositionHandler.postDelayed({
                    if (generation == playbackCommandGeneration &&
                        !isFinishing && !isDestroyed && webView != null
                    ) {
                        webView?.evaluateJavascript(youtubePlaybackScript(retryCommand), null)
                    }
                }, 700L)
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

    private fun requestYouTubePlayback() {
        if (!youtubePlayerReady || webView == null || isFinishing || isDestroyed) return
        explicitUserPause = false
        orientationPlaybackRecoveryGeneration++
        resumePlaybackOnNextOrientationChange = true
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", true)
            .apply()
        val requestGeneration = ++playbackRequestGeneration
        fun attempt(remaining: Int) {
            if (requestGeneration != playbackRequestGeneration ||
                isFinishing || isDestroyed || webView == null
            ) return
            webView?.evaluateJavascript(youtubePlaybackScript("play")) requestPlaybackCallback@{ result ->
                if (requestGeneration != playbackRequestGeneration) return@requestPlaybackCallback
                val response = result.orEmpty()
                val accepted = response.contains("playing") ||
                    response.contains("buffering") ||
                    response.contains("requested-api") ||
                    response.contains("requested-button") ||
                    response.contains("requested-video")
                // Retry only a rejected request. A valid Play request never starts
                // a competing forced pause/Play recovery loop.
                if (!accepted && remaining > 0 && !isFinishing && !isDestroyed &&
                    requestGeneration == playbackRequestGeneration
                ) {
                    playbackPositionHandler.postDelayed({ attempt(remaining - 1) }, 750L)
                } else if (accepted && (
                        response.contains("requested-api") ||
                        response.contains("requested-button") ||
                        response.contains("requested-video")
                    )) {
                    // A transport command being accepted does not prove that the media clock
                    // resumed. Check once after the orientation/decoder has had time to settle.
                    // If it is still paused or genuinely stalled, run the bounded recovery path.
                    playbackPositionHandler.postDelayed({
                        if (requestGeneration != playbackRequestGeneration ||
                            isFinishing || isDestroyed || webView == null ||
                            fullscreenExitRecoveryPending
                        ) return@postDelayed

                        val verifyScript = """
                            (function() {
                              var video = null;
                              try {
                                if (typeof window.__ngocSiFindActiveVideo === 'function')
                                  video = window.__ngocSiFindActiveVideo();
                              } catch (_) {}
                              if (!video) video = document.querySelector('video.html5-main-video') ||
                                document.querySelector('#movie_player video') ||
                                document.querySelector('ytm-player video') ||
                                document.querySelector('video');
                              if (!video) return 'NO_VIDEO';
                              if (video.ended) return 'ENDED';
                              if (video.paused) return 'PAUSED';
                              if (video.readyState < 2) return 'BUFFERING';
                              var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                              if (window.__ngocSiTrackedVideo === video &&
                                  window.__ngocSiPlaybackActuallyPlaying === true &&
                                  progressAt > 0 && Date.now() - progressAt < 1800)
                                return 'PLAYING';
                              return 'STALLED';
                            })();
                        """.trimIndent()
                        webView?.evaluateJavascript(verifyScript) verifyPlaybackCallback@{ verifyResult ->
                            if (requestGeneration != playbackRequestGeneration ||
                                isFinishing || isDestroyed || fullscreenExitRecoveryPending
                            ) return@verifyPlaybackCallback

                            val mediaStatus = verifyResult.orEmpty().trim('"')
                            if (mediaStatus == "PAUSED" || mediaStatus == "STALLED") {
                                recoverFullscreenPlayback()
                            }
                        }
                    }, 1800L)
                }
            }
        }
        attempt(4)
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
            "queue" -> showQueueDialog()
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
        geometryReloadFallbackAttemptedForCurrentPage = false
        pageErrorVisible = false
        youtubePlayerReady = false
        youtubePlayerState = -1
        restoredPositionVideoId = null
        positionRestoreAttempts = 0
        lastHandledEndedVideoId = null

        val player = BackgroundAudioWebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            // Background playback needs the WebView renderer to remain important while
            // the player window is hidden. The default "waive when not visible" policy
            // makes renderer eviction more likely during background playback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val priority = if (inAppBackgroundPlaybackEnabled) {
                    WebView.RENDERER_PRIORITY_IMPORTANT
                } else {
                    WebView.RENDERER_PRIORITY_BOUND
                }
                setRendererPriorityPolicy(
                    priority,
                    !inAppBackgroundPlaybackEnabled
                )
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = false
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
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    if (isKnownYouTubeAdvertisingRequest(request.url)) {
                        // Block only recognizable ad-serving hosts/endpoints. Never block
                        // googlevideo.com: it also delivers the actual video/audio streams.
                        return android.webkit.WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            204,
                            "No Content",
                            mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

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
                          // Best-effort removal of the duplicate mobile masthead, title row,
                          // common ad slots, and mute prompt. Video delivery hosts are not filtered.
                          try {
                            // Keep the mobile page's CSS viewport aligned with the actual WebView.
                            // Otherwise overview scaling can render the player as a small island
                            // inside the native 16:9 frame on some Android viewport configurations.
                            var ngocSiViewport = document.querySelector('meta[name="viewport"]');
                            if (!ngocSiViewport) {
                              ngocSiViewport = document.createElement('meta');
                              ngocSiViewport.name = 'viewport';
                              (document.head || document.documentElement).appendChild(ngocSiViewport);
                            }
                            ngocSiViewport.setAttribute(
                              'content',
                              'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'
                            );

                            // The mobile YouTube SPA may restore a scroll offset after
                            // onPageFinished, once player metadata/overlays are inserted.
                            // This WebView is the player surface, not a browser page: keep it
                            // top-aligned during inline playback and normalize again after SPA
                            // navigation/orientation settles. Never fight actual fullscreen.
                            (function installNgocSiInlineScrollGuard() {
                              if (window.__ngocSiInlineScrollGuardInstalled) return;
                              window.__ngocSiInlineScrollGuardInstalled = true;
                              try { if ('scrollRestoration' in history) history.scrollRestoration = 'manual'; } catch (_) {}
                              var pending = false;
                              function isFullscreen() {
                                return !!(document.getElementById('ngocsi-css-fullscreen') ||
                                  document.fullscreenElement);
                              }
                              function normalize() {
                                pending = false;
                                if (isFullscreen()) return;
                                try {
                                  if (document.scrollingElement && document.scrollingElement.scrollTop !== 0)
                                    document.scrollingElement.scrollTop = 0;
                                  if (document.documentElement && document.documentElement.scrollTop !== 0)
                                    document.documentElement.scrollTop = 0;
                                  if (document.body && document.body.scrollTop !== 0)
                                    document.body.scrollTop = 0;
                                  if (window.scrollX !== 0 || window.scrollY !== 0) window.scrollTo(0, 0);
                                } catch (_) {}
                              }
                              function scheduleNormalize() {
                                if (pending || isFullscreen()) return;
                                pending = true;
                                requestAnimationFrame(function() {
                                  requestAnimationFrame(normalize);
                                });
                              }
                              window.addEventListener('scroll', function() {
                                if (!isFullscreen() &&
                                    (window.scrollY > 0 ||
                                     (document.scrollingElement && document.scrollingElement.scrollTop > 0)))
                                  scheduleNormalize();
                              }, {passive:true});
                              window.addEventListener('pageshow', scheduleNormalize, {passive:true});
                              window.addEventListener('popstate', scheduleNormalize, {passive:true});
                              window.addEventListener('resize', scheduleNormalize, {passive:true});
                              window.addEventListener('orientationchange', scheduleNormalize, {passive:true});
                              document.addEventListener('yt-navigate-finish', scheduleNormalize, {passive:true});
                              document.addEventListener('yt-page-data-updated', scheduleNormalize, {passive:true});
                              if (window.visualViewport)
                                window.visualViewport.addEventListener('resize', scheduleNormalize, {passive:true});
                              scheduleNormalize();
                            })();

                            if (!document.getElementById('ngocsi-youtube-cleanup-style')) {
                              var cleanupStyle = document.createElement('style');
                              cleanupStyle.id = 'ngocsi-youtube-cleanup-style';
                              cleanupStyle.textContent =
                                // Remove the duplicate YouTube mobile browser chrome and title row
                                // highlighted by the user. Native app controls remain outside WebView.
                                'ytm-mobile-topbar-renderer, ytm-pivot-bar-renderer, ytm-app-header, ' +
                                '#masthead-container, ytm-slim-video-metadata-section-renderer, ' +
                                'ytm-watch-metadata, ytm-video-description-infocontainer-renderer, ' +
                                'ytm-comments-entry-point-header-renderer, ytm-comments-entry-point-teaser-renderer, ' +
                                'ytm-comment-section-renderer, ytm-comment-renderer, ytm-comment-thread-renderer, ' +
                                'ytm-comments-entry-point-renderer, ytm-comment-simplebox-renderer, ' +
                                'ytm-comments-entry-point-teaser, ytm-comment-section, ytm-item-section-renderer,' +
                                'ytm-engagement-panel, ytm-engagement-panel-section-list-renderer, ' +
                                'ytm-rich-section-renderer, ' +
                                'ytm-ad-slot-renderer, ytm-promoted-sparkles-web-renderer, ' +
                                'ytm-promoted-sparkles-text-search-renderer, ytm-promoted-video-renderer, ' +
                                'ytm-companion-slot, ytm-companion-ad-renderer, ytm-in-feed-ad-layout-renderer, ' +
                                'ytd-ad-slot-renderer, ytd-display-ad-renderer, ytd-promoted-video-renderer, ' +
                                'ytd-promoted-sparkles-web-renderer, ytd-in-feed-ad-layout-renderer, ' +
                                'ytd-banner-promo-renderer, ytd-action-companion-ad-renderer, ' +
                                'ytm-player-overlay-renderer, ytm-video-overlay-renderer, ytm-mobile-player-overlay-renderer, ' +
                                '.ytp-title, .ytp-title-text, .ytp-title-link, .ytp-chrome-top, ' +
                                '.ytp-pause-overlay, .ytp-pause-overlay-container, ' +
                                '.ytp-large-play-button, .ytp-cued-thumbnail-overlay, .ytp-next-button, .ytp-prev-button, ' +
                                '.ytp-next-button-container, .ytp-prev-button-container, ' +
                                '.ytp-endscreen-content, .ytp-endscreen-previous, .ytp-endscreen-next, ' +
                                '.ytp-ce-covering-overlay, .ytp-cards-teaser, ' +
                                ' ytm-play-button-renderer, ' +
                                '.ytp-ce-element, .ytp-gradient-top, .ytp-show-cards-title, ' +
                                '#player-ads, #masthead-ad, .video-ads, .ytp-ad-module, ' +
                                '.ytp-ad-player-overlay, .ytp-ad-overlay-container, ' +
                                '.ytp-ad-image-overlay, .ytp-ad-text-overlay {' +
                                'display:none!important;visibility:hidden!important;' +
                                'pointer-events:none!important;max-height:0!important;} ' +
                                // Do not force fullscreen positioning in this always-on cleanup stylesheet.
                                // Fullscreen sizing is applied only while the fullscreen mode is active;
                                // keeping these rules global clips the inline player and can freeze it
                                // when Chromium restores the video surface after rotation.
                                'html,body,ytm-app,ytm-watch {max-width:100%!important;background:#000!important;}';
                              (document.head || document.documentElement).appendChild(cleanupStyle);
                            }
                            // Keep the inline player in normal document flow. Fixed positioning
                            // across orientation changes can leave Chromium's video surface frozen
                            // when the fullscreen custom view is dismissed.
                            // The YouTube mobile page can place its central controls inside
                            // an open shadow root. Page-level CSS cannot style those nodes, so add
                            // the same narrowly-scoped hide rules to every open player shadow root
                            // and re-apply them when YouTube inserts/replaces overlay nodes.
                            (function installNgocSiCenterControlHider() {
                              var selectors =
                                '.ytp-cued-thumbnail-overlay,.ytp-next-button,.ytp-prev-button,' +
                                '.ytp-next-button-container,.ytp-prev-button-container,' +
                                '.ytp-endscreen-content,.ytp-endscreen-previous,.ytp-endscreen-next,' +
                                '.ytp-ce-covering-overlay,.ytp-cards-teaser,.ytp-ce-element,' +
                                'ytm-player-overlay-renderer,ytm-video-overlay-renderer,' +
                                'ytm-mobile-player-overlay-renderer,ytm-player-overlay,' +
                                '[class*="next-button-overlay"]';
                              var rules = selectors +
                                '{display:none!important;visibility:hidden!important;opacity:0!important;' +
                                'pointer-events:none!important;max-height:0!important;}';
                              var observedRoots = window.__ngocSiObservedOverlayRoots ||
                                (window.__ngocSiObservedOverlayRoots = new WeakSet());

                              function getPlayerRect(rootNode) {
                                try {
                                  var player = rootNode.querySelector(
                                    'ytm-player,#movie_player,.html5-video-player,#player-container-inner'
                                  );
                                  if (player) return player.getBoundingClientRect();
                                  var host = rootNode.host || null;
                                  while (host) {
                                    if (host.matches && host.matches(
                                      'ytm-player,#movie_player,.html5-video-player,#player-container-inner'
                                    )) return host.getBoundingClientRect();
                                    if (host.parentElement) {
                                      host = host.parentElement;
                                    } else {
                                      var enclosingRoot = host.getRootNode();
                                      host = enclosingRoot && enclosingRoot.host ? enclosingRoot.host : null;
                                    }
                                  }
                                } catch (_) {}
                                return null;
                              }
                              function hideCenterLabeledControls(rootNode) {
                                try {
                                  var playerRect = getPlayerRect(rootNode);
                                  if (!playerRect || playerRect.width < 10 || playerRect.height < 10) return;
                                  var buttons = rootNode.querySelectorAll(
                                    'button,[role="button"],ytm-button-renderer,.ytp-button,' +
                                    '.ytp-next-button,.ytp-prev-button,' +
                                    '[class*="next-button-overlay"]'
                                  );
                                  for (var i = 0; i < buttons.length; i++) {
                                    var button = buttons[i];
                                    var label = ((button.getAttribute('aria-label') || '') + ' ' +
                                      (button.getAttribute('title') || '') + ' ' +
                                      (button.innerText || button.textContent || '')).toLowerCase()
                                      .replace(/\s+/g, ' ').trim();
                                    var classHint = '';
                                    try {
                                      classHint = String(button.className && button.className.baseVal
                                        ? button.className.baseVal : button.className || '') + ' ' +
                                        String(button.id || '');
                                    } catch (_) {}
                                    var hasCenterControlClass = /large-play-button|center-play-button|central-play-button|next-button-overlay|ytp-overlay-button/i.test(classHint);
                                    // Keep YouTube's single central Play/Pause control available in
                                    // landscape. Hide only duplicate skip/next/previous overlays.
                                    var rect = button.getBoundingClientRect();
                                    if (rect.width < 3 || rect.height < 3) continue;
                                    var x = (rect.left + rect.width / 2 - playerRect.left) / playerRect.width;
                                    var y = (rect.top + rect.height / 2 - playerRect.top) / playerRect.height;
                                    var skipControl = /next|previous|prev|skip|tiếp|trước/i.test(
                                      label + ' ' + classHint
                                    );
                                    if (skipControl && x >= 0.25 && x <= 0.75 &&
                                        y >= 0.18 && y <= 0.82) {
                                      button.style.setProperty('display','none','important');
                                      button.style.setProperty('visibility','hidden','important');
                                      button.style.setProperty('opacity','0','important');
                                      button.style.setProperty('pointer-events','none','important');
                                    }
                                  }
                                } catch (_) {}
                              }
                              function visitNode(node) {
                                if (!node || node.nodeType !== 1) return;
                                try {
                                  if (node.shadowRoot) protectRoot(node.shadowRoot);
                                  var descendants = node.querySelectorAll ? node.querySelectorAll('*') : [];
                                  for (var i = 0; i < descendants.length; i++) {
                                    if (descendants[i].shadowRoot) protectRoot(descendants[i].shadowRoot);
                                  }
                                } catch (_) {}
                              }
                              function protectRoot(rootNode) {
                                if (!rootNode || !rootNode.querySelectorAll) return;
                                try {
                                  if (!rootNode.querySelector('#ngocsi-center-controls-shadow-style')) {
                                    var overlayStyle = document.createElement('style');
                                    overlayStyle.id = 'ngocsi-center-controls-shadow-style';
                                    overlayStyle.textContent = rules;
                                    rootNode.appendChild(overlayStyle);
                                  }
                                } catch (_) {}
                                if (!observedRoots.has(rootNode)) {
                                  observedRoots.add(rootNode);
                                  try {
                                    var observer = new MutationObserver(function(records) {
                                      for (var r = 0; r < records.length; r++) {
                                        for (var n = 0; n < records[r].addedNodes.length; n++) {
                                          visitNode(records[r].addedNodes[n]);
                                        }
                                      }
                                      hideCenterLabeledControls(rootNode);
                                    });
                                    observer.observe(rootNode, {childList:true, subtree:true});
                                  } catch (_) {}
                                }
                                hideCenterLabeledControls(rootNode);
                                try {
                                  var nodes = rootNode.querySelectorAll('*');
                                  for (var j = 0; j < nodes.length; j++) {
                                    if (nodes[j].shadowRoot) protectRoot(nodes[j].shadowRoot);
                                  }
                                } catch (_) {}
                              }
                              protectRoot(document);
                            })();

                            if (!document.getElementById('ngocsi-player-viewport-fix')) {
                              var fitStyle = document.createElement('style');
                              fitStyle.id = 'ngocsi-player-viewport-fix';
                              fitStyle.textContent =
                                'html,body,ytm-app,ytm-watch {width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:0!important;}' +
                                'ytm-watch {margin-top:0!important;padding-top:0!important;overflow:hidden!important;}' +
                                'ytm-watch #player-container-outer,ytm-watch #player-container-inner,' +
                                'ytm-watch .player-container,ytm-watch #player-container-id {' +
                                'margin-top:0!important;padding-top:0!important;border-top:0!important;}' +
                                'ytm-player {box-sizing:border-box!important;display:block!important;position:relative!important;' +
                                'top:0!important;transform:none!important;width:100%!important;' +
                                'height:100%!important;min-height:0!important;max-height:none!important;' +
                                'aspect-ratio:auto!important;max-width:none!important;margin:0!important;padding:0!important;' +
                                'border:0!important;overflow:hidden!important;}' +
                                '#player-container,#player-container-id,#player,#movie_player,.html5-video-player,' +
                                '.html5-video-container {box-sizing:border-box!important;width:100%!important;' +
                                'height:100%!important;min-height:0!important;max-height:none!important;' +
                                'max-width:none!important;margin:0!important;padding:0!important;overflow:hidden!important;}' +
                                // Apply crop to the actual video surface and remove intrinsic aspect-ratio caps.
                                // Avoid changing its position or explicit dimensions to protect WebView playback.
                                'ytm-player video,video.html5-main-video,.html5-video-player video {' +
                                'object-fit:contain!important;object-position:50% 50%!important;' +
                                'max-width:none!important;max-height:none!important;}';
                              (document.head || document.documentElement).appendChild(fitStyle);
                            }
                            function ngocSiFitPlayerToViewport() {
                              var root = document.querySelector('ytm-player') ||
                                document.querySelector('#movie_player') ||
                                document.querySelector('#player');
                              if (!root) return;
                              var viewportWidth = Math.max(1, document.documentElement.clientWidth || window.innerWidth || 1);
                              var viewportHeight = Math.max(1, document.documentElement.clientHeight || window.innerHeight || 1);
                              var isFullscreen = !!(document.fullscreenElement ||
                                document.getElementById('ngocsi-css-fullscreen'));
                              root.style.setProperty('box-sizing','border-box','important');
                              root.style.setProperty('max-width','none','important');
                              if (isFullscreen) {
                                // Fullscreen uses the viewport; Android's host frame is expanded
                                // before the delayed layout sync measures these dimensions.
                                root.style.setProperty('position','fixed','important');
                                root.style.setProperty('left','0','important');
                                root.style.setProperty('right','0','important');
                                root.style.setProperty('top','0','important');
                                root.style.setProperty('bottom','0','important');
                                root.style.setProperty('width',viewportWidth+'px','important');
                                root.style.setProperty('height',viewportHeight+'px','important');
                                root.style.setProperty('aspect-ratio','auto','important');
                              } else {
                                // The native InlinePlayerFrameLayout already measures an exact 16:9
                                // surface. Do not calculate another pixel width/height from the
                                // WebView viewport: that measurement can lag rotation and leave the
                                // video inset or below a black strip. Let the WebView's measured
                                // viewport be the single source of truth for inline dimensions.
                                ['position','left','right','top','bottom','inset'].forEach(function(p) {
                                  root.style.removeProperty(p);
                                });
                                root.style.setProperty('width','100%','important');
                                root.style.setProperty('height','100%','important');
                                root.style.setProperty('aspect-ratio','auto','important');
                                root.style.setProperty('margin','0','important');
                                root.style.setProperty('padding','0','important');
                              }
                            }
                            // Avoid repeatedly rewriting player dimensions while Chromium is
                            // handing the video surface between portrait, landscape and inline.
                            // A recurring timer was racing YouTube's own layout and could leave
                            // the player at a stale height after the second/third rotation.
                            if (window.__ngocSiFitPlayerTimer) {
                              clearInterval(window.__ngocSiFitPlayerTimer);
                              window.__ngocSiFitPlayerTimer = null;
                            }
                            if (window.__ngocSiFitPlayerResizeHandler) {
                              window.removeEventListener('resize', window.__ngocSiFitPlayerResizeHandler);
                              window.removeEventListener('orientationchange', window.__ngocSiFitPlayerResizeHandler);
                              if (window.visualViewport) {
                                window.visualViewport.removeEventListener('resize', window.__ngocSiFitPlayerResizeHandler);
                              }
                              if (ngocSiFitFrame) cancelAnimationFrame(ngocSiFitFrame);
                              if (ngocSiFitSettleTimer) clearTimeout(ngocSiFitSettleTimer);
                            }
                            var ngocSiFitFrame = 0;
                            var ngocSiFitSettleTimer = 0;
                            window.__ngocSiFitPlayerResizeHandler = function() {
                              if (ngocSiFitFrame) cancelAnimationFrame(ngocSiFitFrame);
                              if (ngocSiFitSettleTimer) clearTimeout(ngocSiFitSettleTimer);
                              // Wait two animation frames for Chromium to commit the new viewport,
                              // then perform one bounded settling pass after orientation animation.
                              ngocSiFitFrame = requestAnimationFrame(function() {
                                ngocSiFitFrame = requestAnimationFrame(function() {
                                  ngocSiFitFrame = 0;
                                  ngocSiFitPlayerToViewport();
                                });
                              });
                              ngocSiFitSettleTimer = setTimeout(function() {
                                ngocSiFitSettleTimer = 0;
                                ngocSiFitPlayerToViewport();
                              }, 180);
                            };
                            ngocSiFitPlayerToViewport();
                            window.addEventListener('resize', window.__ngocSiFitPlayerResizeHandler, {passive:true});
                            window.addEventListener('orientationchange', window.__ngocSiFitPlayerResizeHandler, {passive:true});
                            if (window.visualViewport) {
                              window.visualViewport.addEventListener('resize', window.__ngocSiFitPlayerResizeHandler, {passive:true});
                            }
                          } catch (_) {}

                          var ngocSiUnmuteAttempts = 0;
                          function removeMutePromptAndTrySound() {
                            ngocSiUnmuteAttempts++;
                            var playerRoot = document.querySelector(
                              'ytm-player, #movie_player, .html5-video-player, ytm-watch'
                            ) || document;
                            var candidates = playerRoot.querySelectorAll(
                              'button,[role="button"],[aria-label],ytm-button-renderer,' +
                              '.ytp-unmute,.ytp-cued-thumbnail-overlay'
                            );
                            for (var i = 0; i < candidates.length; i++) {
                              var el = candidates[i];
                              var label = ((el.getAttribute('aria-label') || '') + ' ' +
                                (el.innerText || el.textContent || '')).toLowerCase()
                                .replace(/\s+/g, ' ').trim();
                              var isUnmutePrompt =
                                label.indexOf('nhấn để bật tiếng') >= 0 ||
                                label.indexOf('nhan de bat tieng') >= 0 ||
                                label.indexOf('tap to unmute') >= 0 ||
                                label.indexOf('click to unmute') >= 0 ||
                                label.indexOf('unmute video') >= 0;
                              if (!isUnmutePrompt) continue;
                              // Trigger YouTube's own unmute action before hiding its prompt.
                              try { el.click(); } catch (_) {}
                              try {
                                el.style.setProperty('visibility', 'hidden', 'important');
                                el.style.setProperty('opacity', '0', 'important');
                                el.style.setProperty('pointer-events', 'none', 'important');
                                if (el.parentElement &&
                                    (el.parentElement.innerText || '').toLowerCase()
                                      .indexOf('nhấn để bật tiếng') >= 0) {
                                  el.parentElement.style.setProperty('visibility', 'hidden', 'important');
                                  el.parentElement.style.setProperty('opacity', '0', 'important');
                                }
                              } catch (_) {}
                            }

                            // WebView is configured for playback without a second gesture.
                            // Try to clear autoplay mute without reloading/replacing the video.
                            var video = window.__ngocSiFindActiveVideo
                              ? window.__ngocSiFindActiveVideo()
                              : playerRoot.querySelector('video');
                            if (video && (video.muted || video.volume === 0)) {
                              try {
                                video.muted = false;
                                video.defaultMuted = false;
                                video.removeAttribute('muted');
                                video.volume = 1;
                              } catch (_) {}
                            }
                            if (ngocSiUnmuteAttempts >= 30 && window.__ngocSiUnmuteTimer) {
                              clearInterval(window.__ngocSiUnmuteTimer);
                              window.__ngocSiUnmuteTimer = null;
                            }
                          }
                          if (window.__ngocSiUnmuteTimer) clearInterval(window.__ngocSiUnmuteTimer);
                          removeMutePromptAndTrySound();
                          window.__ngocSiUnmuteTimer = setInterval(removeMutePromptAndTrySound, 700);

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
                            // temporarily paused during page lifecycle changes; score-only selection
                            // can then target the wrong element and leave the video surface frozen.
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
                            return rendered[0] || null;
                          }
                          window.__ngocSiFindActiveVideo = findActiveVideo;
                          // Do not change page layout or hide YouTube DOM nodes around the
                          // playing video; those changes can detach its rendering surface.
                          // Install the Android bridge only after YouTube creates a video element.
                          // The bridge reports state and avoids automatic pause/play watchdogs
                          // that can restart a buffering stream repeatedly.
                          // Intercept YouTube's fullscreen control and keep playback on the current WebView.
                           // Native custom-view handoff can leave video frozen after returning to portrait.
                           function installNgocSiFullscreenInterceptor() {
                             if (window.__ngocSiFullscreenInterceptorInstalled) return;
                             window.__ngocSiFullscreenInterceptorInstalled = true;
                             document.addEventListener('click', function(event) {
                               var path = [];
                               try {
                                 path = typeof event.composedPath === 'function'
                                   ? event.composedPath() : [event.target];
                               } catch (_) { path = [event.target]; }
                               var matched = false;
                               for (var i = 0; i < path.length; i++) {
                                 var node = path[i];
                                 if (!node || node.nodeType !== 1) continue;
                                 var cls = '';
                                 try { cls = String(node.className || '').toLowerCase(); } catch (_) {}
                                 var tag = String(node.tagName || '').toLowerCase();
                                 var label = '';
                                 try {
                                   label = [
                                     node.getAttribute('aria-label') || '',
                                     node.getAttribute('title') || '',
                                     node.getAttribute('data-title-no-tooltip') || '',
                                     node.getAttribute('data-tooltip-text') || ''
                                   ].join(' ').toLowerCase();
                                 } catch (_) {}
                                 if (cls.indexOf('ytp-fullscreen-button') >= 0 ||
                                     cls.indexOf('fullscreen-button') >= 0 ||
                                     tag.indexOf('fullscreen') >= 0 ||
                                     /full[ _-]?screen|toàn màn hình|thoát toàn màn hình/.test(label)) {
                                   matched = true;
                                   break;
                                 }
                               }
                               if (!matched) return;
                               event.preventDefault();
                               event.stopPropagation();
                               if (typeof event.stopImmediatePropagation === 'function') {
                                 event.stopImmediatePropagation();
                               }
                               try { AndroidBridge.onFullscreenToggle(); } catch (_) {}
                             }, true);
                           }
                           installNgocSiFullscreenInterceptor();

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
                            var lastCurrentTime = NaN;
                            var lastProgressAt = 0;
                            setInterval(function(){
                              var e = findActiveVideo();
                              if (!e) return;
                              if (e !== lastVideo) {
                                lastVideo = e;
                                lastState = -99;
                                lastCurrentTime = Number(e.currentTime || 0);
                                lastProgressAt = 0;
                              }
                              // Track rendered frames separately from currentTime. Chromium can
                              // keep audio/media time moving while the compositor image is frozen
                              // after orientation handoff; a media-clock probe alone misses that.
                              if (typeof e.requestVideoFrameCallback === 'function' &&
                                  window.__ngocSiFrameTrackedVideo !== e) {
                                window.__ngocSiFrameTrackedVideo = e;
                                window.__ngocSiLastVideoFrameAt = 0;
                                try {
                                  var trackRenderedFrame = function() {
                                    if (window.__ngocSiFrameTrackedVideo !== e) return;
                                    window.__ngocSiLastVideoFrameAt = Date.now();
                                    try { e.requestVideoFrameCallback(trackRenderedFrame); } catch (_) {}
                                  };
                                  e.requestVideoFrameCallback(trackRenderedFrame);
                                } catch (_) {}
                              }
                              var currentTime = Number(e.currentTime || 0);
                              if (!e.paused && !e.ended && e.readyState >= 2 &&
                                  Number.isFinite(lastCurrentTime) && currentTime > lastCurrentTime + 0.025) {
                                lastProgressAt = Date.now();
                              }
                              lastCurrentTime = currentTime;
                              var clockAdvancing = lastProgressAt > 0 &&
                                Date.now() - lastProgressAt < 1800 &&
                                !e.paused && !e.ended && e.readyState >= 2;
                              window.__ngocSiTrackedVideo = e;
                              window.__ngocSiPlaybackProgressAt = lastProgressAt;
                              window.__ngocSiPlaybackActuallyPlaying = clockAdvancing;
                              // Distinguish a stalled/buffering media element from one whose
                              // currentTime is really advancing. A stale "playing" flag used
                              // to make the Play button issue pauseVideo() after fullscreen.
                              var state = e.ended ? 0 : (e.paused ? 2 : (clockAdvancing ? 1 : 3));
                              if (state !== lastState) {
                                lastState = state;
                                try { AndroidBridge.onStateChanged(state); } catch (_) {}
                              }
                            }, 500);
                          }
                          installNgocSiBridge(0);

                          // The page can pause/replace its media node during a rotation. Capture
                          // the last real playback progress before that happens, then let native
                          // code recover the same player after its viewport has settled.
                          (function installNgocSiViewportPlaybackGuard() {
                            if (window.__ngocSiViewportPlaybackGuardInstalled) return;
                            window.__ngocSiViewportPlaybackGuardInstalled = true;
                            function findPlaybackControl(event) {
                              var path = event && typeof event.composedPath === 'function'
                                ? event.composedPath() : [event.target];
                              for (var i = 0; i < path.length; i++) {
                                var node = path[i];
                                if (!node || node.nodeType !== 1) continue;
                                var tag = String(node.tagName || '').toLowerCase();
                                var candidate = tag === 'button' ||
                                  node.getAttribute('role') === 'button' ||
                                  (node.matches && node.matches('.ytp-button,ytm-button-renderer'));
                                if (!candidate) continue;
                                var label = '';
                                try {
                                  label = [
                                    node.getAttribute('aria-label') || '',
                                    node.getAttribute('title') || '',
                                    node.getAttribute('data-tooltip-text') || '',
                                    node.getAttribute('data-title-no-tooltip') || '',
                                    node.innerText || node.textContent || '',
                                    typeof node.className === 'string' ? node.className : '',
                                    node.id || ''
                                  ].join(' ').toLowerCase().replace(/\s+/g, ' ').trim();
                                } catch (_) {}
                                if (/pause|tạm dừng|tam dung/.test(label)) return 'pause';
                                if (/\bplay\b|phát|resume|tiếp tục|tiep tuc/.test(label)) return 'play';
                              }
                              return '';
                            }
                            document.addEventListener('click', function(event) {
                              if (!event || !event.isTrusted) return;
                              var action = findPlaybackControl(event);
                              if (!action) return;
                              if (action === 'pause') {
                                window.__ngocSiExplicitPauseAt = Date.now();
                                try { AndroidBridge.onExplicitPauseRequested(); } catch (_) {}
                              } else {
                                window.__ngocSiExplicitPauseAt = 0;
                                try { AndroidBridge.onExplicitPlayRequested(); } catch (_) {}
                              }
                            }, true);

                            var lastHandoffAt = 0;
                            function onViewportChange() {
                              var now = Date.now();
                              if (now - lastHandoffAt < 500) return;
                              var explicitPauseAt = Number(window.__ngocSiExplicitPauseAt || 0);
                              if (explicitPauseAt > 0 && now - explicitPauseAt < 5000) return;
                              var video = null;
                              try {
                                if (typeof window.__ngocSiFindActiveVideo === 'function') {
                                  video = window.__ngocSiFindActiveVideo();
                                }
                              } catch (_) {}
                              var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                              var recentProgress = progressAt > 0 && now - progressAt < 1800;
                              var wasActuallyPlaying = window.__ngocSiPlaybackActuallyPlaying === true;
                              if (!video || video.ended || (!recentProgress && !wasActuallyPlaying)) return;
                              lastHandoffAt = now;
                              window.__ngocSiPlaybackIntentUntil = now + 5000;
                              try { AndroidBridge.onViewportPlaybackHandoff(); } catch (_) {}
                            }
                            window.addEventListener('orientationchange', onViewportChange, {passive:true});
                            window.addEventListener('resize', onViewportChange, {passive:true});
                            if (window.visualViewport) {
                              window.visualViewport.addEventListener('resize', onViewportChange, {passive:true});
                            }
                          })();
                        })();
                    """.trimIndent(), null)
                    // onPageFinished can run before the final Android viewport has been measured.
                    // Re-fit once after the CSS/bridge is installed; the sync is bounded, not a loop.
                    // YouTube Mobile may restore an old document scroll offset after its
                    // hidden masthead/title rows collapse. Normalize the WebView viewport after
                    // the final native measurement, not only the CSS player width.
                    listOf(350L, 850L, 1500L, 2600L).forEachIndexed { index, delay ->
                        view.postDelayed({
                            if (!isFinishing && !isDestroyed && !isCssFullscreen && webView === view) {
                                normalizeInlineYoutubeViewport(view)
                                if (index == 0) scheduleInlinePlayerLayoutSync()
                                if (index == 2) {
                                    // Opening YouTube can also leave the actual video surface
                                    // offset even when document scrollY is zero. Validate the
                                    // rendered geometry after the initial viewport has settled.
                                    val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                                    val expectPlayback = !explicitUserPause && (
                                        youtubePlayerState == 1 ||
                                            prefs.getBoolean("youtube_playback_is_playing", false)
                                        )
                                    scheduleFullscreenExitGeometryCheck(
                                        fullscreenExitGeneration,
                                        expectPlayback
                                    )
                                }
                            }
                        }, delay)
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
                    // Fallback if YouTube changes its button markup and the JS interceptor
                    // misses it. Never attach Chromium's separate video surface: that handoff
                    // is unreliable after orientation changes. Stay on the same WebView.
                    suppressNextCustomViewHide = true
                    runCatching { callback.onCustomViewHidden() }
                    if (!isFinishing && !isDestroyed && !isCssFullscreen) {
                        root.post {
                            if (!isFinishing && !isDestroyed && !isCssFullscreen && webView != null) {
                                requestYoutubeFullscreen()
                            }
                        }
                    }
                }

                override fun onHideCustomView() {
                    // onShowCustomView deliberately dismissed Chromium's separate surface so
                    // the same WebView can stay alive. Ignore only that paired callback; later
                    // real fullscreen exits still run the normal cleanup/recovery path.
                    if (suppressNextCustomViewHide) {
                        suppressNextCustomViewHide = false
                        return
                    }
                    exitFullscreen(notifyCallback = false)
                }
            }

            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(youtubeJsBridge, "AndroidBridge")

            // Load YouTube's mobile site in this embedded WebView. Background mode
            // keeps this same player alive and uses bounded recovery if the page pauses.
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
        root.findViewWithTag<TextView>("youtube_queue_hint")?.text = "☷  HÀNG ĐỢI  •  " + positionText
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

    /**
     * Recalculate the inline player after orientation transitions. Android keeps this
     * Activity alive (configChanges), so a single measurement can still use the previous
     * orientation's width. A small, bounded series of layout passes lets root/WebView
     * measurements settle without a recurring timer that races YouTube's compositor.
     */
    private fun scheduleInlinePlayerLayoutSync() {
        if (!::root.isInitialized || !::playerContainer.isInitialized) return
        val generation = ++inlineLayoutSyncGeneration
        val sync = object : Runnable {
            override fun run() {
                if (generation != inlineLayoutSyncGeneration ||
                    isFinishing || isDestroyed || customView != null || isCssFullscreen
                ) return

                // Returning from fullscreen must restore both scroll containers to the same
                // top-aligned inline player, not preserve a stale portrait/landscape offset.
                playerScrollView?.scrollTo(0, 0)
                webView?.scrollTo(0, 0)

                // The aspect-ratio host derives height from its measured width.
                // Do not write a second height from screen/configuration metrics here.
                root.requestLayout()
                playerContainer.requestLayout()

                webView?.post {
                    if (generation != inlineLayoutSyncGeneration ||
                        isFinishing || isDestroyed || webView == null ||
                        customView != null || isCssFullscreen
                    ) return@post
                    // Share one normalization path with initial load so scroll offsets,
                    // translated wrappers and stale landscape width/height cannot diverge.
                    webView?.let { normalizeInlineYoutubeViewport(it) }
                }
            }
        }
        root.post(sync)
        root.postDelayed(sync, 120L)
        root.postDelayed(sync, 360L)
        root.postDelayed(sync, 700L)
    }

    /**
     * Normalize the mobile YouTube document when opening the player or returning to portrait.
     * YouTube can restore a non-zero internal scroll offset after its own top bars are hidden;
     * the native WebView then looks like the video has slipped down inside the 16:9 frame.
     * Only run this while inline; never scroll or rewrite the document during fullscreen.
     */
    private fun normalizeInlineYoutubeViewport(player: WebView) {
        if (isFinishing || isDestroyed || player !== webView || isCssFullscreen || customView != null) return
        playerScrollView?.scrollTo(0, 0)
        player.scrollTo(0, 0)
        player.evaluateJavascript(
            """
            (function() {
              try {
                if (document.getElementById('ngocsi-css-fullscreen') || document.fullscreenElement)
                  return 'FULLSCREEN';
                var root = document.querySelector('ytm-player') ||
                           document.querySelector('#movie_player') ||
                           document.querySelector('#player');
                if (!root) return 'WAIT';
                // The hidden mobile header leaves a few YouTube page wrappers with stale
                // top spacing. Clear only top spacing on known player wrappers.
                var wrappers = [
                  'ytm-watch',
                  'ytm-watch-grid',
                  '#player-container-outer',
                  '#player-container-inner',
                  '#player-container-id',
                  '#player-container',
                  '#player'
                ];
                wrappers.forEach(function(selector) {
                  var node = root.matches && root.matches(selector) ? root : root.closest(selector);
                  if (!node) {
                    try { node = document.querySelector(selector); } catch (_) {}
                  }
                  if (node && node.style) {
                    node.style.setProperty('margin-top', '0px', 'important');
                    node.style.setProperty('padding-top', '0px', 'important');
                    node.style.setProperty('scroll-margin-top', '0px', 'important');
                    // Hidden mobile masthead transitions can leave a translated player wrapper.
                    node.style.setProperty('transform', 'none', 'important');
                    node.style.setProperty('border-top-width', '0px', 'important');
                  }
                });
                // Drop stale fullscreen/landscape pixel coordinates before measuring the
                // current WebView. The fit handler writes new dimensions only after the
                // latest viewport has committed, rather than preserving a one-frame black gap.
                ['position','left','right','top','bottom','inset','width','height',
                 'margin-left','margin-right'].forEach(function(prop) {
                  root.style.removeProperty(prop);
                });
                root.style.setProperty('transform', 'none', 'important');
                root.style.setProperty('margin-top', '0px', 'important');
                root.style.setProperty('padding-top', '0px', 'important');
                if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                document.documentElement.scrollTop = 0;
                if (document.body) document.body.scrollTop = 0;
                window.scrollTo(0, 0);
                if (typeof window.__ngocSiFitPlayerResizeHandler === 'function') {
                  window.__ngocSiFitPlayerResizeHandler();
                } else {
                  window.dispatchEvent(new Event('resize'));
                }
                requestAnimationFrame(function() {
                  window.scrollTo(0, 0);
                  if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                });
                return 'NORMALIZED';
              } catch (_) {
                return 'NORMALIZE_ERROR';
              }
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Re-fit the same YouTube surface to the latest WebView viewport during CSS fullscreen.
     * On Android, configuration callbacks can arrive before Chromium has committed its new
     * viewport. A few delayed measurements avoid retaining the previous portrait dimensions.
     */
    private fun scheduleCssFullscreenLayoutSync() {
        if (!::root.isInitialized || !::playerContainer.isInitialized) return
        val player = webView ?: return
        val generation = ++fullscreenViewportSyncGeneration
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val shouldRecover = resumePlaybackOnNextOrientationChange ||
            wasPlayingBeforeFullscreen || prefs.getBoolean("youtube_playback_is_playing", false)

        val sync = object : Runnable {
            override fun run() {
                if (generation != fullscreenViewportSyncGeneration ||
                    isFinishing || isDestroyed || !isCssFullscreen || webView !== player
                ) return

                root.requestLayout()
                playerContainer.requestLayout()
                player.requestLayout()
                player.evaluateJavascript(
                    """
                    (function() {
                      try {
                        var width = Math.max(1, document.documentElement.clientWidth || window.innerWidth || 1);
                        var height = Math.max(1, document.documentElement.clientHeight || window.innerHeight || 1);
                        var target = document.querySelector('ytm-player') ||
                                     document.querySelector('#movie_player') ||
                                     document.querySelector('#player');
                        if (target) {
                          target.style.setProperty('position', 'fixed', 'important');
                          target.style.setProperty('left', '0', 'important');
                          target.style.setProperty('right', '0', 'important');
                          target.style.setProperty('top', '0', 'important');
                          target.style.setProperty('bottom', '0', 'important');
                          target.style.setProperty('width', width + 'px', 'important');
                          target.style.setProperty('height', height + 'px', 'important');
                          target.style.setProperty('max-width', '100%', 'important');
                          target.style.setProperty('max-height', '100%', 'important');
                        }
                        if (typeof window.__ngocSiFitPlayerResizeHandler === 'function') {
                          window.__ngocSiFitPlayerResizeHandler();
                        } else {
                          window.dispatchEvent(new Event('resize'));
                        }
                        return width + 'x' + height;
                      } catch (_) { return 'layout-error'; }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        root.post(sync)
        root.postDelayed(sync, 140L)
        root.postDelayed(sync, 380L)
        root.postDelayed(sync, 720L)

        if (shouldRecover) {
            player.postDelayed({
                if (generation == fullscreenViewportSyncGeneration &&
                    !isFinishing && !isDestroyed && isCssFullscreen && webView === player
                ) {
                    // Recovery probes the existing media element and does not reload the page.
                    recoverFullscreenPlayback(allowPageReloadFallback = false)
                }
            }, 900L)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        lastOrientationConfigurationChangeAtMs = System.currentTimeMillis()
        // Invalidate a WebView resize callback that may already have scheduled recovery.
        orientationPlaybackRecoveryGeneration++
        val playbackPrefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val intendedBeforeConfigurationChange = !explicitUserPause && (
            youtubePlayerState == 1 ||
                resumePlaybackOnNextOrientationChange ||
                wasPlayingBeforeFullscreen ||
                playbackPrefs.getBoolean("youtube_playback_is_playing", false)
            )
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 5000L
        if (intendedBeforeConfigurationChange) {
            resumePlaybackOnNextOrientationChange = true
            playbackPrefs.edit().putBoolean("youtube_playback_is_playing", true).apply()
        }

        // The manifest handles orientation changes without recreating this Activity.
        if (!::playerContainer.isInitialized || !::root.isInitialized) return

        if (isCssFullscreen) {
            scheduleCssFullscreenLayoutSync()
            return
        }

        // Chromium's fullscreen custom view is a separate surface. Re-layout it after
        // configuration changes instead of leaving its original portrait measurements.
        customView?.let { fullscreenView ->
            root.post {
                if (customView === fullscreenView && !isFinishing && !isDestroyed) {
                    fullscreenView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.FILL
                    )
                    root.requestLayout()
                    fullscreenView.requestLayout()
                    root.bringChildToFront(fullscreenView)

                    // Switching to landscape can pause the HTML5 video even though the
                    // fullscreen custom view is still attached. If it was playing before
                    // fullscreen, request one bounded Play recovery after the new surface
                    // has settled instead of making the user tap Play a second time.
                    val shouldResume = wasPlayingBeforeFullscreen
                    fullscreenView.postDelayed({
                        if (customView === fullscreenView && shouldResume &&
                            !isFinishing && !isDestroyed && webView != null
                        ) {
                            webView?.onResume()
                            webView?.resumeTimers()
                            resumeVideoAfterFullscreenRotation()
                        }
                    }, 500L)
                }
            }
            return
        }
        scheduleInlinePlayerLayoutSync()
        if (intendedBeforeConfigurationChange && !fullscreenExitRecoveryPending) {
            // Check the actual video surface after the new viewport has settled. A healthy
            // media clock alone cannot detect a compositor frame frozen after rotation.
            scheduleFullscreenExitGeometryCheck(fullscreenExitGeneration, true)
        }
        val player = webView
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        if (player != null && !fullscreenExitRecoveryPending &&
            System.currentTimeMillis() > fullscreenExitRecoveryActiveUntilMs &&
            (resumePlaybackOnNextOrientationChange ||
                prefs.getBoolean("youtube_playback_is_playing", false))) {
            val generation = ++fullscreenViewportSyncGeneration
            player.postDelayed({
                if (generation != fullscreenViewportSyncGeneration ||
                    isFinishing || isDestroyed || webView !== player ||
                    customView != null || isCssFullscreen
                ) return@postDelayed
                if (resumePlaybackOnNextOrientationChange ||
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                        .getBoolean("youtube_playback_is_playing", false)
                ) {
                    // Orientation changes can pause the same video element without ending it.
                    // Restore playback only when the pre-rotation state was active.
                    recoverFullscreenPlayback(allowPageReloadFallback = false)
                }
            }, 850L)
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit().putBoolean("youtube_player_foreground", true).apply()

        // Resume the existing WebView without reloading the selected YouTube page.
        // A normal Play request leaves a progressing video alone and asks YouTube
        // to play only when the media element is actually paused.
        webView?.onResume()
        webView?.resumeTimers()
        if (youtubePlayerReady && webView != null) {
            startPlaybackPositionSaver()
            val wasPlaying = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                .getBoolean("youtube_playback_is_playing", false)
            if (youtubePlayerState == 1) {
                syncYouTubeBackgroundPlayback(1)
            } else if (wasPlaying) {
                webView?.postDelayed({
                    if (!isFinishing && !isDestroyed && youtubePlayerReady && webView != null &&
                        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                            .getBoolean("youtube_playback_is_playing", false)
                    ) requestYouTubePlayback()
                }, 700L)
            }
        }
    }

    override fun onPause() {
        // Mark the app as backgrounded before the page can report its visibility pause.
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .edit().putBoolean("youtube_player_foreground", false).apply()
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
        // pressure and relaunch. Background audio is managed by the media service.
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

    private fun setFullscreenLayout(active: Boolean) {
        val content = contentContainer ?: return
        if (active == fullscreenLayoutActive) return

        if (active) {
            inlineContentPadding = intArrayOf(
                content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom
            )
            fullscreenHiddenChildVisibilities.clear()
            for (index in 0 until content.childCount) {
                val child = content.getChildAt(index)
                if (child !== playerContainer) {
                    fullscreenHiddenChildVisibilities[child] = child.visibility
                    child.visibility = View.GONE
                }
            }
            inlineLoadingBarVisibility = loadingBar?.visibility
            loadingBar?.visibility = View.GONE
            content.setPadding(0, 0, 0, 0)
            fullscreenLayoutActive = true
            playerContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            playerScrollView?.scrollTo(0, 0)
        } else {
            fullscreenLayoutActive = false
            fullscreenHiddenChildVisibilities.forEach { (view, visibility) ->
                view.visibility = visibility
            }
            fullscreenHiddenChildVisibilities.clear()
            inlineContentPadding?.let { padding ->
                if (padding.size == 4) content.setPadding(padding[0], padding[1], padding[2], padding[3])
            }
            inlineContentPadding = null
            inlinePlayerLayoutParams?.let { playerContainer.layoutParams = it }
            inlineLoadingBarVisibility?.let { loadingBar?.visibility = it }
            inlineLoadingBarVisibility = null
        }

        playerContainer.requestLayout()
        content.requestLayout()
        playerScrollView?.requestLayout()
        if (::root.isInitialized) root.requestLayout()
    }

    private fun enterFullscreen() {
        // The existing WebView stays attached; only sibling UI is hidden so the player
        // receives the full activity viewport rather than the space below the header/actions.
        setFullscreenLayout(true)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen(notifyCallback: Boolean = true) {
        // A hardware Back/custom-view callback can report the same exit twice. Ignore
        // duplicate exits; they otherwise schedule competing recovery/reload attempts.
        if (customView == null && !isCssFullscreen) return
        val exitGeneration = ++fullscreenExitGeneration
        fullscreenExitRecoveryPending = true
        fullscreenExitRecoveryActiveUntilMs = System.currentTimeMillis() + 2300L
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 5000L
        // Invalidate older recovery/layout probes. Configuration callbacks may enqueue
        // another CSS pass while we wait for the WebView to finish removing fullscreen CSS.
        fullscreenRecoveryGeneration++
        fullscreenViewportSyncGeneration++

        val wasNativeFullscreen = customView != null
        val wasCssFullscreen = isCssFullscreen
        val persistedPlaybackIntent = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .getBoolean("youtube_playback_is_playing", false)
        val playbackIntentBeforeExit = wasPlayingBeforeFullscreen ||
            resumePlaybackOnNextOrientationChange ||
            persistedPlaybackIntent
        val shouldResumeAfterNativeFullscreen =
            wasNativeFullscreen && playbackIntentBeforeExit
        val shouldResumeAfterCssFullscreen =
            wasCssFullscreen && playbackIntentBeforeExit
        // Do not turn a deliberate user pause into an automatic play on the fullscreen callback.
        val shouldRecoverPlayback =
            !explicitUserPause &&
                (shouldResumeAfterNativeFullscreen || shouldResumeAfterCssFullscreen)
        if (shouldRecoverPlayback) {
            resumePlaybackOnNextOrientationChange = true
            getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                .putBoolean("youtube_playback_is_playing", true)
                .apply()
        }
        wasPlayingBeforeFullscreen = false

        fun finishExitLayout() {
            if (exitGeneration != fullscreenExitGeneration || isFinishing || isDestroyed) return

            // A configuration change may have scheduled CSS-only resize passes while the
            // fullscreen style was being removed. Invalidate them before shrinking the host.
            fullscreenViewportSyncGeneration++
            isCssFullscreen = false

            // Detach the fullscreen custom view from whichever parent currently owns it.
            // Chromium/rotation can change callback order; remove it from its actual parent.
            val fullscreenView = customView
            customView = null
            fullscreenView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                if (view.parent != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                view.visibility = View.GONE
            }
            if (notifyCallback) {
                customViewCallback?.onCustomViewHidden()
            }
            customViewCallback = null
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerContainer.visibility = View.VISIBLE
            setFullscreenLayout(false)
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())

            // Keep native 16:9 restoration ordered after the WebView confirms that
            // ngocsi-css-fullscreen was removed. Previously the host was shrunk first,
            // racing Chromium's fullscreen video surface and leaving a frozen frame.
            scheduleInlinePlayerLayoutSync()
            scheduleFullscreenExitGeometryCheck(exitGeneration, shouldRecoverPlayback)

            playerContainer.post {
                if (exitGeneration != fullscreenExitGeneration ||
                    isFinishing || isDestroyed || webView == null
                ) return@post
                playerContainer.requestLayout()
                webView?.post {
                    if (exitGeneration != fullscreenExitGeneration ||
                        isFinishing || isDestroyed || webView == null
                    ) return@post
                    webView?.scrollTo(0, 0)
                    webView?.evaluateJavascript(
                        "(function(){window.scrollTo(0,0);window.dispatchEvent(new Event('resize'));return 'inline';})();",
                        null
                    )
                    webView?.postDelayed({
                        if (exitGeneration != fullscreenExitGeneration) return@postDelayed
                        fullscreenExitRecoveryPending = false
                        if (shouldRecoverPlayback) {
                            // Use exactly one recovery controller after layout has settled.
                            // It checks real media-clock progress and keeps page reload as last resort.
                            webView?.postDelayed({
                                if (exitGeneration != fullscreenExitGeneration ||
                                    isFinishing || isDestroyed || webView == null ||
                                    !youtubePlayerReady || geometryReloadFallbackAttemptedForCurrentPage
                                ) return@postDelayed
                                // Let the geometry checker finish its two layout/frame probes first.
                                // If it reloads the page, onReady owns recovery; do not send a second
                                // play command into the old/new media surface during that handoff.
                                recoverFullscreenPlayback(allowPageReloadFallback = true)
                            }, 2300L)
                        }
                    }, 500L)
                }
            }
        }

        if (wasCssFullscreen) {
            val player = webView
            if (player == null) {
                isCssFullscreen = false
                finishExitLayout()
                return
            }
            // Do not change Android's host size/orientation yet. evaluateJavascript is async;
            // the callback is the first point where we know this script actually ran.
            player.evaluateJavascript(
                """
                (function() {
                  var style = document.getElementById('ngocsi-css-fullscreen');
                  if (style) style.remove();
                  document.documentElement.style.removeProperty('width');
                  document.documentElement.style.removeProperty('height');
                  if (document.body) {
                    document.body.style.removeProperty('width');
                    document.body.style.removeProperty('height');
                  }
                  // The responsive handler writes explicit landscape pixel dimensions to
                  // ytm-player. Remove those stale inline values before Android shrinks the
                  // host; otherwise Chromium can keep rendering at the old landscape rect.
                  var playerRoot = document.querySelector('ytm-player') ||
                                   document.querySelector('#movie_player') ||
                                   document.querySelector('#player');
                  if (playerRoot) {
                    ['position','left','right','top','bottom','inset','width','height'].forEach(function(prop) {
                      playerRoot.style.removeProperty(prop);
                    });
                  }
                  window.scrollTo(0,0);
                  if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                  // Do not dispatch resize yet: Android has not restored the portrait host
                  // at this point. The native inline-layout sync will dispatch it after exit.
                  return 'layout-restored';
                })();
                """.trimIndent()
            ) { _ ->
                if (exitGeneration != fullscreenExitGeneration || isFinishing || isDestroyed) {
                    return@evaluateJavascript
                }
                // Now the fullscreen stylesheet is gone; only then allow the native frame to shrink.
                isCssFullscreen = false
                finishExitLayout()
            }
            return
        }

        finishExitLayout()
    }

    /**
     * Check the visible HTML5 video's bounds after leaving landscape/fullscreen.
     * One resize pass is attempted first. If the video remains heavily offset or only
     * occupies a fraction of the inline WebView, reload the same YouTube URL once at
     * the last known timestamp. This catches the "audio plays / frame stuck in a corner"
     * failure which does not necessarily stop currentTime from advancing.
     */
    private fun scheduleFullscreenExitGeometryCheck(
        exitGeneration: Int,
        shouldResumePlayback: Boolean
    ) {
        val player = webView ?: return
        val checkGeneration = ++fullscreenExitGeometryCheckGeneration
        val expectedVideoId = sanitizeVideoId(videoId)
        val geometryScript = """
            (function() {
              try {
                var video = null;
                try {
                  if (typeof window.__ngocSiFindActiveVideo === 'function')
                    video = window.__ngocSiFindActiveVideo();
                } catch (_) {}
                if (!video || !video.isConnected ||
                    video.getBoundingClientRect().width < 2) {
                  var candidates = Array.prototype.slice.call(document.querySelectorAll('video'));
                  video = candidates.find(function(candidate) {
                    if (!candidate.isConnected || candidate.getBoundingClientRect().width < 2) return false;
                    return candidate.classList.contains('html5-main-video') ||
                      !!candidate.closest('#movie_player') ||
                      !!candidate.closest('ytm-player');
                  }) || candidates.find(function(candidate) {
                    return candidate.isConnected && candidate.getBoundingClientRect().width >= 2;
                  }) || null;
                }
                var viewportWidth = Number(document.documentElement.clientWidth || window.innerWidth || 0);
                var viewportHeight = Number(document.documentElement.clientHeight || window.innerHeight || 0);
                var scrollY = Number(window.scrollY || (document.scrollingElement && document.scrollingElement.scrollTop) || 0);
                var root = document.querySelector('ytm-player') ||
                           document.querySelector('#player-container') ||
                           document.querySelector('#movie_player');
                if (!video || !root || viewportWidth < 100 || viewportHeight < 100) return 'WAIT';
                var vr = video.getBoundingClientRect();
                var rr = root.getBoundingClientRect();
                if (vr.width < 2 || rr.width < 2 || Number(video.readyState || 0) < 1)
                  return 'WAIT';
                return JSON.stringify({
                  viewportWidth: viewportWidth,
                  viewportHeight: viewportHeight,
                  scrollY: scrollY,
                  videoLeft: Number(vr.left || 0),
                  videoTop: Number(vr.top || 0),
                  videoWidth: Number(vr.width || 0),
                  videoHeight: Number(vr.height || 0),
                  rootLeft: Number(rr.left || 0),
                  rootTop: Number(rr.top || 0),
                  rootWidth: Number(rr.width || 0),
                  rootHeight: Number(rr.height || 0),
                  mediaWidth: Number(video.videoWidth || 0),
                  mediaHeight: Number(video.videoHeight || 0),
                  videoPaused: !!video.paused,
                  hasVideoFrameCallback: typeof video.requestVideoFrameCallback === 'function',
                  videoFrameAgeMs: window.__ngocSiFrameTrackedVideo === video &&
                    Number(window.__ngocSiLastVideoFrameAt || 0) > 0
                    ? Date.now() - Number(window.__ngocSiLastVideoFrameAt || 0) : -1,
                  position: Number(video.currentTime || 0)
                });
              } catch (_) { return 'WAIT'; }
            })();
        """.trimIndent()
        val repairLayoutScript = """
            (function() {
              try {
                if (document.getElementById('ngocsi-css-fullscreen') || document.fullscreenElement)
                  return 'FULLSCREEN';
                var root = document.querySelector('ytm-player') ||
                           document.querySelector('#movie_player') ||
                           document.querySelector('#player');
                if (root) {
                  ['position','left','right','top','bottom','inset'].forEach(function(prop) {
                    root.style.removeProperty(prop);
                  });
                  ['ytm-watch','ytm-watch-grid','#player-container-outer',
                   '#player-container-inner','#player-container-id','#player-container','#player']
                    .forEach(function(selector) {
                      var node = root.matches && root.matches(selector) ? root : root.closest(selector);
                      if (node && node.style) {
                        node.style.setProperty('margin-top','0px','important');
                        node.style.setProperty('padding-top','0px','important');
                      }
                    });
                }
                window.scrollTo(0,0);
                if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                document.documentElement.scrollTop = 0;
                if (document.body) document.body.scrollTop = 0;
                if (typeof window.__ngocSiFitPlayerResizeHandler === 'function')
                  window.__ngocSiFitPlayerResizeHandler();
                window.dispatchEvent(new Event('resize'));
                if (window.visualViewport)
                  window.visualViewport.dispatchEvent(new Event('resize'));
                requestAnimationFrame(function() { window.scrollTo(0,0); });
                return 'OK';
              } catch (_) { return 'ERROR'; }
            })();
        """.trimIndent()

        fun checkIsCurrent(): Boolean =
            checkGeneration == fullscreenExitGeometryCheckGeneration &&
                exitGeneration == fullscreenExitGeneration &&
                !isFinishing && !isDestroyed && player === webView &&
                !isCssFullscreen && customView == null && !explicitUserPause

        fun decodeGeometry(raw: String?): org.json.JSONObject? {
            val normalized = raw.orEmpty().trim('"').replace("\\\"", "\"")
            return runCatching { org.json.JSONObject(normalized) }.getOrNull()
        }

        fun isGeometryBroken(
            info: org.json.JSONObject?,
            expectPlayback: Boolean
        ): Boolean {
            if (info == null) return false
            val viewportWidth = info.optDouble("viewportWidth", 0.0)
            val viewportHeight = info.optDouble("viewportHeight", 0.0)
            val scrollY = info.optDouble("scrollY", 0.0)
            val videoWidth = info.optDouble("videoWidth", 0.0)
            val videoTop = info.optDouble("videoTop", 0.0)
            val rootWidth = info.optDouble("rootWidth", 0.0)
            val rootTop = info.optDouble("rootTop", 0.0)
            val videoLeft = info.optDouble("videoLeft", 0.0)
            val rootLeft = info.optDouble("rootLeft", 0.0)
            val mediaWidth = info.optDouble("mediaWidth", 0.0)
            val mediaHeight = info.optDouble("mediaHeight", 0.0)
            val videoPaused = info.optBoolean("videoPaused", true)
            val hasFrameCallback = info.optBoolean("hasVideoFrameCallback", false)
            val videoFrameAgeMs = info.optDouble("videoFrameAgeMs", -1.0)
            if (viewportWidth < 100.0 || viewportHeight < 100.0 ||
                videoWidth < 2.0 || rootWidth < 2.0
            ) return false
            // Portrait and near-square clips may be narrower by design. Still check their
            // vertical placement, because a document scroll offset can push any aspect ratio down.
            val portraitMedia = mediaWidth > 0.0 && mediaHeight > 0.0 &&
                mediaWidth / mediaHeight < 1.15
            // Detect the document-scroll restoration and a player root that stays below the
            // top of the newly measured portrait viewport.
            // Mobile YouTube can retain even a small (20-40 px) scroll offset after
            // the hidden masthead is removed. Use a tight threshold, not 10% of the screen,
            // otherwise the exact "slightly slipped down" report is treated as healthy.
            val topThreshold = maxOf(12.0, viewportHeight * 0.025)
            val verticalBroken = scrollY > 2.0 || rootTop > topThreshold ||
                rootTop < -topThreshold || videoTop > topThreshold + 8.0
            // If the player claims to be playing but no rendered video frame has arrived
            // recently, reload the same item once after the layout repair. This covers the
            // frozen-picture/audio-still-moving case that currentTime-only recovery misses.
            val renderedFrameStalled = expectPlayback && !videoPaused &&
                hasFrameCallback && videoFrameAgeMs > 1500.0
            return renderedFrameStalled || verticalBroken || (!portraitMedia && (
                videoWidth < viewportWidth * 0.78 ||
                videoLeft > viewportWidth * 0.12 ||
                videoLeft < -viewportWidth * 0.12 ||
                rootWidth < viewportWidth * 0.78 ||
                rootLeft > viewportWidth * 0.12 ||
                rootLeft < -viewportWidth * 0.12
            ))
        }

        fun inspectAndRecover(allowOneLayoutRepair: Boolean) {
            if (!checkIsCurrent()) return
            player.evaluateJavascript(geometryScript) { raw ->
                if (!checkIsCurrent()) return@evaluateJavascript
                val info = decodeGeometry(raw)
                if (!isGeometryBroken(info, shouldResumePlayback)) {
                    if (allowOneLayoutRepair && shouldResumePlayback) {
                        // A healthy geometry snapshot cannot prove the compositor is still
                        // delivering new frames. Probe once more after another 850 ms so the
                        // frame-age check catches a freeze that begins just after the first read.
                        player.postDelayed({
                            inspectAndRecover(allowOneLayoutRepair = false)
                        }, 850L)
                    }
                    return@evaluateJavascript
                }
                if (allowOneLayoutRepair) {
                    // Give Chromium one final chance to re-measure its new portrait viewport
                    // before using the heavier page-recreation fallback.
                    player.evaluateJavascript(repairLayoutScript, null)
                    playerContainer.requestLayout()
                    player.requestLayout()
                    player.postDelayed({ inspectAndRecover(allowOneLayoutRepair = false) }, 420L)
                    return@evaluateJavascript
                }

                // The geometry inspector can run after initial load, rotation and fullscreen
                // exit. Share one fallback budget across all inspectors so a persistently bad
                // WebView surface never creates a reload loop.
                if (geometryReloadFallbackAttemptedForCurrentPage) return@evaluateJavascript

                val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                val shouldAutoplay = shouldResumePlayback &&
                    !explicitUserPause &&
                    prefs.getBoolean("youtube_playback_is_playing", false)
                val currentUrl = player.url
                if (expectedVideoId.isBlank() || currentUrl.isNullOrBlank() ||
                    !(currentUrl.contains("youtube.com/watch") ||
                      currentUrl.contains("youtube.com/shorts/"))
                ) return@evaluateJavascript
                geometryReloadFallbackAttemptedForCurrentPage = true

                val seconds = (info?.optDouble("position", 0.0) ?: 0.0)
                    .takeIf { it.isFinite() && it >= 0.0 }
                    ?.toInt()?.coerceAtLeast(0) ?: 0
                val builder = Uri.parse(currentUrl).buildUpon()
                    .clearQuery()
                    .appendQueryParameter("v", expectedVideoId)
                    .appendQueryParameter("t", "${seconds}s")
                    .appendQueryParameter("playsinline", "1")
                if (shouldAutoplay) builder.appendQueryParameter("autoplay", "1")

                // Invalidate the slower clock recovery before reloading so the old WebView
                // state cannot send a competing Play/Pause command into the replacement page.
                fullscreenRecoveryGeneration++
                fullscreenExitRecoveryPending = false
                fullscreenExitGeometryCheckGeneration++
                recoverAfterPageReload = shouldAutoplay
                youtubePlayerReady = false
                youtubePlayerState = if (shouldAutoplay) 3 else 2
                updatePlaybackButton()
                player.postDelayed({
                    if (!isFinishing && !isDestroyed && player === webView &&
                        exitGeneration == fullscreenExitGeneration
                    ) {
                        player.loadUrl(builder.build().toString())
                    }
                }, 120L)
            }
        }

        player.postDelayed({ inspectAndRecover(allowOneLayoutRepair = true) }, 820L)
    }

    /**
     * Resume the same HTML5 video after the native YouTube fullscreen surface rotates.
     * Do not use the normal visible-video selector here: Chrome may temporarily move
     * rendering into WebChromeClient's custom view, leaving the DOM video's bounds at zero.
     * This is a single bounded request and never reloads the page or changes the video ID.
     */
    private fun resumeVideoAfterFullscreenRotation() {
        if (isFinishing || isDestroyed || webView == null) return
        webView?.evaluateJavascript(
            """
            (function() {
              try {
                var video = document.querySelector('video.html5-main-video') ||
                            document.querySelector('#movie_player video') ||
                            document.querySelector('ytm-player video') ||
                            document.querySelector('video');
                var player = document.querySelector('#movie_player') ||
                             document.querySelector('.html5-video-player') ||
                             document.querySelector('ytm-player');
                if (!video) {
                  if (player && typeof player.playVideo === 'function') {
                    player.playVideo();
                    return 'requested-api';
                  }
                  return 'no-video';
                }

                var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                var clockStalled = !video.paused &&
                  (!progressAt || Date.now() - progressAt > 1800);
                if (!video.paused && !clockStalled) return 'already-playing';

                // If Chromium left the media element logically playing with a frozen clock,
                // reset only that same element's media pipeline. Avoid calling both API and
                // video.play() for the same request, which caused stutter in prior versions.
                if (clockStalled) {
                  // Do not pause and replay synchronously during an orientation handoff:
                  // that can reset the audio pipeline and cause a brief stutter followed by a stop.
                  // Reassert playback through exactly one transport path instead.
                  if (player && typeof player.playVideo === 'function') {
                    try { player.playVideo(); return 'requested-api'; }
                    catch (_) { return 'play-rejected'; }
                  }
                  try {
                    var promise = video.play();
                    if (promise && typeof promise.catch === 'function') promise.catch(function(){});
                    return 'requested-video';
                  } catch (_) {
                    return 'play-rejected';
                  }
                }

                if (player && typeof player.playVideo === 'function') {
                  player.playVideo();
                  return 'requested-api';
                }
                try {
                  var fallbackPromise = video.play();
                  if (fallbackPromise && typeof fallbackPromise.catch === 'function') {
                    fallbackPromise.catch(function(){});
                  }
                  return 'requested-video';
                } catch (_) {
                  return 'play-rejected';
                }
              } catch (_) {
                return 'recovery-error';
              }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun recoverFullscreenPlayback(allowPageReloadFallback: Boolean = true) {
        if (isFinishing || isDestroyed || webView == null || explicitUserPause) return
        val generation = ++fullscreenRecoveryGeneration
        var previousPosition: Double? = null
        var playRequested = false
        var fallbackNudgeSent = false
        var pageReloadFallbackUsed = !allowPageReloadFallback

        // Re-arm foreground WebView processing after the orientation/layout transition.
        webView?.onResume()
        webView?.resumeTimers()

        fun attempt(number: Int) {
            if (generation != fullscreenRecoveryGeneration ||
                explicitUserPause || isFinishing || isDestroyed || webView == null
            ) return

            // First request Play only if the media element is genuinely paused.
            // Treat a non-paused but stale state as a stall only after several real-time probes.
            val mode = when {
                number == 0 && !playRequested -> "initial"
                number >= 4 && !fallbackNudgeSent -> "fallback"
                else -> "probe"
            }
            if (mode == "fallback") fallbackNudgeSent = true

            val script = """
                (function(mode) {
                  try {
                    var video = null;
                    try {
                      if (typeof window.__ngocSiFindActiveVideo === 'function')
                        video = window.__ngocSiFindActiveVideo();
                    } catch (_) {}
                    function isRenderable(candidate) {
                      if (!candidate || !candidate.isConnected) return false;
                      var rect = candidate.getBoundingClientRect();
                      if (rect.width < 2 || rect.height < 2) return false;
                      var node = candidate;
                      while (node && node.nodeType === 1 && node !== document.documentElement) {
                        var style = window.getComputedStyle(node);
                        if (style.display === 'none' || style.visibility === 'hidden' ||
                            Number(style.opacity || 1) === 0) return false;
                        node = node.parentElement;
                      }
                      return true;
                    }
                    function isAttachedNonHidden(candidate) {
                      if (!candidate || !candidate.isConnected) return false;
                      var node = candidate;
                      while (node && node.nodeType === 1 && node !== document.documentElement) {
                        var style = window.getComputedStyle(node);
                        if (style.display === 'none' || style.visibility === 'hidden' ||
                            Number(style.opacity || 1) === 0) return false;
                        node = node.parentElement;
                      }
                      return true;
                    }
                    if (!isRenderable(video)) {
                      var candidates = Array.prototype.slice.call(document.querySelectorAll('video'));
                      video = candidates.find(function(candidate) {
                        return isRenderable(candidate) &&
                          (candidate.classList.contains('html5-main-video') ||
                           !!candidate.closest('#movie_player') ||
                           !!candidate.closest('.html5-video-player') ||
                           !!candidate.closest('ytm-player'));
                      }) || candidates.find(isRenderable) ||
                        candidates.find(function(candidate) {
                          return isAttachedNonHidden(candidate) &&
                            (candidate.classList.contains('html5-main-video') ||
                             !!candidate.closest('#movie_player') ||
                             !!candidate.closest('.html5-video-player') ||
                             !!candidate.closest('ytm-player'));
                        }) || null;
                    }
                    if (!video) {
                      var apiOnlyPlayer = document.querySelector('#movie_player') ||
                                          document.querySelector('.html5-video-player') ||
                                          document.querySelector('ytm-player');
                      if ((mode === 'initial' || mode === 'fallback') &&
                          apiOnlyPlayer && typeof apiOnlyPlayer.playVideo === 'function') {
                        try { apiOnlyPlayer.playVideo(); return 'API_ONLY|0|0'; } catch (_) {}
                      }
                      return 'NO_VIDEO|0|0';
                    }

                    var videoIsRenderable = isRenderable(video);
                    var position = Number(video.currentTime || 0);
                    var ready = Number(video.readyState || 0);
                    if (video.ended) return 'ENDED|' + position + '|' + ready;

                    var player = document.querySelector('#movie_player') ||
                                 document.querySelector('.html5-video-player') ||
                                 document.querySelector('ytm-player');

                    var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                    var clockAdvancing = window.__ngocSiTrackedVideo === video &&
                      window.__ngocSiPlaybackActuallyPlaying === true &&
                      progressAt > 0 && Date.now() - progressAt < 1800;

                    // After returning from fullscreen, Chromium can leave a media element
                    // logically "playing" while currentTime no longer advances. Recover both
                    // a genuinely paused element and a stalled, non-paused one.
                    if (mode === 'initial' && video.paused) {
                      // Do not pause/restart an element just because the YouTube state
                      // tracker is stale during rotation. That was interrupting streams
                      // which were already progressing. Only issue Play for a paused element.
                      if (videoIsRenderable && player && typeof player.playVideo === 'function') {
                        try { player.playVideo(); return 'REQUESTED|' + position + '|' + ready; } catch (_) {}
                      }
                      try {
                        var firstPromise = video.play();
                        if (firstPromise && typeof firstPromise.catch === 'function')
                          firstPromise.catch(function(){});
                        return 'REQUESTED|' + position + '|' + ready;
                      } catch (_) {}
                    }

                    if (mode === 'fallback') {
                      // Several probes have already shown that the media clock is not moving.
                      // Never issue player.playVideo() and immediately pause/replay the same
                      // element: the competing commands caused audible stutter then stopped audio.
                      if (video.paused) {
                        if (videoIsRenderable && player && typeof player.playVideo === 'function') {
                          try {
                            player.playVideo();
                            return 'REQUESTED|' + position + '|' + ready;
                          } catch (_) {}
                        }
                        try {
                          var pausedPromise = video.play();
                          if (pausedPromise && typeof pausedPromise.catch === 'function')
                            pausedPromise.catch(function(){});
                          return 'REQUESTED|' + position + '|' + ready;
                        } catch (_) {}
                      } else {
                        // If the element claims to be playing but is stalled, make a single
                        // idempotent Play request. Keep the one-time reload fallback as the
                        // last resort rather than resetting the media pipeline in-place.
                        if (player && typeof player.playVideo === 'function') {
                          try {
                            player.playVideo();
                            return 'NUDGED|' + position + '|' + ready;
                          } catch (_) {}
                        }
                        try {
                          var stalledPromise = video.play();
                          if (stalledPromise && typeof stalledPromise.catch === 'function')
                            stalledPromise.catch(function(){});
                          return 'NUDGED|' + position + '|' + ready;
                        } catch (_) {}
                      }
                    }

                    return (video.paused ? 'PAUSED' : 'ACTIVE') + '|' + position + '|' + ready;
                  } catch (_) {
                    return 'ERROR|0|0';
                  }
                })('$mode');
            """.trimIndent()

            webView?.evaluateJavascript(script) { result ->
                if (generation != fullscreenRecoveryGeneration || explicitUserPause) return@evaluateJavascript
                val fields = result.orEmpty().trim('"').split('|')
                val state = fields.getOrNull(0).orEmpty()
                val currentPosition = fields.getOrNull(1)?.toDoubleOrNull()

                if (state == "ENDED") return@evaluateJavascript
                if (state == "REQUESTED" || state == "API_ONLY") playRequested = true

                // Confirm recovery only when the actual media clock advances. "ACTIVE" or
                // "buffering" alone is not enough and must not trigger more Play commands.
                val previous = previousPosition
                if (previous != null && currentPosition != null &&
                    currentPosition > previous + 0.04
                ) {
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                        .putBoolean("youtube_playback_is_playing", true)
                        .putBoolean("youtube_is_playing", true)
                        .apply()
                    return@evaluateJavascript
                }
                if (currentPosition != null) previousPosition = currentPosition

                if (number < 8 && !isFinishing && !isDestroyed) {
                    playbackPositionHandler.postDelayed({ attempt(number + 1) }, 300L)
                    return@evaluateJavascript
                }

                // If the WebView's media surface is still frozen after bounded, non-repeating
                // play attempts, recreate the YouTube page once at the last known timestamp.
                // This is a last resort: preserve the selected video and approximate position
                // instead of leaving a silent frozen surface with no way to recover.
                if (!pageReloadFallbackUsed &&
                    !geometryReloadFallbackAttemptedForCurrentPage &&
                    !isFinishing && !isDestroyed
                ) {
                    val currentUrl = webView?.url
                    if (!currentUrl.isNullOrBlank() &&
                        (currentUrl.contains("youtube.com/watch") ||
                         currentUrl.contains("youtube.com/shorts/"))
                    ) {
                        pageReloadFallbackUsed = true
                        geometryReloadFallbackAttemptedForCurrentPage = true
                        val seconds = ((currentPosition ?: previousPosition ?: 0.0).toInt())
                            .coerceAtLeast(0)
                        val restoredUrl = Uri.parse(currentUrl)
                            .buildUpon()
                            .clearQuery()
                            .appendQueryParameter("v", sanitizeVideoId(videoId))
                            .appendQueryParameter("t", "${seconds}s")
                            .appendQueryParameter("autoplay", "1")
                            .appendQueryParameter("playsinline", "1")
                            .build()
                            .toString()
                        recoverAfterPageReload = true
                        youtubePlayerReady = false
                        webView?.postDelayed({
                            if (!isFinishing && !isDestroyed && webView != null) {
                                webView?.loadUrl(restoredUrl)
                            }
                        }, 300L)
                    }
                }
            }
        }

        attempt(0)
    }

    /**
     * Best-effort ad request filtering for the embedded YouTube WebView.
     * Do not filter googlevideo.com or broad Google/YouTube hosts because they
     * serve actual video/audio, player code, thumbnails and captions as well.
     */
    private fun isKnownYouTubeAdvertisingRequest(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        fun isHostOrSubdomain(domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        val knownAdDomains = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adservice.google.com.vn"
        )
        if (knownAdDomains.any(::isHostOrSubdomain)) return true

        // Target clearly ad-specific endpoints on YouTube itself; avoid generic
        // player/search APIs that are required for normal playback.
        return isHostOrSubdomain("youtube.com") && (
            path.contains("/pagead/") ||
                path.contains("/get_midroll_info") ||
                path.contains("/youtubei/v1/player/ad_break") ||
                path.contains("/youtubei/v1/ad_break")
            )
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
        if (youtubeBackgroundControlReceiverRegistered) {
            runCatching { unregisterReceiver(youtubeBackgroundControlReceiver) }
            youtubeBackgroundControlReceiverRegistered = false
        }
        loadingBar = null
        super.onDestroy()
    }
}


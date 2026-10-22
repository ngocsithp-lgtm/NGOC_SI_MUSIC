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
    private var videoId: String = ""
    private var title: String = "YouTube"
    private var channel: String = "YouTube"
    private lateinit var titleView: TextView
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
                if (state == 1) backgroundPlaybackRecoveryGeneration++
                youtubePlayerState = state
                getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                    .putBoolean("youtube_playback_is_playing", state == 1)
                    .apply()
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
        val nowPlaying = TextView(this).apply {
            tag = "youtube_playback_status"
            text = "●  YOUTUBE  •  TRÌNH PHÁT TÍCH HỢP"
            setTextColor(AndroidColor.rgb(255, 139, 155))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            background = roundedButtonDrawable(
                fill = AndroidColor.rgb(39, 24, 30),
                stroke = AndroidColor.rgb(83, 45, 56),
                radius = dp(9)
            )
            setPadding(dp(9), dp(6), dp(9), dp(6))
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
            text = "☷  HÀNG ĐỢI  •  " + (queueIndex + 1) + "/" + queue.size.coerceAtLeast(1)
            setTextColor(AndroidColor.rgb(164, 225, 255))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = roundedButtonDrawable(
                fill = AndroidColor.rgb(20, 29, 43),
                stroke = AndroidColor.rgb(42, 65, 91),
                radius = dp(10)
            )
            setPadding(dp(10), dp(7), dp(10), dp(7))
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

        val backgroundButton = actionButton("🎧 Nền") { enableInAppBackgroundPlayback() }.apply {
            contentDescription = "Bật phát nền ngay trong NGỌC SĨ MUSIC"
            textSize = 12f
        }
        queueActions.addView(backgroundButton, LinearLayout.LayoutParams(dp(82), dp(46)).apply {
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

        inAppBackgroundPlaybackEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView?.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false
            )
        }
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_background_playback_enabled", true)
            .apply()
        backgroundPlaybackRecoveryGeneration++

        if (youtubePlayerState == 1) {
            syncYouTubeBackgroundPlayback(1)
            Toast.makeText(
                this,
                "Đã bật giữ phát nền trong NGỌC SĨ MUSIC. Bạn có thể về màn hình chính hoặc khóa màn hình.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            requestYouTubePlayback()
            Toast.makeText(
                this,
                "Đã bật chế độ nền trong app. Bấm Phát nếu video đang tạm dừng.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    private fun pauseYouTubePlayback() {
        // An explicit Pause must cancel background recovery and never auto-resume.
        playbackRequestGeneration++
        backgroundPlaybackRecoveryGeneration++
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", false)
            .apply()
        // Keep background mode enabled across a deliberate pause; the playback flag
        // prevents recovery until the user presses Play again.
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
                      if (current && current.isConnected) return current;
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

                var wantsPause = '$safeCommand' === 'pause' ||
                    ('$safeCommand' === 'toggle' && !video.paused && !video.ended);

                if (wantsPause) {
                  try { if (api && typeof api.pauseVideo === 'function') api.pauseVideo(); } catch (_) {}
                  try { video.pause(); } catch (_) {}
                  return 'paused';
                }

                // Multiple simultaneous playback mechanisms caused stutter. Use one mechanism
                // per request: prefer the YouTube player API, then the visible button or HTML
                // media API only when the preferred mechanism is unavailable.
                if ('$safeCommand' === 'play' &&
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

    private fun requestYouTubePlayback() {
        if (!youtubePlayerReady || webView == null || isFinishing || isDestroyed) return
        val requestGeneration = ++playbackRequestGeneration
        fun attempt(remaining: Int) {
            if (requestGeneration != playbackRequestGeneration ||
                isFinishing || isDestroyed || webView == null || !youtubePlayerReady
            ) return
            webView?.evaluateJavascript(youtubePlaybackScript("play")) { result ->
                if (requestGeneration != playbackRequestGeneration) return@evaluateJavascript
                val accepted = result?.contains("playing") == true ||
                    result?.contains("buffering") == true ||
                    result?.contains("requested-api") == true ||
                    result?.contains("requested-button") == true ||
                    result?.contains("requested-video") == true
                // Retry only a rejected request. A valid Play request never starts
                // a competing forced pause/Play recovery loop.
                if (!accepted && remaining > 0 && !isFinishing && !isDestroyed &&
                    requestGeneration == playbackRequestGeneration
                ) playbackPositionHandler.postDelayed({ attempt(remaining - 1) }, 750L)
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
                            return rendered[0] || videos[0] || null;
                          }
                          window.__ngocSiFindActiveVideo = findActiveVideo;
                          // Do not change page layout or hide YouTube DOM nodes around the
                          // playing video; those changes can detach its rendering surface.
                          // Install the Android bridge only after YouTube creates a video element.
                          // The bridge reports state and avoids automatic pause/play watchdogs
                          // that can restart a buffering stream repeatedly.
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
                              if (e !== lastVideo) {
                                lastVideo = e;
                                lastState = -99;
                              }
                              // Report browser state only. Do not force pause/play when
                              // the media clock pauses briefly; YouTube may simply buffer.
                              var state = e.ended ? 0 : (e.paused ? 2 : 1);
                              if (state !== lastState) {
                                lastState = state;
                                try { AndroidBridge.onStateChanged(state); } catch (_) {}
                              }
                            }, 500);
                          }
                          installNgocSiBridge(0);
                        })();
                    """.trimIndent(), null)
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
        if (youtubeBackgroundControlReceiverRegistered) {
            runCatching { unregisterReceiver(youtubeBackgroundControlReceiver) }
            youtubeBackgroundControlReceiverRegistered = false
        }
        loadingBar = null
        super.onDestroy()
    }
}


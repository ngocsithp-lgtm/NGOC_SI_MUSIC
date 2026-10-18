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
                // Persist the actual embedded YouTube playback state so the
                // compact player's button can pause/resume YouTube instead of
                // accidentally toggling the independent Media3 local player.
                getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                    .putBoolean("youtube_playback_is_playing", state == 1)
                    .apply()
                updatePlaybackButton()
                syncYouTubeBackgroundPlayback(state)
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
    private var resumeVideoAfterPipEntry = false
    private val pipActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE_INTERNAL -> {
                    // PiP transport controls the embedded YouTube player in place.
                    if (youtubePlayerReady && webView != null) {
                        toggleYoutubePlayback()
                    }
                }
                YouTubeBackgroundService.ACTION_CONTROL -> {
                    if (!youtubePlayerReady || webView == null) return
                    when (intent.getStringExtra(YouTubeBackgroundService.EXTRA_CONTROL)) {
                        "play" -> webView?.evaluateJavascript("(function(){try{var v=document.querySelector(\u0027video\u0027);if(!v)return \u0027no-video\u0027;var p=v.play();if(p&&p.catch)p.catch(function(){});return \u0027requested\u0027;}catch(e){return \u0027error\u0027;}})();", null)
                        "pause" -> webView?.evaluateJavascript("(function(){try{var v=document.querySelector(\u0027video\u0027);if(v)v.pause();return \u0027paused\u0027;}catch(e){return \u0027error\u0027;}})();", null)
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
            "(function(){try{var v=document.querySelector('video');var m=location.href.match(/[?&]v=([A-Za-z0-9_-]+)/);return JSON.stringify({id:m?m[1]:'',p:v?Math.floor(v.currentTime*1000):0});}catch(e){return '{}';}})();"
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
            "(function(){try{var v=document.querySelector('video');if(!v||!isFinite(v.duration))return 'wait';v.currentTime=$seconds;return 'ok';}catch(e){return 'wait';}})();"
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
        if (!youtubePlayerReady || webView == null || isFinishing || isDestroyed) return
        val toggleScript = """
            (function(){
              try {
                var v = document.querySelector('video.html5-main-video') ||
                        document.querySelector('#movie_player video') ||
                        document.querySelector('video');
                if (!v) return 'no-video';
                if (!v.paused && !v.ended) {
                  v.pause();
                  return 'paused';
                }
                var p = v.play();
                if (p && p.catch) p.catch(function(){
                  try {
                    var b = document.querySelector('.ytp-play-button[aria-label*="Play"],.ytp-play-button[aria-label*="Phát"],button[aria-label*="Play"],button[aria-label*="Phát"]');
                    if (b) b.click();
                  } catch (_) {}
                });
                // "buffering" means play() was accepted; do not spam play() while data buffers.
                return !v.paused ? (v.readyState >= 2 ? 'playing' : 'buffering') : 'retry';
              } catch (_) { return 'error'; }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(toggleScript) { result ->
            if ((result?.contains("retry") == true || result?.contains("no-video") == true) &&
                !isFinishing && !isDestroyed
            ) {
                playbackPositionHandler.postDelayed({ requestYouTubePlayback() }, 300L)
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
        val resumeScript = """
            (function(){
              try {
                var v = document.querySelector('video.html5-main-video') ||
                        document.querySelector('#movie_player video') ||
                        document.querySelector('video');
                if (!v) return 'no-video';
                if (!v.paused && !v.ended) return v.readyState >= 2 ? 'playing' : 'buffering';
                var p = v.play();
                if (p && p.catch) p.catch(function(){
                  try {
                    var b = document.querySelector('.ytp-play-button[aria-label*="Play"],.ytp-play-button[aria-label*="Phát"],button[aria-label*="Play"],button[aria-label*="Phát"]');
                    if (b) b.click();
                  } catch (_) {}
                });
                return !v.paused ? (v.readyState >= 2 ? 'playing' : 'buffering') : 'retry';
              } catch (_) { return 'error'; }
            })();
        """.trimIndent()
        fun attempt(remaining: Int) {
            if (isFinishing || isDestroyed || webView == null) return
            webView?.evaluateJavascript(resumeScript) { result ->
                val accepted = result?.contains("playing") == true ||
                    result?.contains("buffering") == true
                if (!accepted && remaining > 0 && !isFinishing && !isDestroyed) {
                    playbackPositionHandler.postDelayed({ attempt(remaining - 1) }, 450L)
                }
            }
        }
        // Retry only until play() is accepted. Do not issue repeated play() calls while buffering.
        attempt(12)
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
            "pause" -> webView?.evaluateJavascript("(function(){try{var v=document.querySelector(\u0027video\u0027);if(v)v.pause();return \u0027paused\u0027;}catch(e){return \u0027error\u0027;}})();", null)
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
                            return document.querySelector('video.html5-main-video') ||
                                   document.querySelector('#movie_player video') ||
                                   document.querySelector('video');
                          }
                          function installNgocSiBridge(retries) {
                            if (window.__ngocSiBridgeInstalled) return;
                            var v = findActiveVideo();
                            if (!v) {
                              if (retries < 30) setTimeout(function(){ installNgocSiBridge(retries + 1); }, 700);
                              return;
                            }
                            window.__ngocSiBridgeInstalled = true;
                            window.playVideo = function(){ var e=findActiveVideo(); if(e) e.play().catch(function(){}); };
                            window.pauseVideo = function(){ var e=findActiveVideo(); if(e) e.pause(); };
                            window.getCurrentTime = function(){ var e=findActiveVideo(); return e ? e.currentTime : 0; };
                            window.seekTo = function(t){ var e=findActiveVideo(); if(e) e.currentTime=t; };
                            window.loadVideoById = function(id){ location.href='https://m.youtube.com/watch?v='+encodeURIComponent(id); };

                            // Focus the actual mobile YouTube player while PiP is active;
                            // restore the surrounding watch page when the PiP window closes.
                            window.__ngocSiSetPipMode = function(enabled) {
                              var styleId = 'ngocsi-pip-style';
                              var hiddenClass = 'ngocsi-pip-hidden';
                              var focusClass = 'ngocsi-pip-video-container';
                              var restore = window.__ngocSiPipHiddenElements || [];
                              if (!enabled) {
                                restore.forEach(function(el){ if (el && el.classList) el.classList.remove(hiddenClass); });
                                window.__ngocSiPipHiddenElements = [];
                                document.querySelectorAll('.' + focusClass).forEach(function(el){ el.classList.remove(focusClass); });
                                document.documentElement.classList.remove('ngocsi-pip-active');
                                var oldStyle = document.getElementById(styleId);
                                if (oldStyle) oldStyle.remove();
                                return true;
                              }

                              restore.forEach(function(el){ if (el && el.classList) el.classList.remove(hiddenClass); });
                              window.__ngocSiPipHiddenElements = [];
                              document.querySelectorAll('.' + focusClass).forEach(function(el){ el.classList.remove(focusClass); });

                              var player = document.querySelector('.player-container') ||
                                           document.querySelector('#player-container-id') ||
                                           document.querySelector('#player') ||
                                           document.querySelector('ytm-player') ||
                                           document.querySelector('.html5-video-player');
                              if (!player) return false;

                              var hidden = [];
                              var current = player;
                              while (current && current.parentElement && current.parentElement !== document.documentElement) {
                                var parent = current.parentElement;
                                Array.prototype.forEach.call(parent.children, function(sibling) {
                                  if (sibling !== current && !sibling.classList.contains(hiddenClass)) {
                                    sibling.classList.add(hiddenClass);
                                    hidden.push(sibling);
                                  }
                                });
                                current = parent;
                              }
                              window.__ngocSiPipHiddenElements = hidden;
                              player.classList.add(focusClass);
                              document.documentElement.classList.add('ngocsi-pip-active');

                              var style = document.getElementById(styleId);
                              if (!style) {
                                style = document.createElement('style');
                                style.id = styleId;
                                document.head.appendChild(style);
                              }
                              style.textContent = [
                                '.' + hiddenClass + '{display:none!important;}',
                                'html.ngocsi-pip-active,html.ngocsi-pip-active body,html.ngocsi-pip-active ytm-app{' +
                                  'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
                                  'overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important;}',
                                '.' + focusClass + '{position:fixed!important;top:0!important;right:0!important;bottom:0!important;left:0!important;' +
                                  'display:block!important;width:100vw!important;height:100vh!important;min-width:0!important;min-height:0!important;' +
                                  'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;overflow:hidden!important;' +
                                  'background:#000!important;z-index:2147483647!important;}',
                                '.' + focusClass + ' ytm-player,.' + focusClass + ' #player,.' + focusClass + ' #player-container-id,' +
                                  '.' + focusClass + ' .html5-video-player,.' + focusClass + ' .html5-video-container,.' + focusClass + ' video{' +
                                  'position:absolute!important;top:0!important;right:0!important;bottom:0!important;left:0!important;' +
                                  'width:100%!important;height:100%!important;min-width:0!important;min-height:0!important;' +
                                  'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;}',
                                '.' + focusClass + ' video{object-fit:contain!important;background:#000!important;}'
                              ].join('');
                              return true;
                            };

                            try { AndroidBridge.onReady(); } catch(e) {}
                            var lastState = -99;
                            setInterval(function(){
                              var e=findActiveVideo();
                              if(!e) return;
                              var state = e.ended ? 0 : (e.paused ? 2 : 1);
                              if(state !== lastState) {
                                lastState = state;
                                try { AndroidBridge.onStateChanged(state); } catch(e) {}
                              }
                            }, 900);
                          }
                          installNgocSiBridge(0);
                        })();
                    """.trimIndent(), null)
                    // A queue navigation can finish while PiP is already active.
                    if (isInPictureInPictureMode) {
                        view.evaluateJavascript(
                            "window.__ngocSiSetPipMode && window.__ngocSiSetPipMode(true);",
                            null
                        )
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
        // Re-arm WebView media/JS processing whenever the Activity returns to
        // the foreground. This does not attempt to bypass YouTube background-
        // playback restrictions; it only makes foreground recovery reliable.
        webView?.onResume()
        updatePipParamsIfSupported()
        // onPause()/onStop() stop the periodic position saver. Restart it when
        // returning to an already-ready player; onReady() only fires on initial
        // player setup, so relying on that callback loses periodic saves after
        // the first background/foreground transition.
        if (youtubePlayerReady && webView != null) {
            startPlaybackPositionSaver()
            if (youtubePlayerState == 1) syncYouTubeBackgroundPlayback(youtubePlayerState)
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        // Tell Android which on-screen region is the actual video surface. This
        // avoids animating/cropping the whole watch page into the PiP window.
        if (::playerContainer.isInitialized) {
            val sourceRect = Rect()
            if (playerContainer.getGlobalVisibleRect(sourceRect) &&
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
            builder.setAutoEnterEnabled(youtubePlayerReady && youtubePlayerState == 1)
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
            loadingBar?.visibility = View.GONE
            pipScrollY = contentScrollView?.scrollY ?: 0

            // The mobile YouTube page may be in its separate fullscreen custom view.
            // Remove that overlay before showing only the inline WebView in PiP.
            if (customView != null) exitFullscreen()

            // PiP can pause the HTML5 video while Chromium resizes its surface.
            // Preserve playback intent before any state callback reports that pause.
            if (!resumeVideoAfterPipEntry) {
                val lastObservedPlaying = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                    .getBoolean("youtube_playback_is_playing", false)
                resumeVideoAfterPipEntry = youtubePlayerState == 1 ||
                    (youtubePlayerState < 0 && lastObservedPlaying)
            }

            // Keep the WebView attached to its original parent. Reparenting a WebView
            // during PiP can detach Chromium's video compositor surface, producing a
            // black window or audio-only playback on some Samsung devices.
            val content = contentScrollView?.getChildAt(0) as? ViewGroup
            content?.let { column ->
                for (i in 0 until column.childCount) {
                    val child = column.getChildAt(i)
                    child.visibility = if (child === playerContainer) View.VISIBLE else View.GONE
                }
            }
            contentScrollView?.scrollTo(0, 0)
            contentScrollView?.clipChildren = true
            contentScrollView?.clipToPadding = true
            playerContainer.visibility = View.VISIBLE
            val pipWindowWidthPx = newConfig.screenWidthDp
                .takeIf { it > 0 }
                ?.let { (it * resources.displayMetrics.density).toInt() }
                ?: 0
            resizePlayerContainerForPip(true, pipWindowWidthPx = pipWindowWidthPx)
            webView?.onResume()
            webView?.resumeTimers()
            updateWebViewPipPresentation(true)

            if (resumeVideoAfterPipEntry) {
                val recoverPlayback = object : Runnable {
                    private var attempts = 0
                    override fun run() {
                        if (isFinishing || isDestroyed || !isInPictureInPictureMode || webView == null) return
                        attempts++
                        webView?.evaluateJavascript(
                            """(function(){
                                try {
                                  var v=document.querySelector('video.html5-main-video') ||
                                        document.querySelector('#movie_player video') ||
                                        document.querySelector('video');
                                  if(!v) return 'no-video';
                                  if(!v.paused && !v.ended) return v.readyState >= 2 ? 'playing' : 'buffering';
                                  var p=v.play();
                                  if(p && p.catch) p.catch(function(){
                                    try {
                                      var b=document.querySelector('.ytp-play-button[aria-label*="Play"],.ytp-play-button[aria-label*="Phát"],button[aria-label*="Play"],button[aria-label*="Phát"]');
                                      if(b) b.click();
                                    } catch(e) {}
                                  });
                                  return !v.paused ? (v.readyState >= 2 ? 'playing' : 'buffering') : 'retry';
                                } catch(e) { return 'error'; }
                            })();""".trimIndent()
                        ) { result ->
                            val accepted = result?.contains("playing") == true ||
                                result?.contains("buffering") == true
                            if (!accepted && attempts < 16 && !isFinishing &&
                                !isDestroyed && isInPictureInPictureMode
                            ) {
                                webView?.postDelayed(this, 500L)
                            }
                        }
                    }
                }
                webView?.post(recoverPlayback)
            }
        } else {
            resumeVideoAfterPipEntry = false
            val content = contentScrollView?.getChildAt(0) as? ViewGroup
            content?.let { column ->
                for (i in 0 until column.childCount) {
                    column.getChildAt(i).visibility = View.VISIBLE
                }
            }
            contentScrollView?.visibility = View.VISIBLE
            contentScrollView?.post { contentScrollView?.scrollTo(0, pipScrollY) }
            updateWebViewPipPresentation(false)
            webView?.onResume()
            resizePlayerContainerForPip(false)
        }
        updatePipParamsIfSupported()
    }

    private fun updateWebViewPipPresentation(inPip: Boolean) {
        val value = if (inPip) "true" else "false"
        webView?.evaluateJavascript(
            "window.__ngocSiSetPipMode && window.__ngocSiSetPipMode($value);",
            null
        )
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
        resumeVideoAfterPipEntry =
            (youtubePlayerState == 1 || (youtubePlayerState < 0 && lastObservedPlaying))
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
        )
    }
}
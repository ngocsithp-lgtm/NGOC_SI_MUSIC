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
    private var isCssFullscreen = false
    // Preserve the pre-fullscreen playback state even if YouTube reports PAUSED while rotating.
    private var wasPlayingBeforeFullscreen = false
    private var fullscreenRecoveryGeneration = 0
    // Coalesce duplicate fullscreen-dismiss callbacks so only one recovery cycle runs.
    private var fullscreenExitGeneration = 0
    // A single post-reload recovery is allowed after fullscreen playback stalls.
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

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            contentDescription = "Trình phát video YouTube"
        }

        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Keep the in-app player at 16:9 in portrait; YouTube's native fullscreen
        // custom view takes over the entire display when fullscreen is requested.
        val metrics = resources.displayMetrics
        val playerHeight = (metrics.widthPixels * 9f / 16f).toInt()
        content.addView(playerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            playerHeight.coerceAtLeast(dp(200))
        ))

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
                '.ytp-pause-overlay-container,.ytp-cards-teaser,.ytp-ce-element,.ytp-gradient-top,' +
                '.ytp-show-cards-title,ytm-player-overlay-renderer,ytm-video-overlay-renderer,' +
                'ytm-mobile-player-overlay-renderer { display:none!important; }' +
                'ytm-player { position:fixed!important; inset:0!important; width:auto!important;height:auto!important;min-width:0!important;min-height:0!important;max-width:none!important;max-height:none!important;aspect-ratio:auto!important;z-index:2147483647!important;background:#000!important;overflow:hidden!important;margin:0!important;padding:0!important; }' +
                'ytm-player video,video.html5-main-video,.html5-video-container { display:block!important;position:absolute!important;inset:0!important;width:100%!important;height:100%!important;min-width:100%!important;min-height:100%!important;max-width:100vw!important;max-height:100vh!important;object-fit:contain!important;object-position:center center!important;margin:0!important;padding:0!important; }';
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

                var video = findVideo();
                if (!video) return 'no-video';
                var api = getPlayerApi();

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
        val generation = ++playbackCommandGeneration
        // A button press sends one playback command. Do not repeatedly call playVideo()
        // while YouTube is buffering: repeated commands can cause stutter and stop playback.
        val retryCommand = if (command == "toggle") "play" else command

        webView?.evaluateJavascript(youtubePlaybackScript(command)) { result ->
            if (generation != playbackCommandGeneration ||
                webView == null || isFinishing || isDestroyed
            ) return@evaluateJavascript
            val response = result.orEmpty()
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
        val requestGeneration = ++playbackRequestGeneration
        fun attempt(remaining: Int) {
            if (requestGeneration != playbackRequestGeneration ||
                isFinishing || isDestroyed || webView == null
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
                                '.ytp-pause-overlay, .ytp-pause-overlay-container, .ytp-cards-teaser, ' +
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
                            if (!document.getElementById('ngocsi-player-viewport-fix')) {
                              var fitStyle = document.createElement('style');
                              fitStyle.id = 'ngocsi-player-viewport-fix';
                              fitStyle.textContent =
                                'html,body,ytm-app,ytm-watch {width:100%!important;max-width:none!important;}' +
                                'ytm-player,#player-container,#player,#movie_player,.html5-video-player,' +
                                '.html5-video-container {box-sizing:border-box!important;width:100%!important;' +
                                'max-width:none!important;margin-left:0!important;margin-right:0!important;}' +
                                'video.html5-main-video,ytm-player video,#movie_player video {' +
                                'width:100%!important;height:100%!important;max-width:none!important;' +
                                'max-height:100%!important;object-fit:contain!important;object-position:center center!important;}';
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
                              if (isFullscreen) {
                                root.style.setProperty('position','fixed','important');
                                root.style.setProperty('left','0','important');
                                root.style.setProperty('right','0','important');
                                root.style.setProperty('top','0','important');
                                root.style.setProperty('bottom','0','important');
                                root.style.setProperty('width',viewportWidth+'px','important');
                                root.style.setProperty('height',viewportHeight+'px','important');
                              } else {
                                // Return to YouTube's inline layout after shrinking fullscreen.
                                ['position','left','right','top','bottom'].forEach(function(p) {
                                  root.style.removeProperty(p);
                                });
                                root.style.setProperty('width','100%','important');
                                root.style.setProperty('height',Math.round(viewportWidth*9/16)+'px','important');
                              }
                              root.style.setProperty('max-width','none','important');
                              var video = document.querySelector('video.html5-main-video') ||
                                root.querySelector('video') || document.querySelector('#movie_player video');
                              if (video) {
                                if (isFullscreen) {
                                  video.style.setProperty('position','absolute','important');
                                  video.style.setProperty('left','0','important');
                                  video.style.setProperty('top','0','important');
                                } else {
                                  video.style.removeProperty('position');
                                  video.style.removeProperty('left');
                                  video.style.removeProperty('top');
                                }
                                video.style.setProperty('width','100%','important');
                                video.style.setProperty('height','100%','important');
                                video.style.setProperty('max-width','none','important');
                                video.style.setProperty('object-fit','contain','important');
                                video.style.setProperty('object-position','center center','important');
                              }
                            }
                            if (window.__ngocSiFitPlayerTimer) clearInterval(window.__ngocSiFitPlayerTimer);
                            ngocSiFitPlayerToViewport();
                            window.addEventListener('resize', ngocSiFitPlayerToViewport, {passive:true});
                            window.__ngocSiFitPlayerTimer = setInterval(ngocSiFitPlayerToViewport, 900);
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

                    // Capture state before WebChromeClient hides the inline player.
                    wasPlayingBeforeFullscreen = wasPlayingBeforeFullscreen ||
                        youtubePlayerState == 1 ||
                        getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                            .getBoolean("youtube_playback_is_playing", false)

                    customView = view
                    customViewCallback = callback
                    // Keep the original WebView attached beneath the fullscreen custom view.
                    // Chromium redirects drawing to this custom view during fullscreen;
                    // hiding the host container can trigger a visibility transition and
                    // leave inline media frozen when returning to the app player.
                    playerContainer.visibility = View.VISIBLE
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
                    // WebChromeClient is already notifying us that its custom view is hidden.
                    // Do not call CustomViewCallback.onCustomViewHidden() a second time here:
                    // that callback is reserved for the host initiating dismissal.
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // The manifest handles orientation changes without recreating this Activity.
        // Recompute the native 16:9 WebView host when returning from landscape fullscreen,
        // but never resize the separate fullscreen custom view itself.
        if (!::playerContainer.isInitialized || customView != null || isCssFullscreen) return

        playerContainer.post {
            if (isFinishing || isDestroyed || customView != null || isCssFullscreen) return@post
            val availableWidth = root.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            playerContainer.layoutParams = playerContainer.layoutParams.apply {
                height = (availableWidth * 9f / 16f).toInt().coerceAtLeast(dp(200))
            }
            playerContainer.requestLayout()
            webView?.postDelayed({
                if (isFinishing || isDestroyed || webView == null ||
                    customView != null || isCssFullscreen
                ) return@postDelayed
                webView?.evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'));",
                    null
                )
            }, 120L)
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

    private fun enterFullscreen() {
        // Landscape guarantees the fullscreen video uses the long edge of the phone.
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
        // Cancel any stale probe loop before scheduling the one recovery for this exit.
        fullscreenRecoveryGeneration++
        // Keep the existing WebView/video element and currentTime. Do not write persistent
        // inline width/height/inset values to YouTube's internal video nodes; they survive
        // removal of the fullscreen stylesheet and can break playback after rotation.
        // Fullscreen transitions can leave the JS/player state stale even when playback
        // was active immediately before rotation. Always run bounded recovery on return;
        // the recovery routine checks the real media clock and avoids repeated play commands.
        // A WebView/YouTube state callback can be stale after the fullscreen surface
        // handoff. Always probe the same video after layout settles; the recovery routine
        // only requests Play when the actual HTML5 video reports paused.
        // YouTube's player state can become stale after the first rotation and report
        // PAUSED even though the user did not pause. Probe/recover after every fullscreen
        // exit instead of relying only on the cached state. Recovery is bounded and checks
        // the actual HTML5 media clock before deciding whether a fallback is needed.
        val shouldRecoverPlayback = true
        wasPlayingBeforeFullscreen = false

        if (isCssFullscreen) {
            webView?.evaluateJavascript(
                """
                (function() {
                  var style = document.getElementById('ngocsi-css-fullscreen');
                  if (style) style.remove();
                  document.documentElement.style.removeProperty('width');
                  document.documentElement.style.removeProperty('height');
                  document.body.style.removeProperty('width');
                  document.body.style.removeProperty('height');
                  window.dispatchEvent(new Event('resize'));
                  return 'layout-restored';
                })();
                """.trimIndent(),
                null
            )
            isCssFullscreen = false
        }
        // Detach the fullscreen custom view from whichever parent currently owns it.
        // Chromium/rotation can change the callback order; removing only from root may
        // leave the fullscreen surface attached as an extra layer over the inline UI.
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
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())

        // Resize only the native host after rotation. Let YouTube restore its own DOM layout;
        // persistent forced CSS on internal player nodes is intentionally avoided.
        playerContainer.post {
            if (isFinishing || isDestroyed || webView == null) return@post
            playerContainer.requestLayout()
            webView?.post {
                if (isFinishing || isDestroyed || webView == null) return@post
                webView?.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
                if (shouldRecoverPlayback) {
                    // Wait for Android orientation and WebView layout to settle before recovery.
                    webView?.postDelayed({
                        if (exitGeneration != fullscreenExitGeneration) return@postDelayed
                        // First recover the existing media element. Recreating the page
                        // on every exit can restart buffering and cause intermittent audio
                        // loss. The bounded recovery loop will reload only if the media
                        // clock still cannot advance after several checks.
                        recoverFullscreenPlayback()
                    }, 850L)
                }
            }
        }
    }

    private fun recoverFullscreenPlayback(allowPageReloadFallback: Boolean = true) {
        if (isFinishing || isDestroyed || webView == null) return
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
                isFinishing || isDestroyed || webView == null
            ) return

            // One normal Play request if the element is paused, then only observe currentTime.
            // If it remains stalled for ~3 seconds, allow one (and only one) fallback nudge.
            val mode = when {
                number == 0 && !playRequested -> "initial"
                number >= 5 && !fallbackNudgeSent -> "fallback"
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
                    if (!isRenderable(video)) {
                      var candidates = Array.prototype.slice.call(document.querySelectorAll('video'));
                      video = candidates.find(function(candidate) {
                        return isRenderable(candidate) &&
                          (candidate.classList.contains('html5-main-video') ||
                           !!candidate.closest('#movie_player') ||
                           !!candidate.closest('.html5-video-player') ||
                           !!candidate.closest('ytm-player'));
                      }) || candidates.find(isRenderable) || null;
                    }
                    if (!video) return 'NO_VIDEO|0|0';

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
                    if (mode === 'initial' && (video.paused || !clockAdvancing)) {
                      if (video.paused && player && typeof player.playVideo === 'function') {
                        try { player.playVideo(); return 'REQUESTED|' + position + '|' + ready; } catch (_) {}
                      }
                      if (!video.paused && !clockAdvancing) {
                        try { video.pause(); } catch (_) {}
                      }
                      try {
                        var firstPromise = video.play();
                        if (firstPromise && typeof firstPromise.catch === 'function')
                          firstPromise.catch(function(){});
                        return 'REQUESTED|' + position + '|' + ready;
                      } catch (_) {}
                    }

                    if (mode === 'fallback') {
                      if (player && typeof player.playVideo === 'function') {
                        try { player.playVideo(); } catch (_) {}
                      }
                      // One bounded media-pipeline nudge for a frozen element. Calling play()
                      // alone is insufficient when paused=false but currentTime is stuck.
                      try {
                        if (!video.paused) video.pause();
                        var fallbackPromise = video.play();
                        if (fallbackPromise && typeof fallbackPromise.catch === 'function')
                          fallbackPromise.catch(function(){});
                        return 'NUDGED|' + position + '|' + ready;
                      } catch (_) {}
                    }

                    return (video.paused ? 'PAUSED' : 'ACTIVE') + '|' + position + '|' + ready;
                  } catch (_) {
                    return 'ERROR|0|0';
                  }
                })('$mode');
            """.trimIndent()

            webView?.evaluateJavascript(script) { result ->
                if (generation != fullscreenRecoveryGeneration) return@evaluateJavascript
                val fields = result.orEmpty().trim('"').split('|')
                val state = fields.getOrNull(0).orEmpty()
                val currentPosition = fields.getOrNull(1)?.toDoubleOrNull()

                if (state == "ENDED") return@evaluateJavascript
                if (state == "REQUESTED") playRequested = true

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

                if (number < 11 && !isFinishing && !isDestroyed) {
                    playbackPositionHandler.postDelayed({ attempt(number + 1) }, 650L)
                    return@evaluateJavascript
                }

                // If the WebView's media surface is still frozen after bounded, non-repeating
                // play attempts, recreate the YouTube page once at the last known timestamp.
                // This is a last resort: preserve the selected video and approximate position
                // instead of leaving a silent frozen surface with no way to recover.
                if (!pageReloadFallbackUsed && !isFinishing && !isDestroyed) {
                    pageReloadFallbackUsed = true
                    val currentUrl = webView?.url
                    val seconds = ((currentPosition ?: previousPosition ?: 0.0).toInt())
                        .coerceAtLeast(0)
                    if (!currentUrl.isNullOrBlank() &&
                        (currentUrl.contains("youtube.com/watch") ||
                         currentUrl.contains("youtube.com/shorts/"))
                    ) {
                        val restoredUrl = Uri.parse(currentUrl)
                            .buildUpon()
                            .clearQuery()
                            .appendQueryParameter("v", sanitizeVideoId(videoId))
                            .appendQueryParameter("t", "${seconds}s")
                            // Request inline autoplay on the one-time page recreation. Without
                            // these parameters YouTube can reload a frozen frame but remain paused.
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


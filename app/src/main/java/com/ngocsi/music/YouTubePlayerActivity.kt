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
     * A responsive inline video host. Its height is derived from the measured width,
     * not displayMetrics, so Android and the WebView always share the same 16:9 box.
     */
    private inner class InlinePlayerFrameLayout(context: android.content.Context) :
        FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
            val widthFromSpec = View.MeasureSpec.getSize(widthMeasureSpec)
            val parentWidth = (parent as? View)?.width ?: 0
            // This view is intended to fill the width allocated by the native layout.
            // A non-EXACT/UNSPECIFIED pass must not let WebView's intrinsic content size
            // shrink the player while its height is still calculated from the larger spec.
            // AT_MOST is only a maximum constraint. On rotation WebView can briefly
            // report the old larger width while its host has already narrowed; clamp to
            // the measured host to prevent a right-shift/overflow during that frame.
            val targetWidth = when {
                widthMode == View.MeasureSpec.EXACTLY && widthFromSpec > 0 -> widthFromSpec
                widthMode == View.MeasureSpec.AT_MOST && widthFromSpec > 0 ->
                    if (parentWidth > 0) minOf(widthFromSpec, parentWidth) else widthFromSpec
                parentWidth > 0 -> parentWidth
                widthFromSpec > 0 -> widthFromSpec
                else -> resources.displayMetrics.widthPixels
            }.coerceAtLeast(1)
            val exactWidthSpec = View.MeasureSpec.makeMeasureSpec(
                targetWidth, View.MeasureSpec.EXACTLY
            )

            if (fullscreenLayoutActive) {
                // Fullscreen uses the full viewport, not a 16:9 height derived from width.
                // ScrollView can initially measure this child with an unspecified height,
                // so use its exact viewport height when available or the current short edge.
                val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
                val targetHeight = when {
                    heightMode == View.MeasureSpec.EXACTLY ->
                        View.MeasureSpec.getSize(heightMeasureSpec)
                    else -> {
                        val density = resources.displayMetrics.density
                        (minOf(
                            resources.configuration.screenWidthDp,
                            resources.configuration.screenHeightDp
                        ) * density).toInt().coerceAtLeast(1)
                    }
                }
                super.onMeasure(
                    exactWidthSpec,
                    View.MeasureSpec.makeMeasureSpec(
                        targetHeight.coerceAtLeast(1), View.MeasureSpec.EXACTLY
                    )
                )
                return
            }

            val aspectHeight = (targetWidth * 9f / 16f).toInt().coerceAtLeast(1)
            // Honor the actual parent viewport in landscape. Subtracting a fixed 48dp
            // here left the WebView shorter than its visible host and made YouTube's
            // viewport/inset calculations place the picture below and to the side.
            val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
            val heightLimit = when {
                heightMode == View.MeasureSpec.EXACTLY || heightMode == View.MeasureSpec.AT_MOST ->
                    View.MeasureSpec.getSize(heightMeasureSpec)
                else -> 0
            }
            val measuredHeight = if (landscape && heightLimit > 0) {
                minOf(aspectHeight, heightLimit)
            } else {
                aspectHeight
            }
            val exactHeight = View.MeasureSpec.makeMeasureSpec(
                measuredHeight.coerceAtLeast(1), View.MeasureSpec.EXACTLY
            )
            super.onMeasure(exactWidthSpec, exactHeight)
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
    // Use Chromium's actual video surface for fullscreen instead of restyling the page.
    private var isCssFullscreen = false
    // Native overlay control remains tappable even if YouTube's web controls are hidden
    // or its video surface is temporarily black after a viewport change.
    private var fullscreenEntryPending = false
    private var nativeFullscreenButton: Button? = null
    private var frameStallRecoveryCooldownUntilMs = 0L
    // Restore the real Chromium fullscreen surface if a confirmed frame stall requires
    // rebuilding the same YouTube page at the last saved playback position.
    private var restoreNativeFullscreenAfterRecoveryReload = false
    // Preserve the pre-fullscreen playback state even if YouTube reports PAUSED while rotating.
    private var wasPlayingBeforeFullscreen = false
    private var fullscreenRecoveryGeneration = 0
    // Coalesce duplicate fullscreen-dismiss callbacks so only one recovery cycle runs.
    private var fullscreenExitGeneration = 0
    // Prevent the orientation listener and onConfigurationChanged from cancelling
    // each other's fullscreen-exit recovery when both fire for the same rotation.
    private var fullscreenExitTransitionInProgress = false
    // Prevent onConfigurationChanged from starting a second recovery cycle for the same fullscreen exit.
    private var fullscreenExitRecoveryPending = false
    // Runs one post-rotation geometry verification; a broken video surface can freeze
    // visually while the HTML5 media clock continues advancing.
    private var fullscreenExitGeometryCheckGeneration = 0
    // Defer geometry-driven repair/reload until playback recovery has completed. Running
    // both loops at once could reload the page while the media pipeline was resuming.
    private var pendingFullscreenExitGeometryCheck: (() -> Unit)? = null
    // Ensure only one playback recovery is scheduled for a particular fullscreen exit.
    private var fullscreenExitPlaybackRecoveryScheduledGeneration = -1
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
    // Only physical rotation suppresses DOM style normalization; fullscreen exit must run
    // the normal inline-player cleanup after the shrink button is pressed.
    private var physicalOrientationNormalizationUntilMs = 0L
    // Debounce WebView resize recovery against the native orientation callback.
    private var lastOrientationConfigurationChangeAtMs = 0L
    private var lastKnownConfigurationOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED
    private var automaticOrientationRecoveryGeneration = 0
    // ConfigurationChanged and root-layout callbacks can describe the same physical rotation.
    // Coalesce only duplicate notifications for the same target orientation.
    private var lastPhysicalOrientationTargetLandscape: Boolean? = null
    private var lastPhysicalOrientationHandledAtMs = 0L
    // Keep explicit user pauses separate from temporary WebView surface/viewport pauses.
    private var explicitUserPause = false
    private var orientationPlaybackRecoveryGeneration = 0
    private var fullscreenLayoutActive = false
    private var playerVideoHost: FrameLayout? = null
    private var contentContainer: LinearLayout? = null
    private var playerScrollView: ScrollView? = null
    private var playerHeaderView: View? = null
    private var playerInfoView: View? = null
    private var playerTransportView: View? = null
    private var playerQueueActionsView: View? = null
    // Side panel remains a stable child of the content layout; only weights/visibility change on rotation.
    private var responsiveLandscapeContainer: LinearLayout? = null
    private var landscapePlayerLayoutActive = false
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
    // Selecting/opening a video should start it without requiring a second Play tap.
    // This is consumed once per selected video, never on each configuration change.
    private var autoplayPendingForCurrentVideo = true
    // Keep a forced landscape request until the handset is physically landscape; otherwise
    // switching to FULL_SENSOR immediately after Android's forced rotation snaps back to portrait.
    private var fullscreenOrientationListener: android.view.OrientationEventListener? = null
    private var waitingForPhysicalLandscape = false
    private var fullscreenPhysicalLandscapeReached = false
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
                val hadPendingAction = pendingAiAction != null
                val isRecoveryReload = recoverAfterPageReload
                executePendingAiAction()
                if (autoplayPendingForCurrentVideo) {
                    autoplayPendingForCurrentVideo = false
                    if (!hadPendingAction && !isRecoveryReload) {
                        val expectedVideoId = sanitizeVideoId(videoId)
                        playbackPositionHandler.postDelayed({
                            if (!isFinishing && !isDestroyed && youtubePlayerReady &&
                                webView != null && expectedVideoId == sanitizeVideoId(videoId) &&
                                !explicitUserPause
                            ) {
                                val player = webView ?: return@postDelayed
                                // The mobile page may already be autoplaying from its URL.
                                // Probe first so the native startup command does not interrupt
                                // a stream that is active or still buffering.
                                val startupProbe = """
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
                                      if (!video || !video.isConnected) return 'NO_VIDEO';
                                      if (video.ended) return 'ENDED';
                                      if (video.paused) return 'PAUSED';
                                      if (video.readyState < 2 || video.currentTime < 0.05) return 'BUFFERING';
                                      return 'PLAYING';
                                    })();
                                """.trimIndent()
                                player.evaluateJavascript(startupProbe) { rawState ->
                                    if (isFinishing || isDestroyed || player !== webView ||
                                        !youtubePlayerReady ||
                                        expectedVideoId != sanitizeVideoId(videoId) ||
                                        explicitUserPause
                                    ) return@evaluateJavascript
                                    val state = rawState.orEmpty().trim('"')
                                    if (state == "PAUSED" || state == "NO_VIDEO") {
                                        requestYouTubePlayback()
                                    }
                                }
                            }
                        }, 1100L)
                    }
                }
                if (recoverAfterPageReload) {
                    recoverAfterPageReload = false
                    val restoreNativeFullscreen = restoreNativeFullscreenAfterRecoveryReload
                    restoreNativeFullscreenAfterRecoveryReload = false
                    if (restoreNativeFullscreen) {
                        // A native fullscreen view belongs to the old page instance. Re-enter
                        // YouTube's real fullscreen mode after the replacement video is ready.
                        playbackPositionHandler.postDelayed({
                            if (!isFinishing && !isDestroyed && webView != null &&
                                !explicitUserPause
                            ) {
                                requestYoutubeFullscreen()
                                playbackPositionHandler.postDelayed({
                                    if (!isFinishing && !isDestroyed && webView != null &&
                                        customView != null && !explicitUserPause
                                    ) {
                                        recoverFullscreenPlayback(allowPageReloadFallback = false)
                                    }
                                }, 900L)
                            }
                        }, 850L)
                    } else {
                        playbackPositionHandler.postDelayed({
                            if (!isFinishing && !isDestroyed && webView != null) {
                                // Recover on the replacement page without starting a second reload.
                                recoverFullscreenPlayback(allowPageReloadFallback = false)
                            }
                        }, 900L)
                    }
                }
                // If the player was not ready when fullscreen exit scheduled recovery, that
                // task will release its slot. Start recovery again now that YouTube is ready.
                // Do not duplicate the dedicated post-reload recovery above.
                if (!isRecoveryReload && pendingFullscreenExitGeometryCheck != null) {
                    scheduleFullscreenExitPlaybackRecovery(fullscreenExitGeneration, 350L)
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
        fun onVideoFrameStalled() {
            runOnUiThread {
                recoverVideoFrameStall()
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
                // Any fresh player-state event invalidates an older delayed recovery.
                val recoveryGeneration = ++unexpectedPlaybackRecoveryGeneration
                val previousPlayerState = youtubePlayerState
                val stateVideoId = sanitizeVideoId(videoId)
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
                // Treat a pause as an orientation handoff only when there is an actual
                // physical rotation/fullscreen transition. Play intent alone is not proof
                // of rotation: the old condition swallowed ordinary portrait pauses forever.
                val orientationHandoffActive =
                    System.currentTimeMillis() <= physicalOrientationNormalizationUntilMs ||
                    customView != null || isCssFullscreen || fullscreenLayoutActive ||
                    fullscreenExitTransitionInProgress || fullscreenExitRecoveryPending ||
                    pendingFullscreenExitGeometryCheck != null
                val playbackIntentStillActive =
                    prefs.getBoolean("youtube_playback_is_playing", false)
                val playbackIntentWasActive = !explicitUserPause && (
                    playbackIntentStillActive ||
                        resumePlaybackOnNextOrientationChange ||
                        previousPlayerState == 1
                    )

                // YouTube can emit a transient PAUSED event while Android is really rotating.
                // Preserve intent only during that known handoff; outside it, allow the
                // state machine below to register PAUSED and schedule one bounded probe.
                if (state == 2 &&
                    orientationHandoffActive &&
                    !explicitUserPause &&
                    (playbackIntentStillActive || resumePlaybackOnNextOrientationChange) &&
                    previousPlayerState != 0
                ) {
                    resumePlaybackOnNextOrientationChange = true
                    prefs.edit().putBoolean("youtube_playback_is_playing", true).apply()
                    return@runOnUiThread
                }

                // Do not publish a temporary PAUSED/BUFFERING state to the UI or media
                // session while the viewport is handing off between portrait and landscape.
                if ((state == 2 || state == 3) && orientationHandoffActive &&
                    !explicitUserPause && playbackIntentStillActive
                ) {
                    return@runOnUiThread
                }

                val recoverUnexpectedPause =
                    state == 2 && playbackIntentWasActive && !orientationHandoffActive &&
                        previousPlayerState != 0
                val recoverUnexpectedStall =
                    state == 3 && playbackIntentWasActive && !orientationHandoffActive &&
                        previousPlayerState != 0

                if (state == 1) {
                    // State 1 is reported only after the JS media clock advances. A
                    // confirmed healthy playback re-arms bounded recovery for a later stall.
                    geometryReloadFallbackAttemptedForCurrentPage = false
                    explicitUserPause = false
                    orientationPlaybackRecoveryGeneration++
                    backgroundPlaybackRecoveryGeneration++
                    resumePlaybackOnNextOrientationChange = true
                    if (customView != null || isCssFullscreen || fullscreenLayoutActive) {
                        wasPlayingBeforeFullscreen = true
                    }
                    prefs.edit().putBoolean("youtube_playback_is_playing", true).apply()
                } else if (state == 0 || (state == 2 && (!orientationHandoffActive || explicitUserPause))) {
                    // ENDED always clears intent. PAUSED clears it only when it is not a viewport
                    // handoff, or when the user explicitly asked to pause the player.
                    if (state == 0) explicitUserPause = false
                    resumePlaybackOnNextOrientationChange = false
                    if (customView != null || isCssFullscreen || fullscreenLayoutActive) {
                        wasPlayingBeforeFullscreen = false
                    }
                    prefs.edit().putBoolean("youtube_playback_is_playing", false).apply()
                }
                // State 3 (BUFFERING), and transient state 2 during rotation, preserve intent.
                youtubePlayerState = state
                updatePlaybackButton()
                syncYouTubeBackgroundPlayback(state)
                if (state == 5 || state == 1) restoreSavedPlaybackPosition()
                when {
                    recoverUnexpectedPause -> scheduleUnexpectedPlaybackRecovery(
                        recoveryGeneration, stateVideoId, expectedState = 2, delayMs = 900L
                    )
                    recoverUnexpectedStall -> scheduleUnexpectedPlaybackRecovery(
                        recoveryGeneration, stateVideoId, expectedState = 3, delayMs = 6000L
                    )
                }
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
    // One bounded in-place repair for an unexpected portrait pause/buffer stall.
    private var unexpectedPlaybackRecoveryGeneration = 0
    private var lastUnexpectedPlaybackRecoveryAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastKnownConfigurationOrientation = resources.configuration.orientation

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
                if (customView != null || isCssFullscreen || fullscreenLayoutActive) {
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
            if (customView != null || isCssFullscreen || fullscreenLayoutActive) exitFullscreen() else finish()
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
        playerHeaderView = header

        // Keep playerContainer attached to this host permanently. Do not move the WebView
        // between parent layouts when switching from portrait to landscape.
        val videoHost = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }
        videoHost.addView(
            playerContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        playerVideoHost = videoHost
        content.addView(videoHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.rgb(8, 9, 12))
        }
        responsiveLandscapeContainer = sidePanel

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
        sidePanel.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        playerInfoView = info

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
        sidePanel.addView(transport)
        playerTransportView = transport

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
        sidePanel.addView(queueActions)
        playerQueueActionsView = queueActions

        content.addView(sidePanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

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
        applyResponsivePlayerLayout()
        installPhysicalOrientationLayoutWatcher()
        updateActionState()
        updateQueueModeButtons()
    }

    /**
     * Match the reference landscape layout without detaching the active WebView.
     *
     * The video stays inside one permanent FrameLayout host for the entire Activity
     * lifetime. Rotation only changes weights and visibility of its sibling side panel.
     * This avoids the WebView detach/attach cycle that can freeze Chromium's video surface
     * or stop audio when the phone rotates from landscape back to portrait.
     */
    private fun applyResponsivePlayerLayout(
        landscapeOverride: Boolean? = null,
        force: Boolean = false
    ) {
        if (!::root.isInitialized || fullscreenLayoutActive ||
            isCssFullscreen || customView != null
        ) return

        val content = contentContainer ?: return
        val scroll = playerScrollView ?: return
        val header = playerHeaderView ?: return
        val videoHost = playerVideoHost ?: return
        val sidePanel = responsiveLandscapeContainer ?: return
        val shouldUseLandscape = landscapeOverride ?: (
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        )

        if (!force && shouldUseLandscape == landscapePlayerLayoutActive && scroll.parent != null) {
            return
        }

        scroll.isFillViewport = true
        content.orientation = if (shouldUseLandscape) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }
        header.visibility = if (shouldUseLandscape) View.GONE else View.VISIBLE
        sidePanel.orientation = LinearLayout.VERTICAL
        // Keep YouTube's WebView viewport full-width in landscape. A weighted side panel
        // makes the embedded mobile site reflow into a narrow column and misaligns its
        // internal video surface. Controls remain available in portrait and fullscreen.
        sidePanel.visibility = if (shouldUseLandscape) View.GONE else View.VISIBLE

        if (shouldUseLandscape) {
            content.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            videoHost.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            sidePanel.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        } else {
            content.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            videoHost.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            sidePanel.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        landscapePlayerLayoutActive = shouldUseLandscape
        root.requestLayout()
        root.invalidate()
        content.requestLayout()
        videoHost.requestLayout()
        playerContainer.requestLayout()
        sidePanel.requestLayout()
        scroll.requestLayout()
    }

    /**
     * Some Android/WebView combinations deliver size/layout changes before the Activity
     * configuration callback (or without an orientation enum transition). Detect an actual
     * portrait<->landscape swap at the activity root so recovery does not depend on that callback.
     */
    private fun installPhysicalOrientationLayoutWatcher() {
        val trackedRoot = root
        trackedRoot.addOnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width < 100 || height < 100 || oldWidth < 100 || oldHeight < 100) {
                return@addOnLayoutChangeListener
            }
            val newLandscape = width > height
            val oldLandscape = oldWidth > oldHeight
            if (newLandscape == oldLandscape) return@addOnLayoutChangeListener

            // Fullscreen owns its viewport lifecycle; do not start the inline rotation
            // recovery loop while the permanent WebView host is expanded.
            if (isCssFullscreen || customView != null || fullscreenLayoutActive) {
                return@addOnLayoutChangeListener
            }
            // Set the physical-rotation debounce before requesting layout. Otherwise the
            // responsive-layout resize callback can enqueue playback recovery first.
            handlePhysicalOrientationLayoutChange(newLandscape)
        }
    }

    /**
     * Physical rotation is layout-only. Keep the same WebView and HTML5 video element alive;
     * do not issue Play/Pause/seek, reload YouTube, or restart WebView timers because the
     * device changed orientation. Chromium gets the new viewport through normal measurement.
     */
    private fun handlePhysicalOrientationLayoutChange(targetLandscape: Boolean? = null) {
        if (isFinishing || isDestroyed || fullscreenExitTransitionInProgress ||
            !::root.isInitialized || !::playerContainer.isInitialized || isCssFullscreen ||
            customView != null || fullscreenLayoutActive
        ) return

        val isLandscape = targetLandscape ?: (
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        )
        // ConfigurationChanged and the root layout watcher can report the same physical
        // rotation milliseconds apart. Handle that viewport transition once; duplicate
        // callbacks must not cancel/requeue playback recovery against the same WebView.
        val now = System.currentTimeMillis()
        if (lastPhysicalOrientationTargetLandscape == isLandscape &&
            now - lastPhysicalOrientationHandledAtMs < 1500L
        ) {
            // If the first callback arrived before the responsive children were measured,
            // make sure the desired layout is still applied without starting another recovery.
            if (landscapePlayerLayoutActive != isLandscape) {
                applyResponsivePlayerLayout(landscapeOverride = isLandscape, force = true)
                scheduleInlinePlayerLayoutSync()
            }
            return
        }
        lastPhysicalOrientationTargetLandscape = isLandscape
        lastPhysicalOrientationHandledAtMs = now
        physicalOrientationNormalizationUntilMs = now + 5000L
        lastOrientationConfigurationChangeAtMs = now
        orientationPlaybackRecoveryGeneration++
        orientationRecoveryWindowUntilMs = now + 5000L

        // Preserve explicit PLAY intent before YouTube/WebView can emit a transient PAUSED
        // callback during rotation. Otherwise the return-to-portrait recovery is skipped
        // because the preference was cleared during the viewport handoff.
        val playbackPrefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val shouldPreservePlayback = !explicitUserPause && (
            youtubePlayerState == 1 ||
                resumePlaybackOnNextOrientationChange ||
                playbackPrefs.getBoolean("youtube_playback_is_playing", false) ||
                // YouTube may publish a transient PAUSED state before Android finishes
                // rotating the WebView viewport. If the user did not explicitly pause,
                // keep the play intent for any ready video that has not ended.
                (youtubePlayerReady && youtubePlayerState != 0)
            )
        if (shouldPreservePlayback) {
            resumePlaybackOnNextOrientationChange = true
            playbackPrefs.edit()
                .putBoolean("youtube_playback_is_playing", true)
                .putBoolean("youtube_is_playing", true)
                .apply()
            // Each distinct physical rotation gets one bounded repair opportunity. The page
            // reload remains a last resort inside recoverFullscreenPlayback, not a rotation action.
            geometryReloadFallbackAttemptedForCurrentPage = false
        }

        applyResponsivePlayerLayout(landscapeOverride = isLandscape, force = true)
        // Normalize the WebView viewport after the native layout has settled. This routine
        // keeps the existing WebView/video element and only resets scroll + emits resize
        // notifications during the orientation handoff; it does not rebuild the media page.
        scheduleInlinePlayerLayoutSync()

        // Request native layout/measure/invalidation first; do not restart or seek the
        // active media element during the actual orientation transition.
        root.requestLayout()
        root.invalidate()
        playerContainer.requestLayout()
        playerContainer.invalidate()
        webView?.requestLayout()
        webView?.invalidate()

        // A CSS-fullscreen exit changes requestedOrientation back to UNSPECIFIED, which
        // can dispatch the same configuration/layout callback as a physical rotation.
        // The fullscreen-exit controller already owns playback recovery during that handoff.
        // Starting a second orientation recovery here can issue competing Play/probe requests
        // against the same WebView and leave the video stopped after shrinking to portrait.
        if (fullscreenExitRecoveryPending ||
            pendingFullscreenExitGeometryCheck != null ||
            now <= fullscreenExitRecoveryActiveUntilMs
        ) return

        // Some WebView builds keep audio/currentTime moving while the video compositor stops
        // producing frames after a rotation. Probe the existing media node first; allow one
        // reload only after repeated probes confirm a frame-only stall. An explicit user Pause
        // invalidates this path, and healthy playback is never reloaded.
        val recoveryGeneration = ++automaticOrientationRecoveryGeneration
        playbackPositionHandler.postDelayed({
            if (recoveryGeneration != automaticOrientationRecoveryGeneration ||
                isFinishing || isDestroyed || explicitUserPause ||
                isCssFullscreen || customView != null ||
                fullscreenExitTransitionInProgress ||
                fullscreenExitRecoveryPending ||
                pendingFullscreenExitGeometryCheck != null ||
                System.currentTimeMillis() <= fullscreenExitRecoveryActiveUntilMs ||
                webView == null
            ) return@postDelayed
            val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            if (!prefs.getBoolean("youtube_playback_is_playing", false)) {
                return@postDelayed
            }
            recoverFullscreenPlayback(
                // Ordinary rotation keeps the current page. Only a confirmed rendered-frame
                // stall can use the one-shot saved-position reload fallback.
                allowPageReloadFallback = false,
                allowFrameReloadFallback = true
            )
        }, 1100L)
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

    /**
     * Use an app-managed fullscreen viewport for the native expand/shrink button.
     *
     * Clicking YouTube's fullscreen button transfers rendering to WebChromeClient's
     * custom view. On some WebView/Android combinations that second surface can remain
     * black after the phone rotates back to portrait while HTML5 audio keeps running.
     * Keep the existing WebView and media element attached, and resize their native host.
     */
    private fun requestYoutubeFullscreen() {
        if (isFinishing || isDestroyed || fullscreenExitTransitionInProgress ||
            !::root.isInitialized || !::playerContainer.isInitialized
        ) return

        if (fullscreenLayoutActive || customView != null || isCssFullscreen) {
            exitFullscreen()
            return
        }

        if (webView == null) {
            Toast.makeText(this, "Trình phát chưa sẵn sàng.", Toast.LENGTH_SHORT).show()
            return
        }

        fullscreenEntryPending = false
        wasPlayingBeforeFullscreen = !explicitUserPause &&
            (youtubePlayerState == 1 ||
                getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                    .getBoolean("youtube_playback_is_playing", false))
        enterFullscreen()
        updateNativeFullscreenButton()
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
                // A newly selected queue item gets its own single recovery budget.
                unexpectedPlaybackRecoveryGeneration++
                lastUnexpectedPlaybackRecoveryAtMs = 0L
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
                autoplayPendingForCurrentVideo = true
                explicitUserPause = false
                // The one-shot fallback budget belongs to the selected video, not to the
                // lifetime of this Activity/WebView.
                geometryReloadFallbackAttemptedForCurrentPage = false
                webView?.loadUrl(
                    Uri.parse("https://m.youtube.com/watch")
                        .buildUpon()
                        .appendQueryParameter("v", safeId)
                        .appendQueryParameter("autoplay", "1")
                        .appendQueryParameter("playsinline", "1")
                        .build().toString()
                )
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
        unexpectedPlaybackRecoveryGeneration++
        automaticOrientationRecoveryGeneration++
        explicitUserPause = true
        resumePlaybackOnNextOrientationChange = false
        wasPlayingBeforeFullscreen = false
        orientationRecoveryWindowUntilMs = 0L
        fullscreenExitGeneration++
        fullscreenExitRecoveryPending = false
        pendingFullscreenExitGeometryCheck = null
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
        // A fresh user Play action supersedes any delayed recovery/reload queued for the
        // previous viewport state. Let the user's command own the current media session.
        unexpectedPlaybackRecoveryGeneration++
        fullscreenRecoveryGeneration++
        explicitUserPause = false
        resumePlaybackOnNextOrientationChange = true
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 2200L
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", true)
            .apply()
    }

    /**
     * The media clock can advance while Chromium stops delivering visible video frames.
     * Keep a cooldown so the watchdog cannot repeatedly reload the same YouTube page.
     */
    private fun recoverVideoFrameStall() {
        if (isFinishing || isDestroyed || webView == null || explicitUserPause) return
        val now = System.currentTimeMillis()

        // Do not let the rendered-frame watchdog restart a healthy inline session.
        // On some Android WebView builds requestVideoFrameCallback may stop reporting
        // compositor frames even though the visible inline video is still playing.
        // A recovery here sends another Play nudge and can interrupt playback at startup.
        // Reserve this heavier recovery for fullscreen or a confirmed orientation handoff;
        // normal portrait playback must remain passive.
        val inFullscreen = customView != null || isCssFullscreen || fullscreenLayoutActive
        // Inline/portrait playback is intentionally excluded. Physical orientation changes
        // already have their own single recovery owner; letting this frame watchdog start a
        // second recovery after a transient configuration callback can interrupt the stream.
        if (!inFullscreen) return

        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        if (!prefs.getBoolean("youtube_player_foreground", true) ||
            !prefs.getBoolean("youtube_playback_is_playing", false)
        ) return
        if (now < frameStallRecoveryCooldownUntilMs) return
        frameStallRecoveryCooldownUntilMs = now + 12_000L

        if (customView != null || isCssFullscreen) scheduleCssFullscreenLayoutSync()
        // Fullscreen/rotation only: probe the existing media node before any bounded fallback.
        recoverFullscreenPlayback(
            allowPageReloadFallback = false,
            allowFrameReloadFallback = true
        )
    }

    private fun noteViewportPlaybackHandoff() {
        if (isFinishing || isDestroyed || webView == null || explicitUserPause) return
        val now = System.currentTimeMillis()
        // Suppress resize-originated recovery only during a physical-rotation handoff.
        // Fullscreen exit also sets orientationRecoveryWindowUntilMs, but must still record
        // playback intent before its own bounded recovery takes over.
        if (now <= physicalOrientationNormalizationUntilMs &&
            !fullscreenExitRecoveryPending &&
            pendingFullscreenExitGeometryCheck == null
        ) return
        orientationRecoveryWindowUntilMs = now + 5000L
        resumePlaybackOnNextOrientationChange = true
        getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
            .putBoolean("youtube_playback_is_playing", true)
            .apply()

        // Fullscreen exit owns the single playback-recovery loop until it has either
        // observed real media-clock progress or completed its bounded fallback. A WebView
        // resize during the same transition must not create a second loop that invalidates it.
        if (fullscreenExitRecoveryPending ||
            pendingFullscreenExitGeometryCheck != null ||
            now <= fullscreenExitRecoveryActiveUntilMs
        ) return
        // Outside a fullscreen exit, prefer the native configuration callback when it is recent.
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

            // A WebView player API can accept Play while the HTML5 media element stays
            // paused after a portrait viewport/layout handoff. Verify a single time after
            // the user command; only ask the bounded Play controller to retry when the
            // media is still truly paused/stalled. Do not compete with a buffering stream.
            if (command == "toggle" && (
                    response.contains("requested-api") ||
                        response.contains("requested-button") ||
                        response.contains("requested-video")
                    )
            ) {
                playbackPositionHandler.postDelayed({
                    if (generation != playbackCommandGeneration ||
                        explicitUserPause || isFinishing || isDestroyed ||
                        webView == null || fullscreenExitRecoveryPending ||
                        pendingFullscreenExitGeometryCheck != null
                    ) return@postDelayed

                    val verifyPortraitPlayback = """
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
                    webView?.evaluateJavascript(verifyPortraitPlayback) portraitPlaybackVerification@{ verifyResult ->
                        if (generation != playbackCommandGeneration ||
                            explicitUserPause || isFinishing || isDestroyed ||
                            fullscreenExitRecoveryPending ||
                            pendingFullscreenExitGeometryCheck != null
                        ) return@portraitPlaybackVerification

                        val mediaState = verifyResult.orEmpty().trim('"')
                        if (mediaState == "PAUSED" || mediaState == "STALLED") {
                            requestYouTubePlayback()
                        }
                    }
                }, 1400L)
            }

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

    /**
     * Recover an unexpected HTML5 pause or a sustained media-clock stall without reloading
     * YouTube. A real rotation/fullscreen transition owns its own recovery path; this fallback
     * is only for an unchanged inline player and issues at most one request per failed episode.
     */
    private fun scheduleUnexpectedPlaybackRecovery(
        generation: Int,
        expectedVideoId: String,
        expectedState: Int,
        delayMs: Long
    ) {
        playbackPositionHandler.postDelayed({
            if (generation != unexpectedPlaybackRecoveryGeneration ||
                explicitUserPause || isFinishing || isDestroyed ||
                sanitizeVideoId(videoId) != expectedVideoId ||
                youtubePlayerState != expectedState ||
                webView == null ||
                customView != null || isCssFullscreen || fullscreenLayoutActive ||
                fullscreenExitTransitionInProgress || fullscreenExitRecoveryPending ||
                pendingFullscreenExitGeometryCheck != null ||
                System.currentTimeMillis() <= physicalOrientationNormalizationUntilMs
            ) return@postDelayed

            // A recovery is allowed only once for the currently selected video.
            // Clearing this cooldown as soon as state becomes PLAYING causes a retry loop:
            // each short pause reissues Play and produces the very stutter we are avoiding.
            if (lastUnexpectedPlaybackRecoveryAtMs > 0L) return@postDelayed

            val player = webView ?: return@postDelayed
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
                  if (!video || !video.isConnected) return 'NO_VIDEO';
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
            player.evaluateJavascript(verifyScript) recoveryVerification@{ result ->
                if (generation != unexpectedPlaybackRecoveryGeneration ||
                    explicitUserPause || isFinishing || isDestroyed ||
                    player !== webView ||
                    sanitizeVideoId(videoId) != expectedVideoId ||
                    youtubePlayerState != expectedState
                ) return@recoveryVerification

                val mediaState = result.orEmpty().trim('"')
                val shouldResume = if (expectedState == 2) {
                    mediaState == "PAUSED"
                } else {
                    mediaState == "PAUSED" || mediaState == "STALLED"
                }
                if (shouldResume) {
                    lastUnexpectedPlaybackRecoveryAtMs = System.currentTimeMillis()
                    requestYouTubePlayback()
                }
            }
        }, delayMs)
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

        val directHtml5PlayScript = """
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
              if (!video || !video.isConnected || video.ended) return 'NO_VIDEO';
              try {
                var promise = video.play();
                if (promise && typeof promise.catch === 'function')
                  promise.catch(function() {});
                return 'DIRECT_PLAY_REQUESTED';
              } catch (_) {
                return 'DIRECT_PLAY_REJECTED';
              }
            })();
        """.trimIndent()

        fun isRequestStillCurrent(): Boolean =
            requestGeneration == playbackRequestGeneration &&
                !explicitUserPause && !isFinishing && !isDestroyed &&
                webView != null && !fullscreenExitRecoveryPending &&
                pendingFullscreenExitGeometryCheck == null

        fun verifyAfterDirectPlay(player: WebView) {
            player.evaluateJavascript(directHtml5PlayScript) directPlayCallback@{ directResult ->
                if (!isRequestStillCurrent()) return@directPlayCallback
                if (!directResult.orEmpty().contains("DIRECT_PLAY_REQUESTED")) return@directPlayCallback

                // The YouTube API has already been tried. One direct HTML5 request is
                // the only fallback; do not repeat API Play on a timer, which can cause
                // brief starts/stutters followed by a stopped stream.
                playbackPositionHandler.postDelayed({
                    if (!isRequestStillCurrent() || player !== webView) return@postDelayed
                    player.evaluateJavascript(verifyScript) finalVerification@{ finalResult ->
                        if (!isRequestStillCurrent() || player !== webView) return@finalVerification
                        if (finalResult.orEmpty().trim('"') == "PLAYING") {
                            geometryReloadFallbackAttemptedForCurrentPage = false
                        }
                    }
                }, 1400L)
            }
        }

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

                // Retry only requests that could not be issued at all. Once YouTube
                // acknowledges Play, verify the media clock instead of stacking commands.
                if (!accepted && remaining > 0 && !isFinishing && !isDestroyed &&
                    requestGeneration == playbackRequestGeneration
                ) {
                    playbackPositionHandler.postDelayed({ attempt(remaining - 1) }, 750L)
                } else if (accepted && (
                        response.contains("requested-api") ||
                        response.contains("requested-button") ||
                        response.contains("requested-video")
                    )) {
                    val player = webView ?: return@requestPlaybackCallback
                    playbackPositionHandler.postDelayed({
                        if (!isRequestStillCurrent() || player !== webView) return@postDelayed
                        player.evaluateJavascript(verifyScript) verifyPlaybackCallback@{ verifyResult ->
                            if (!isRequestStillCurrent() || player !== webView) return@verifyPlaybackCallback

                            when (verifyResult.orEmpty().trim('"')) {
                                "PLAYING" -> {
                                    geometryReloadFallbackAttemptedForCurrentPage = false
                                }
                                "PAUSED", "STALLED" -> verifyAfterDirectPlay(player)
                                "BUFFERING" -> {
                                    // Let the existing stream buffer before a single direct
                                    // fallback. Never issue another YouTube API Play in parallel.
                                    playbackPositionHandler.postDelayed({
                                        if (!isRequestStillCurrent() || player !== webView) return@postDelayed
                                        player.evaluateJavascript(verifyScript) bufferedVerification@{ bufferedResult ->
                                            if (!isRequestStillCurrent() || player !== webView) return@bufferedVerification
                                            when (bufferedResult.orEmpty().trim('"')) {
                                                "PLAYING" -> geometryReloadFallbackAttemptedForCurrentPage = false
                                                "PAUSED", "STALLED" -> verifyAfterDirectPlay(player)
                                            }
                                        }
                                    }, 1300L)
                                }
                            }
                        }
                    }, 1800L)
                }
            }
        }

        // Issue one Play transport command. If YouTube rejects it, wait for a new
        // media/ready-state event or a deliberate user tap instead of sending a burst of
        // retries. Repeated API Play commands are known to cause short starts followed by
        // stalls in this WebView player.
        attempt(0)
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
        unexpectedPlaybackRecoveryGeneration++
        lastUnexpectedPlaybackRecoveryAtMs = 0L
        removePlayer()
        autoplayPendingForCurrentVideo = true
        geometryReloadFallbackAttemptedForCurrentPage = false
        restoreNativeFullscreenAfterRecoveryReload = false
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
                    // Pass YouTube's requests through unchanged. Filtering its ad-break/player
                    // endpoints can disrupt the mobile site's own playback state machine and
                    // leave an otherwise loaded video paused or stalled after startup.
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
                    view.evaluateJavascript(
                        """
                        (function() {
                          try {
                            if (window.__ngocSiCleanPlayerInstalled) return 'already-installed';
                            window.__ngocSiCleanPlayerInstalled = true;

                            // Hide only page chrome outside the player. No width/height/transform/
                            // overflow/visibility changes are applied to the actual video surface.
                            var style = document.createElement('style');
                            style.id = 'ngocsi-youtube-minimal-style';
                            style.textContent =
                              '#masthead-container,ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer,' +
                              'ytm-app-header,ytm-slim-video-metadata-section-renderer,ytm-watch-metadata,' +
                              'ytm-video-description-infocontainer-renderer,ytm-comments-entry-point-header-renderer,' +
                              'ytm-comments-entry-point-teaser-renderer,ytm-comment-section-renderer,' +
                              'ytm-comment-renderer,ytm-comment-thread-renderer,ytm-comments-entry-point-renderer,' +
                              'ytm-comment-simplebox-renderer,ytm-engagement-panel,ytm-engagement-panel-section-list-renderer,' +
                              'ytm-rich-section-renderer,ytm-related-video-renderer,ytm-item-section-renderer {' +
                              'display:none!important;} ' +
                              '.ytp-title,.ytp-title-text,.ytp-title-link,.ytp-pause-overlay,' +
                              '.ytp-pause-overlay-container,.ytp-large-play-button,.ytp-cued-thumbnail-overlay,' +
                              '.ytp-next-button,.ytp-prev-button,.ytp-next-button-container,.ytp-prev-button-container,' +
                              '.ytp-fullscreen-button,.ytp-size-button,ytm-player-fullscreen-button-renderer,' +
                              '.ytp-endscreen-content,.ytp-endscreen-previous,.ytp-endscreen-next,' +
                              '.ytp-ce-covering-overlay,.ytp-cards-teaser,.ytp-ce-element,.ytp-show-cards-title {' +
                              'display:none!important;} ' +
                              // Keep YouTube's video compositor in normal document flow. Fixed-position
                              // ytm-watch plus 100%-height children produced black frames when the WebView
                              // viewport changed while the HTML5 media clock/audio kept running.
                              'html,body {margin:0!important;padding:0!important;width:100%!important;' +
                              'min-height:100%!important;overflow:hidden!important;background:#000!important;} ' +
                              'ytm-app {margin:0!important;padding:0!important;min-height:0!important;} ' +
                              'ytm-app.sticky-player {padding-top:0!important;} ' +
                              'ytm-watch {position:relative!important;top:0!important;left:0!important;' +
                              'right:auto!important;bottom:auto!important;width:100%!important;max-width:100%!important;' +
                              'min-height:0!important;margin:0!important;padding:0!important;transform:none!important;} ' +
                              '.player-container,.player-container.sticky-player {position:relative!important;' +
                              'top:0!important;left:0!important;right:auto!important;bottom:auto!important;' +
                              'width:100%!important;max-width:100%!important;margin:0!important;padding:0!important;' +
                              'transform:none!important;box-sizing:border-box!important;} ' +
                              '.player-placeholder {position:relative!important;top:0!important;left:0!important;' +
                              'right:auto!important;bottom:auto!important;width:100%!important;max-width:100%!important;' +
                              'height:auto!important;aspect-ratio:16/9!important;margin:0!important;padding:0!important;' +
                              'transform:none!important;box-sizing:border-box!important;} ' +
                              'ytm-player,#player-container-id,#player-container,#player {position:relative!important;' +
                              'top:0!important;left:0!important;right:auto!important;bottom:auto!important;' +
                              'transform:none!important;width:100%!important;max-width:100%!important;height:auto!important;' +
                              'min-height:0!important;max-height:none!important;aspect-ratio:16/9!important;' +
                              'margin:0!important;padding:0!important;box-sizing:border-box!important;} ' +
                              'ytm-player {display:block!important;} ' +
                              '@media (orientation:landscape) {' +
                              'html,body,ytm-app,ytm-watch {height:100%!important;min-height:100%!important;} ' +
                              'ytm-watch {display:block!important;} ' +
                              '.player-container,.player-placeholder {height:100%!important;max-height:100%!important;' +
                              'aspect-ratio:auto!important;} ' +
                              'ytm-player,#player-container-id,#player-container,#player {height:100%!important;' +
                              'max-height:100%!important;aspect-ratio:auto!important;} ' +
                              '.html5-video-player,#movie_player,.html5-video-container {position:relative!important;' +
                              'top:0!important;left:0!important;right:auto!important;bottom:auto!important;' +
                              'transform:none!important;width:100%!important;height:100%!important;' +
                              'max-width:100%!important;margin:0!important;box-sizing:border-box!important;} ' +
                              'video.html5-main-video {position:absolute!important;left:0!important;top:0!important;' +
                              'right:auto!important;bottom:auto!important;margin:0!important;transform:none!important;' +
                              'width:100%!important;height:100%!important;object-fit:contain!important;}}';
                            (document.head || document.documentElement).appendChild(style);

                            // Reset stale sticky positioning at every wrapper level after
                            // viewport changes. A measured negative margin on only one ancestor
                            // can become incorrect when the phone changes from portrait to landscape.
                            window.__ngocSiNormalizePlayerGeometry = function() {
                              try {
                                if (window.__ngocSiFullscreenActive) return 'FULLSCREEN_SKIPPED';
                                window.scrollTo(0, 0);
                                if (document.scrollingElement) {
                                  document.scrollingElement.scrollTop = 0;
                                  document.scrollingElement.scrollLeft = 0;
                                }
                                if (document.documentElement) {
                                  document.documentElement.scrollTop = 0;
                                  document.documentElement.scrollLeft = 0;
                                }
                                if (document.body) {
                                  document.body.scrollTop = 0;
                                  document.body.scrollLeft = 0;
                                }

                                var wrapperSelector = 'ytm-watch,.player-container,.player-placeholder,' +
                                  'ytm-player,#player-container-id,#player-container,#player,' +
                                  '.html5-video-player,#movie_player,.html5-video-container';
                                var wrappers = Array.prototype.slice.call(
                                  document.querySelectorAll(wrapperSelector)
                                );
                                wrappers.forEach(function(wrapper) {
                                  if (!wrapper || !wrapper.isConnected) return;
                                  wrapper.style.setProperty('position', 'relative', 'important');
                                  wrapper.style.setProperty('top', '0px', 'important');
                                  wrapper.style.setProperty('left', '0px', 'important');
                                  wrapper.style.setProperty('right', 'auto', 'important');
                                  wrapper.style.setProperty('bottom', 'auto', 'important');
                                  wrapper.style.setProperty('transform', 'none', 'important');
                                  wrapper.style.setProperty('margin-left', '0px', 'important');
                                  wrapper.style.setProperty('margin-right', '0px', 'important');
                                  wrapper.style.setProperty('box-sizing', 'border-box', 'important');
                                  wrapper.style.setProperty('width', '100%', 'important');
                                  wrapper.style.setProperty('max-width', '100%', 'important');
                                  wrapper.style.setProperty('min-width', '0', 'important');
                                });
                                Array.prototype.forEach.call(
                                  document.querySelectorAll('video.html5-main-video'),
                                  function(video) {
                                    video.style.setProperty('left', '0px', 'important');
                                    video.style.setProperty('top', '0px', 'important');
                                    video.style.setProperty('right', 'auto', 'important');
                                    video.style.setProperty('bottom', 'auto', 'important');
                                    video.style.setProperty('margin', '0px', 'important');
                                    video.style.setProperty('transform', 'none', 'important');
                                    video.style.setProperty('max-width', '100%', 'important');
                                  }
                                );
                                var viewportWidth = Math.max(
                                  1, document.documentElement.clientWidth || window.innerWidth || 1
                                );
                                return 'PLAYER_GEOMETRY_RESET|' + viewportWidth + '|' + wrappers.length;
                              } catch (_) {
                                return 'PLAYER_GEOMETRY_ERROR';
                              }
                            };

                            function videoVisible(video) {
                              if (!video || !video.isConnected) return false;
                              var rect = video.getBoundingClientRect();
                              if (rect.width < 2 || rect.height < 2) return false;
                              var node = video;
                              while (node && node.nodeType === 1 && node !== document.documentElement) {
                                var cs = window.getComputedStyle(node);
                                if (cs.display === 'none' || cs.visibility === 'hidden' ||
                                    Number(cs.opacity || 1) === 0) return false;
                                node = node.parentElement;
                              }
                              // Do not reject a connected, visible-to-CSS video solely because
                              // it is temporarily outside the viewport during rotation. The area
                              // intersection is already part of findActiveVideo's score, so an
                              // off-screen primary video remains selectable for Play/recovery.
                              return true;
                            }

                            function findActiveVideo() {
                              var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                              var visible = videos.filter(videoVisible);
                              function score(video) {
                                var r = video.getBoundingClientRect();
                                var vw = Math.max(1, document.documentElement.clientWidth || window.innerWidth || 1);
                                var vh = Math.max(1, document.documentElement.clientHeight || window.innerHeight || 1);
                                var visibleWidth = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
                                var visibleHeight = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
                                // Visible intersection outranks DOM order and stale playback flags.
                                var value = visibleWidth * visibleHeight * 100;
                                var primary = video.classList.contains('html5-main-video') ||
                                  !!video.closest('#movie_player') ||
                                  !!video.closest('.html5-video-player') ||
                                  !!video.closest('ytm-player');
                                if (primary) value += 1000000;
                                if (video.videoWidth > 0 && video.videoHeight > 0) value += 500000;
                                if (video.readyState >= 2) value += 400000;
                                if (!video.paused && !video.ended) value += 300000;
                                if (video.currentTime > 0) value += Math.min(video.currentTime, 600) * 100;
                                return value;
                              }
                              visible.sort(function(a,b) { return score(b) - score(a); });
                              return visible[0] || null;
                            }

                            window.__ngocSiFindActiveVideo = findActiveVideo;
                            // Legacy native recovery callers can request a regular resize, but
                            // this handler never writes styling to the page or video element.
                            window.__ngocSiFitPlayerResizeHandler = function() {
                              try { window.dispatchEvent(new Event('resize')); } catch (_) {}
                            };

                            var lastVideo = null;
                            var lastState = -99;
                            var lastCurrentTime = NaN;
                            var lastProgressAt = 0;
                            var lastFrameStallNoticeAt = 0;
                            var frameTrackedVideo = null;
                            var frameTrackingStartedAt = 0;

                            function trackVideo() {
                              var video = findActiveVideo();
                              if (!video) return;

                              if (!window.__ngocSiReadySent) {
                                window.__ngocSiReadySent = true;
                                window.__ngocSiBridgeInstalled = true;
                                try { AndroidBridge.onReady(); } catch (_) {}
                              }

                              if (video !== lastVideo) {
                                lastVideo = video;
                                lastState = -99;
                                lastCurrentTime = Number(video.currentTime || 0);
                                lastProgressAt = 0;
                                lastFrameStallNoticeAt = 0;
                              }

                              if (typeof video.requestVideoFrameCallback === 'function' &&
                                  frameTrackedVideo !== video) {
                                frameTrackedVideo = video;
                                frameTrackingStartedAt = Date.now();
                                window.__ngocSiFrameTrackedVideo = video;
                                window.__ngocSiLastVideoFrameAt = 0;
                                window.__ngocSiFrameTrackingStartedAt = frameTrackingStartedAt;
                                try {
                                  var onFrame = function() {
                                    if (frameTrackedVideo !== video) return;
                                    window.__ngocSiLastVideoFrameAt = Date.now();
                                    try { video.requestVideoFrameCallback(onFrame); } catch (_) {}
                                  };
                                  video.requestVideoFrameCallback(onFrame);
                                } catch (_) {}
                              }

                              var now = Date.now();
                              var currentTime = Number(video.currentTime || 0);
                              if (!video.paused && !video.ended && video.readyState >= 2 &&
                                  Number.isFinite(lastCurrentTime) &&
                                  currentTime > lastCurrentTime + 0.025) {
                                lastProgressAt = now;
                              }
                              lastCurrentTime = currentTime;

                              var clockAdvancing = lastProgressAt > 0 &&
                                now - lastProgressAt < 1800 &&
                                !video.paused && !video.ended && video.readyState >= 2;
                              window.__ngocSiTrackedVideo = video;
                              window.__ngocSiPlaybackProgressAt = lastProgressAt;
                              window.__ngocSiPlaybackActuallyPlaying = clockAdvancing;

                              var frameAt = Number(window.__ngocSiLastVideoFrameAt || 0);
                              var frameAge = frameAt > 0 ? now - frameAt : -1;
                              var hasFrameCallback = typeof video.requestVideoFrameCallback === 'function';
                              var trackingAge = frameTrackingStartedAt > 0 ? now - frameTrackingStartedAt : 0;
                              var noNewFrames = hasFrameCallback &&
                                (frameAge > 1900 || (frameAge < 0 && trackingAge > 2600));

                              // Audio/media time can move while Chromium stops composing frames.
                              // Notify Android only after a sustained, measurable frame stall.
                              if (clockAdvancing && Number(video.videoWidth || 0) > 0 && noNewFrames) {
                                if (!lastFrameStallNoticeAt || now - lastFrameStallNoticeAt > 9000) {
                                  lastFrameStallNoticeAt = now;
                                  try { AndroidBridge.onVideoFrameStalled(); } catch (_) {}
                                }
                              } else if (!clockAdvancing || (frameAge >= 0 && frameAge < 700)) {
                                lastFrameStallNoticeAt = 0;
                              }

                              var state = video.ended ? 0 :
                                (video.paused ? 2 : (clockAdvancing ? 1 : 3));
                              if (state !== lastState) {
                                lastState = state;
                                try { AndroidBridge.onStateChanged(state); } catch (_) {}
                              }
                            }

                            // Track explicit Play/Pause intent so orientation recovery does not
                            // override a deliberate user pause.
                            document.addEventListener('click', function(event) {
                              if (!event || !event.isTrusted) return;
                              var path = typeof event.composedPath === 'function'
                                ? event.composedPath() : [event.target];
                              for (var i = 0; i < path.length; i++) {
                                var node = path[i];
                                if (!node || node.nodeType !== 1) continue;
                                var label = [
                                  node.getAttribute && node.getAttribute('aria-label') || '',
                                  node.getAttribute && node.getAttribute('title') || '',
                                  node.getAttribute && node.getAttribute('data-tooltip-text') || '',
                                  node.innerText || node.textContent || '',
                                  typeof node.className === 'string' ? node.className : '',
                                  node.id || ''
                                ].join(' ').toLowerCase().replace(/\s+/g, ' ').trim();
                                if (/pause|tạm dừng|tam dung/.test(label)) {
                                  window.__ngocSiExplicitPauseAt = Date.now();
                                  try { AndroidBridge.onExplicitPauseRequested(); } catch (_) {}
                                  return;
                                }
                                if (/\bplay\b|phát|phat|resume|tiếp tục|tiep tuc/.test(label)) {
                                  window.__ngocSiExplicitPauseAt = 0;
                                  try { AndroidBridge.onExplicitPlayRequested(); } catch (_) {}
                                  return;
                                }
                              }
                            }, true);

                            var lastHandoffAt = 0;
                            var lastViewportLandscape =
                              (window.innerWidth || 1) > (window.innerHeight || 1);
                            function onViewportChange(event) {
                              var now = Date.now();
                              var viewportLandscape =
                                (window.innerWidth || 1) > (window.innerHeight || 1);
                              var actualOrientationChange = viewportLandscape !== lastViewportLandscape;
                              lastViewportLandscape = viewportLandscape;

                              // Browser/YouTube chrome and visualViewport can emit ordinary
                              // resize events during playback. They are not phone rotation.
                              // Only hand recovery to Android when the viewport crosses from
                              // portrait to landscape (or back), which is the transition we
                              // need to preserve across the same media element.
                              if (!actualOrientationChange) return;
                              if (now - lastHandoffAt < 650) return;
                              var explicitPauseAt = Number(window.__ngocSiExplicitPauseAt || 0);
                              if (explicitPauseAt > 0 && now - explicitPauseAt < 5000) return;
                              var progressAt = Number(window.__ngocSiPlaybackProgressAt || 0);
                              if (!window.__ngocSiTrackedVideo ||
                                  !window.__ngocSiPlaybackActuallyPlaying ||
                                  !progressAt || now - progressAt > 1800) return;
                              lastHandoffAt = now;
                              try { AndroidBridge.onViewportPlaybackHandoff(); } catch (_) {}
                            }
                            window.addEventListener('orientationchange', onViewportChange, {passive:true});
                            window.addEventListener('resize', onViewportChange, {passive:true});
                            if (window.visualViewport) {
                              window.visualViewport.addEventListener('resize', onViewportChange, {passive:true});
                            }

                            window.setInterval(trackVideo, 500);
                            trackVideo();
                            return 'clean-player-installed';
                          } catch (_) {
                            return 'clean-player-install-error';
                          }
                        })();
                        """.trimIndent(),
                        null
                    )
                    // Let Android/WebView own responsive dimensions. Only request one normal
                    // layout pass after YouTube has inserted its media node.
                    view.postDelayed({
                        if (!isFinishing && !isDestroyed && webView === view &&
                            customView == null && !isCssFullscreen
                        ) {
                            view.scrollTo(0, 0)
                            view.requestLayout()
                            view.invalidate()
                            scheduleInlinePlayerLayoutSync()
                        }
                    }, 350L)
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
                    if (customView != null || fullscreenLayoutActive) {
                        runCatching { callback.onCustomViewHidden() }
                        return
                    }

                    fullscreenEntryPending = false
                    (view.parent as? ViewGroup)?.removeView(view)

                    // Do not attach Chromium's separate fullscreen surface. Returning the
                    // callback immediately keeps the original mobile page/media element alive;
                    // then expand the permanent native WebView host for the same fullscreen view.
                    wasPlayingBeforeFullscreen = !explicitUserPause &&
                        (youtubePlayerState == 1 ||
                            getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
                                .getBoolean("youtube_playback_is_playing", false))
                    runCatching { callback.onCustomViewHidden() }
                    enterFullscreen()
                    updateNativeFullscreenButton()
                }

                override fun onHideCustomView() {
                    if (customView != null) exitFullscreen(notifyCallback = false)
                }
            }

            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(youtubeJsBridge, "AndroidBridge")

            // Load YouTube's mobile site in this embedded WebView. Background mode
            // keeps this same player alive and uses bounded recovery if the page pauses.
            val safeId = sanitizeVideoId(videoId)
            loadUrl(
                Uri.parse("https://m.youtube.com/watch")
                    .buildUpon()
                    .appendQueryParameter("v", safeId)
                    .appendQueryParameter("autoplay", "1")
                    .appendQueryParameter("playsinline", "1")
                    .build().toString()
            )
        }

        webView = player
        playerContainer.addView(
            player,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Do not depend on YouTube's mutable DOM/shadow-root fullscreen control. This native
        // overlay stays above the WebView and remains usable when the video image is black.
        nativeFullscreenButton = Button(this).apply {
            text = "⛶"
            textSize = 21f
            isAllCaps = false
            setTextColor(AndroidColor.WHITE)
            background = roundedButtonDrawable(
                AndroidColor.argb(220, 18, 18, 24),
                AndroidColor.rgb(112, 116, 128),
                dp(8)
            )
            backgroundTintList = null
            setPadding(dp(2), 0, dp(2), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            contentDescription = "Phóng to toàn màn hình"
            elevation = dp(6).toFloat()
            setOnClickListener { requestYoutubeFullscreen() }
        }.also { button ->
            playerContainer.addView(
                button,
                FrameLayout.LayoutParams(dp(44), dp(42), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(8)
                    marginEnd = dp(8)
                }
            )
            button.bringToFront()
        }
        updateNativeFullscreenButton()
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

        // Send one DOM viewport notification after native measurements settle. This lets
        // mobile YouTube recalculate its inline player controls/viewport on portrait return;
        // the same WebView and HTMLVideoElement remain attached throughout.
        root.postDelayed({
            if (generation != inlineLayoutSyncGeneration ||
                isFinishing || isDestroyed || customView != null || isCssFullscreen
            ) return@postDelayed
            val player = webView ?: return@postDelayed
            player.evaluateJavascript(
                """
                (function() {
                  try {
                    window.scrollTo(0, 0);
                    if (typeof window.__ngocSiNormalizePlayerGeometry === 'function') {
                      window.__ngocSiNormalizePlayerGeometry();
                    }
                    if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                    window.dispatchEvent(new Event('resize'));
                    if (window.visualViewport) {
                      window.visualViewport.dispatchEvent(new Event('resize'));
                    }
                    return 'inline-viewport-synced';
                  } catch (_) {
                    return 'inline-viewport-sync-failed';
                  }
                })();
                """.trimIndent(),
                null
            )
        }, 420L)
    }

    /**
     * Normalize the mobile YouTube document when opening the player or returning to portrait.
     * YouTube can restore a non-zero internal scroll offset after its own top bars are hidden;
     * the native WebView then looks like the video has slipped down inside the 16:9 frame.
     * Only run this while inline; never scroll or rewrite the document during fullscreen.
     */
    private fun normalizeInlineYoutubeViewport(player: WebView) {
        if (isFinishing || isDestroyed || player !== webView ||
            isCssFullscreen || customView != null
        ) return
        playerScrollView?.scrollTo(0, 0)
        player.scrollTo(0, 0)
        // Never write CSS to ytm-player, HTMLVideoElement or any ancestor. Native measure/
        // invalidate is sufficient once WebView receives the orientation's real viewport.
        player.requestLayout()
        player.invalidate()
    }

    /**
     * Re-fit the same YouTube surface to the latest WebView viewport during CSS fullscreen.
     * On Android, configuration callbacks can arrive before Chromium has committed its new
     * viewport. A few delayed measurements avoid retaining the previous portrait dimensions.
     */
    private fun scheduleCssFullscreenLayoutSync() {
        if (!::root.isInitialized || !::playerContainer.isInitialized) return
        val generation = ++fullscreenViewportSyncGeneration
        val player = webView
        val nativeView = customView
        val sync = object : Runnable {
            override fun run() {
                if (generation != fullscreenViewportSyncGeneration ||
                    isFinishing || isDestroyed
                ) return
                root.requestLayout()
                root.invalidate()
                playerContainer.requestLayout()
                playerContainer.invalidate()
                player?.requestLayout()
                player?.invalidate()
                nativeView?.requestLayout()
                nativeView?.invalidate()
            }
        }
        root.post(sync)
        root.postDelayed(sync, 140L)
        root.postDelayed(sync, 380L)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientationActuallyChanged =
            lastKnownConfigurationOrientation != android.content.res.Configuration.ORIENTATION_UNDEFINED &&
                lastKnownConfigurationOrientation != newConfig.orientation
        lastKnownConfigurationOrientation = newConfig.orientation
        // Invalidate resize-triggered playback recovery for this viewport transition. Rotation
        // must only resize the existing player; it must not overwrite playback state or arm a
        // page reload fallback.
        lastOrientationConfigurationChangeAtMs = System.currentTimeMillis()
        orientationPlaybackRecoveryGeneration++
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 5000L

        // The manifest handles orientation changes without recreating this Activity.
        if (!::playerContainer.isInitialized || !::root.isInitialized) return

        if (isCssFullscreen) {
            scheduleCssFullscreenLayoutSync()
            return
        }

        // App-managed fullscreen keeps the original WebView attached. On return to portrait,
        // leave fullscreen automatically; on landscape, only remeasure that same surface.
        if (fullscreenLayoutActive && customView == null) {
            if (orientationActuallyChanged &&
                newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            ) {
                exitFullscreen()
                return
            }
            // Do not immediately release the requested landscape orientation here. This callback
            // can be caused by our own forced landscape request while the handset is still
            // physically portrait; switching to FULL_SENSOR now makes Android snap straight back.
            // waitForPhysicalLandscapeBeforeUnlock() releases the lock after the sensor confirms
            // the phone was physically rotated to landscape.
            scheduleCssFullscreenLayoutSync()
            return
        }

        // Chromium's fullscreen custom view is a separate surface. Re-layout it after
        // configuration changes instead of leaving its original portrait measurements.
        customView?.let { fullscreenView ->
            if (orientationActuallyChanged &&
                newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            ) {
                exitFullscreen(notifyCallback = true)
                return
            }
            if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ) {
                // After the first rotation into landscape, allow the sensor to return to portrait.
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
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
        if (orientationActuallyChanged) {
            // Set the rotation debounce before any layout request can dispatch a WebView
            // resize callback. Keep the existing media element untouched.
            handlePhysicalOrientationLayoutChange(
                newConfig.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
        } else {
            applyResponsivePlayerLayout(
                landscapeOverride = newConfig.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
            scheduleInlinePlayerLayoutSync()
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
        nativeFullscreenButton = null
        fullscreenEntryPending = false
        playerContainer.takeIf { ::playerContainer.isInitialized }?.removeAllViews()
    }

    private fun updateNativeFullscreenButton() {
        nativeFullscreenButton?.apply {
            text = if (fullscreenLayoutActive || isCssFullscreen || customView != null) "↙" else "⛶"
            contentDescription = if (fullscreenLayoutActive || isCssFullscreen || customView != null) {
                "Thu nhỏ về trình phát"
            } else {
                "Phóng to toàn màn hình"
            }
            bringToFront()
        }
    }

    private fun setFullscreenLayout(active: Boolean) {
        val content = contentContainer ?: return
        val videoHost = playerVideoHost ?: return
        if (active == fullscreenLayoutActive) return

        if (active) {
            inlineContentPadding = intArrayOf(
                content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom
            )
            fullscreenHiddenChildVisibilities.clear()
            for (index in 0 until content.childCount) {
                val child = content.getChildAt(index)
                // Keep the permanent video host visible. The header/side panel are hidden,
                // but neither the host nor the WebView is detached during fullscreen changes.
                if (child !== videoHost) {
                    fullscreenHiddenChildVisibilities[child] = child.visibility
                    child.visibility = View.GONE
                }
            }
            inlineLoadingBarVisibility = loadingBar?.visibility
            loadingBar?.visibility = View.GONE
            content.setPadding(0, 0, 0, 0)
            fullscreenLayoutActive = true
            content.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            videoHost.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            fullscreenLayoutActive = false
            fullscreenHiddenChildVisibilities.forEach { (view, visibility) ->
                view.visibility = visibility
            }
            fullscreenHiddenChildVisibilities.clear()
            inlineContentPadding?.let { padding ->
                if (padding.size == 4) {
                    content.setPadding(padding[0], padding[1], padding[2], padding[3])
                }
            }
            inlineContentPadding = null
            inlineLoadingBarVisibility?.let { loadingBar?.visibility = it }
            inlineLoadingBarVisibility = null

            // Reapply the current orientation's weights after leaving fullscreen. This
            // restores the inline 16:9 host without moving the WebView to another parent.
            applyResponsivePlayerLayout(force = true)
        }

        videoHost.requestLayout()
        playerContainer.requestLayout()
        content.requestLayout()
        playerScrollView?.requestLayout()
        if (::root.isInitialized) root.requestLayout()
    }

    /**
     * Keep fullscreen locked to landscape while observing the physical handset orientation.
     * The display is initially forced to landscape by the app, so unlocking to FULL_SENSOR
     * in onConfigurationChanged would immediately snap it back to portrait. Wait until the
     * device itself is rotated to landscape, then watch for a physical return to portrait and
     * exit fullscreen cleanly without destroying the WebView/video element.
     */
    private fun monitorPhysicalOrientationDuringFullscreen() {
        fullscreenOrientationListener?.disable()
        waitingForPhysicalLandscape = true
        fullscreenPhysicalLandscapeReached = false
        val listener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!fullscreenLayoutActive || orientation ==
                    android.view.OrientationEventListener.ORIENTATION_UNKNOWN ||
                    isFinishing || isDestroyed
                ) return

                val physicallyLandscape =
                    orientation in 60..120 || orientation in 240..300
                val physicallyPortrait =
                    orientation in 0..30 || orientation in 150..210 || orientation in 330..359

                if (!fullscreenPhysicalLandscapeReached) {
                    if (physicallyLandscape) {
                        fullscreenPhysicalLandscapeReached = true
                        waitingForPhysicalLandscape = false
                    }
                    return
                }

                if (physicallyPortrait) {
                    // Leave the sensor callback before changing requestedOrientation or view
                    // hierarchy; exitFullscreen() disables this listener synchronously.
                    root.post {
                        if (!isFinishing && !isDestroyed && fullscreenLayoutActive &&
                            fullscreenPhysicalLandscapeReached
                        ) {
                            exitFullscreen()
                        }
                    }
                }
            }
        }
        fullscreenOrientationListener = listener
        if (listener.canDetectOrientation()) {
            listener.enable()
        } else {
            waitingForPhysicalLandscape = false
            listener.disable()
            fullscreenOrientationListener = null
        }
    }

    private fun clearFullscreenOrientationListener() {
        waitingForPhysicalLandscape = false
        fullscreenPhysicalLandscapeReached = false
        fullscreenOrientationListener?.disable()
        fullscreenOrientationListener = null
    }

    private fun enterFullscreen() {
        // The existing WebView stays attached; only sibling UI is hidden so the player
        // receives the full activity viewport rather than the space below the header/actions.
        setFullscreenLayout(true)
        monitorPhysicalOrientationDuringFullscreen()
        // Always lock display orientation during the forced transition. The sensor listener
        // separately detects when the handset physically turns back to portrait and exits this
        // mode; do not switch to FULL_SENSOR in the forced-landscape callback.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        updateNativeFullscreenButton()
    }

    private fun exitFullscreen(notifyCallback: Boolean = true) {
        fullscreenEntryPending = false
        // Sensor and configuration callbacks can request the same exit almost together.
        // Keep the first exit transaction; a second one would invalidate its generation
        // and cancel the only playback/frame-recovery sequence.
        if (fullscreenExitTransitionInProgress) return
        clearFullscreenOrientationListener()
        if (customView == null && !isCssFullscreen && !fullscreenLayoutActive) return
        fullscreenExitTransitionInProgress = true
        val exitGeneration = ++fullscreenExitGeneration
        // Cancel any orientation-recovery callback queued before the fullscreen button was
        // pressed. This exit has its own single recovery/geometry pipeline below.
        automaticOrientationRecoveryGeneration++
        // Each explicit fullscreen exit is a new recovery event. If the prior exit's
        // fallback did not restore playback, permit one new fallback for this exit rather
        // than permanently disabling reload recovery for the selected video.
        geometryReloadFallbackAttemptedForCurrentPage = false
        fullscreenExitPlaybackRecoveryScheduledGeneration = -1
        fullscreenExitRecoveryPending = true
        // Cover the full bounded playback-probe window, not just its start delay; resize and
        // configuration callbacks during this interval must not launch competing recoveries.
        fullscreenExitRecoveryActiveUntilMs = System.currentTimeMillis() + 5000L
        orientationRecoveryWindowUntilMs = System.currentTimeMillis() + 5000L
        // Invalidate older recovery/layout probes. Configuration callbacks may enqueue
        // another CSS pass while we wait for the WebView to finish removing fullscreen CSS.
        fullscreenRecoveryGeneration++
        fullscreenViewportSyncGeneration++

        val wasNativeFullscreen = customView != null
        val wasCssFullscreen = isCssFullscreen
        val wasManagedFullscreen = fullscreenLayoutActive && !wasNativeFullscreen && !wasCssFullscreen
        val persistedPlaybackIntent = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            .getBoolean("youtube_playback_is_playing", false)
        val playbackIntentBeforeExit = wasPlayingBeforeFullscreen ||
            resumePlaybackOnNextOrientationChange ||
            persistedPlaybackIntent ||
            youtubePlayerState == 1
        val shouldResumeAfterNativeFullscreen =
            wasNativeFullscreen && playbackIntentBeforeExit
        val shouldResumeAfterCssFullscreen =
            wasCssFullscreen && playbackIntentBeforeExit
        // Do not turn a deliberate user pause into an automatic play on the fullscreen callback.
        val shouldRecoverPlayback =
            !explicitUserPause && playbackIntentBeforeExit &&
                (shouldResumeAfterNativeFullscreen || shouldResumeAfterCssFullscreen || wasManagedFullscreen)
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
            applyResponsivePlayerLayout()
            updateNativeFullscreenButton()
            fullscreenExitTransitionInProgress = false
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())

            // Keep native 16:9 restoration ordered after the WebView confirms that
            // ngocsi-css-fullscreen was removed. Previously the host was shrunk first,
            // racing Chromium's fullscreen video surface and leaving a frozen frame.
            scheduleInlinePlayerLayoutSync()

            // Only inspect/repair geometry after the playback recovery controller has
            // either observed real media-clock progress or exhausted its bounded retries.
            // Starting the geometry reload during that window can interrupt the same WebView.
            pendingFullscreenExitGeometryCheck = null
            if (wasManagedFullscreen) {
                // The same DOM video element never left the WebView. Avoid starting the
                // native-custom-view reload/geometry pipeline on an ordinary portrait return.
                // The bounded frame-stall watchdog remains available if actual frame delivery
                // later stops while media time advances.
            } else if (shouldRecoverPlayback) {
                pendingFullscreenExitGeometryCheck = {
                    if (exitGeneration == fullscreenExitGeneration &&
                        !isFinishing && !isDestroyed && !explicitUserPause
                    ) {
                        scheduleFullscreenExitGeometryCheck(exitGeneration, shouldRecoverPlayback)
                    }
                }
            } else {
                scheduleFullscreenExitGeometryCheck(exitGeneration, shouldRecoverPlayback)
            }

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
                            if (wasManagedFullscreen) {
                                // No geometry callback is installed for managed fullscreen because
                                // the original DOM player stayed in the WebView. Still probe the
                                // media/frame clocks after the portrait viewport has settled.
                                playbackPositionHandler.postDelayed({
                                    if (exitGeneration == fullscreenExitGeneration &&
                                        !isFinishing && !isDestroyed && !explicitUserPause &&
                                        webView != null && youtubePlayerReady
                                    ) {
                                        recoverFullscreenPlayback(
                                            // The native view never changed, so the live page is
                                            // preferred. If it is still stalled after bounded probes,
                                            // the existing one-shot fallback reloads at the saved time.
                                            allowPageReloadFallback = true,
                                            allowFrameReloadFallback = true
                                        )
                                    }
                                }, 260L)
                            } else {
                                // Recovery runs only after native layout is back inline.
                                // If YouTube is not ready yet, onReady() schedules this exit's recovery.
                                scheduleFullscreenExitPlaybackRecovery(exitGeneration, 700L)
                            }
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
                    ['position','left','right','top','bottom','inset','width','height',
                   'max-width','max-height','min-width','min-height','aspect-ratio',
                   'box-sizing','overflow','transform','margin','padding'].forEach(function(prop) {
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
                  // Only inspect the video element itself. Ancestor visibility can change
                  // transiently during YouTube's fullscreen transition while the media remains
                  // healthy; treating that handoff as a hard failure caused unnecessary reloads.
                  videoVisible: (function() {
                    var computed = window.getComputedStyle(video);
                    return computed.display !== 'none' && computed.visibility !== 'hidden' &&
                      Number(computed.opacity || 1) >= 0.05;
                  })(),
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
                if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                if (document.documentElement) document.documentElement.scrollTop = 0;
                if (document.body) document.body.scrollTop = 0;
                window.scrollTo(0, 0);
                window.dispatchEvent(new Event('resize'));
                if (window.visualViewport) window.visualViewport.dispatchEvent(new Event('resize'));
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
            val videoVisible = info.optBoolean("videoVisible", true)
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
            // Audio can keep advancing while a stale inline visibility/opacity rule hides
            // the video layer. Treat that as a geometry failure even when its rectangle
            // still looks normal, so the existing bounded repair/reload path can recover it.
            val renderedVideoHidden = expectPlayback && !videoPaused && !videoVisible
            return renderedFrameStalled || renderedVideoHidden || verticalBroken || (!portraitMedia && (
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

    /**
     * Validate the real media clock and rendered-frame clock after exiting fullscreen.
     * If Chromium's video surface is stalled, rebuild the YouTube page at the last known
     * position instead of repeatedly sending Play to the same frozen media pipeline.
     */
    /**
     * Resume the existing HTML5 media session first after fullscreen exit. A single stale
     * frame/progress sample during the viewport handoff is not proof the page must be reloaded.
     * The shared recovery controller probes the media clock over several frames and only
     * uses its one-time page reload fallback if the current video still cannot advance.
     */
    private fun recoverPlaybackAfterFullscreenExit(exitGeneration: Int) {
        val player = webView ?: return
        if (isFinishing || isDestroyed || player !== webView ||
            exitGeneration != fullscreenExitGeneration || explicitUserPause
        ) return

        recoverFullscreenPlayback(allowPageReloadFallback = true)
    }

    private fun scheduleFullscreenExitPlaybackRecovery(exitGeneration: Int, delayMs: Long) {
        if (exitGeneration != fullscreenExitGeneration ||
            pendingFullscreenExitGeometryCheck == null ||
            explicitUserPause || isFinishing || isDestroyed
        ) return
        if (fullscreenExitPlaybackRecoveryScheduledGeneration == exitGeneration) return
        fullscreenExitPlaybackRecoveryScheduledGeneration = exitGeneration

        playbackPositionHandler.postDelayed({
            if (exitGeneration != fullscreenExitGeneration ||
                pendingFullscreenExitGeometryCheck == null ||
                explicitUserPause || isFinishing || isDestroyed
            ) return@postDelayed
            if (!youtubePlayerReady || webView == null) {
                // Don't leave a stale scheduled flag that would block onReady().
                fullscreenExitPlaybackRecoveryScheduledGeneration = -1
                return@postDelayed
            }
            recoverPlaybackAfterFullscreenExit(exitGeneration)
        }, delayMs)
    }

    private fun finishPendingFullscreenExitGeometryCheck() {
        val callback = pendingFullscreenExitGeometryCheck ?: return
        fullscreenExitPlaybackRecoveryScheduledGeneration = -1
        pendingFullscreenExitGeometryCheck = null
        // Keep resize/configuration callbacks quiet only for the geometry check's settling
        // window. If the user rotates again afterwards, normal orientation recovery must run.
        fullscreenExitRecoveryActiveUntilMs = System.currentTimeMillis() + 1800L
        callback()
    }

    private fun recoverFullscreenPlayback(
        allowPageReloadFallback: Boolean = true,
        allowFrameReloadFallback: Boolean = false
    ) {
        if (isFinishing || isDestroyed || webView == null || explicitUserPause) return
        val generation = ++fullscreenRecoveryGeneration
        var previousPosition: Double? = null
        var playRequested = false
        var fallbackNudgeSent = false
        var frameStallSamples = 0
        var frameRepairAttempted = false
        // Physical rotation may reload only when the rendered-frame watchdog proves a
        // video-surface stall; ordinary clock recovery remains reload-free in that path.
        var pageReloadFallbackUsed = !allowPageReloadFallback && !allowFrameReloadFallback

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
                    function report(state) {
                      var rect = video.getBoundingClientRect();
                      var frameSupported = typeof video.requestVideoFrameCallback === 'function';
                      var frameAt = window.__ngocSiFrameTrackedVideo === video
                        ? Number(window.__ngocSiLastVideoFrameAt || 0) : 0;
                      var frameAge = frameAt > 0 ? Date.now() - frameAt : -1;
                      return state + '|' + position + '|' + ready + '|' + frameAge + '|' +
                        (frameSupported ? '1' : '0') + '|' +
                        (isRenderable(video) ? '1' : '0') + '|' +
                        Number(rect.width || 0) + '|' + Number(rect.height || 0);
                    }
                    if (video.ended) return report('ENDED');

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
                        try { player.playVideo(); return report('REQUESTED'); } catch (_) {}
                      }
                      try {
                        var firstPromise = video.play();
                        if (firstPromise && typeof firstPromise.catch === 'function')
                          firstPromise.catch(function(){});
                        return report('REQUESTED');
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
                            return report('NUDGED');
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

                    return report(video.paused ? 'PAUSED' : 'ACTIVE');
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
                val frameAgeMs = fields.getOrNull(3)?.toLongOrNull() ?: -1L
                val hasFrameCallback = fields.getOrNull(4) == "1"
                val videoRenderable = fields.getOrNull(5) == "1"

                if (state == "ENDED") {
                    finishPendingFullscreenExitGeometryCheck()
                    return@evaluateJavascript
                }
                if (state == "REQUESTED" || state == "API_ONLY") playRequested = true

                val previous = previousPosition
                val clockAdvanced = previous != null && currentPosition != null &&
                    currentPosition > previous + 0.04
                // Audio/currentTime can keep moving while Chromium stops delivering the
                // video frames. Do not declare recovery healthy until frame delivery resumes.
                val frameStalledNow =
                    (hasFrameCallback && frameAgeMs > 1500L) ||
                    (hasFrameCallback && frameAgeMs < 0L && clockAdvanced && number >= 5) ||
                    (clockAdvanced && number >= 2 && !videoRenderable)
                if (frameStalledNow) {
                    frameStallSamples++
                } else if (hasFrameCallback && frameAgeMs >= 0L &&
                    frameAgeMs <= 1500L && videoRenderable
                ) {
                    frameStallSamples = 0
                }

                // Once two consecutive probes confirm stale/missing frames, perform one
                // lightweight compositor/layout repair before considering page recreation.
                if (frameStallSamples >= 2 && !frameRepairAttempted) {
                    frameRepairAttempted = true
                    val repairPlayer = webView
                    repairPlayer?.evaluateJavascript(
                        """
                        (function() {
                          try {
                            if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
                            window.scrollTo(0, 0);
                            window.dispatchEvent(new Event('resize'));
                            if (window.visualViewport) window.visualViewport.dispatchEvent(new Event('resize'));
                            return 'REMEASURE_REQUESTED';
                          } catch (_) { return 'REMEASURE_ERROR'; }
                        })();
                        """.trimIndent(),
                        null
                    )
                    repairPlayer?.post {
                        if (repairPlayer === webView && !isFinishing && !isDestroyed) {
                            repairPlayer.requestLayout()
                            repairPlayer.invalidate()
                            playerContainer.requestLayout()
                            playerContainer.invalidate()
                        }
                    }
                }

                if (clockAdvanced && !frameStalledNow) {
                    // Require both advancing media time and recent frame delivery when the
                    // WebView supports requestVideoFrameCallback.
                    geometryReloadFallbackAttemptedForCurrentPage = false
                    getSharedPreferences("ngoc_si_music", MODE_PRIVATE).edit()
                        .putBoolean("youtube_playback_is_playing", true)
                        .putBoolean("youtube_is_playing", true)
                        .apply()
                    finishPendingFullscreenExitGeometryCheck()
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
                var reloadScheduled = false
                val frameOnlyFallbackAllowed = allowPageReloadFallback ||
                    (allowFrameReloadFallback && frameStallSamples >= 3)
                if (frameOnlyFallbackAllowed &&
                    !pageReloadFallbackUsed &&
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
                        restoreNativeFullscreenAfterRecoveryReload = customView != null
                        recoverAfterPageReload = true
                        youtubePlayerReady = false
                        reloadScheduled = true
                        val recoveryPlayer = webView
                        recoveryPlayer?.postDelayed({
                            // An old orientation/fullscreen recovery must never reload the
                            // page after a newer recovery or an explicit user action supersedes it.
                            if (recoveryPlayer != null &&
                                generation == fullscreenRecoveryGeneration &&
                                !explicitUserPause && !isFinishing && !isDestroyed &&
                                recoveryPlayer === webView
                            ) {
                                recoveryPlayer.loadUrl(restoredUrl)
                            }
                        }, 300L)
                    }
                }
                // Keep the geometry check pending across a recovery reload. onReady will
                // run one fresh bounded recovery and complete it only after the clock moves.
                if (!reloadScheduled) finishPendingFullscreenExitGeometryCheck()
            }
        }

        attempt(0)
    }

    /**
     * Best-effort ad request filtering for the embedded YouTube WebView.
     * Do not filter googlevideo.com or broad Google/YouTube hosts because they
     * serve actual video/audio, player code, thumbnails and captions as well.
     */
    private fun sanitizeVideoId(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        clearFullscreenOrientationListener()
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


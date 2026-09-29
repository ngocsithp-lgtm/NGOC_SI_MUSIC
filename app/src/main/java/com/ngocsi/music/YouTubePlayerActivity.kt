package com.ngocsi.music

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
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
import android.view.Gravity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.URL

class YouTubePlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_title"
        const val EXTRA_CHANNEL = "youtube_channel"
        const val EXTRA_QUEUE_JSON = "youtube_queue_json"
        const val EXTRA_QUEUE_INDEX = "youtube_queue_index"
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
    private lateinit var channelView: TextView
    private lateinit var favoriteButton: Button
    private lateinit var watchLaterButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private data class QueueItem(val videoId: String, val title: String, val channelTitle: String, val thumbnailUrl: String)
    private val queue = mutableListOf<QueueItem>()
    private var queueIndex = 0
    private var errorView: LinearLayout? = null
    private var loadingBar: ProgressBar? = null
    private var pageErrorVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoId = sanitizeVideoId(intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty())
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "YouTube" }
        channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().ifBlank { "YouTube" }
        queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, 0).coerceAtLeast(0)
        parseQueue(intent.getStringExtra(EXTRA_QUEUE_JSON))
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

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(8), dp(4))
            setBackgroundColor(AndroidColor.rgb(18, 18, 22))
        }

        val backButton = Button(this).apply {
            text = "‹"
            isAllCaps = false
            textSize = 28f
            setTextColor(AndroidColor.WHITE)
            contentDescription = "Quay lại"
            setOnClickListener {
                if (customView != null) exitFullscreen() else finish()
            }
        }
        header.addView(
            backButton,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val nowPlaying = TextView(this).apply {
            text = "ĐANG PHÁT"
            setTextColor(AndroidColor.rgb(143, 214, 148))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        info.addView(nowPlaying)

        titleView = TextView(this).apply {
            text = title
            setTextColor(AndroidColor.WHITE)
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        channelView = TextView(this).apply {
            text = channel
            setTextColor(AndroidColor.rgb(155, 155, 166))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        info.addView(titleView)
        info.addView(channelView)

        header.addView(
            info,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }

        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // Keep the embedded player at the standard 16:9 video ratio instead of
        // stretching it to fill the entire remaining screen. This gives a more
        // predictable phone layout while fullscreen remains available from YouTube.
        val playerHeight = (resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        content.addView(playerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            playerHeight.coerceAtLeast(dp(200))
        ))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(3))
        }
        previousButton = actionButton("⏮") { playPrevious() }
        favoriteButton = actionButton("♡") { toggleFavorite() }
        watchLaterButton = actionButton("🔖") { toggleWatchLater() }
        nextButton = actionButton("⏭") { playNext() }
        previousButton.contentDescription = "Video trước"
        favoriteButton.contentDescription = "Yêu thích"
        watchLaterButton.contentDescription = "Xem sau"
        nextButton.contentDescription = "Video tiếp theo"
        actions.addView(previousButton, LinearLayout.LayoutParams(dp(50), dp(42)))
        actions.addView(favoriteButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        actions.addView(watchLaterButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
        actions.addView(nextButton, LinearLayout.LayoutParams(dp(50), dp(42)))
        content.addView(actions)

        val queueButton = Button(this).apply {
            text = "☷ HÀNG ĐỢI (" + (queueIndex + 1) + "/" + queue.size + ")"
            isAllCaps = false
            tag = "queue_button"
            setOnClickListener { showQueueDialog() }
        }
        content.addView(queueButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = dp(2)
        })

        val queueProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = queue.size.coerceAtLeast(1)
            progress = (queueIndex + 1).coerceIn(0, max)
            tag = "queue_progress"
            isIndeterminate = false
            contentDescription = "Tiến độ hàng đợi YouTube"
        }
        content.addView(queueProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(3)
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = dp(2)
            bottomMargin = dp(4)
        })

        val share = Button(this).apply {
            text = "↗ CHIA SẺ"
            isAllCaps = false
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + videoId)
                    putExtra(Intent.EXTRA_TITLE, title)
                }
                startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
            }
        }
        content.addView(share, LinearLayout.LayoutParams(dp(100), dp(44)).apply {
            gravity = Gravity.START
            leftMargin = dp(8)
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(AndroidColor.BLACK)
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
        root.addView(
            loadingBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
            )
        )

        setContentView(root)
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(AndroidColor.WHITE)
            setOnClickListener { onClick() }
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

    private fun showQueueDialog() {
        if (queue.isEmpty()) return

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("HÀNG ĐỢI YOUTUBE • " + (queueIndex + 1) + "/" + queue.size)
            .setView(list)
            .setNegativeButton("ĐÓNG", null)
            .create()

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
            Thread {
                val bitmap = runCatching {
                    URL(thumbnailUrl).openConnection().apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        useCaches = true
                    }.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (thumb.parent != null) thumb.setImageBitmap(bitmap)
                    }
                }
            }.start()

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
        if (queue.size <= 1 || queueIndex <= 0) return
        queueIndex--
        loadQueueItem()
    }

    private fun playNext() {
        if (queue.size <= 1 || queueIndex >= queue.lastIndex) return
        queueIndex++
        loadQueueItem()
    }

    private fun loadQueueItem() {
        val selected = queue.getOrNull(queueIndex) ?: return
        videoId = selected.videoId
        title = selected.title
        channel = selected.channelTitle
        createPlayer()
    }

    private fun saveLastPlayedState() {
        val queueJson = org.json.JSONArray().apply {
            queue.forEach { item ->
                put(org.json.JSONObject().apply {
                    put("videoId", item.videoId)
                    put("title", item.title)
                    put("channelTitle", item.channelTitle)
                    put("thumbnailUrl", item.thumbnailUrl)
                })
            }
        }.toString()

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

    private fun updateActionState() {
        if (::favoriteButton.isInitialized) favoriteButton.text = if (isFavorite()) "♥ Yêu thích" else "♡ Yêu thích"
        if (::watchLaterButton.isInitialized) watchLaterButton.text = if (isWatchLater()) "✓ Xem sau" else "🔖 Xem sau"
        if (::previousButton.isInitialized) previousButton.isEnabled = queueIndex > 0
        if (::nextButton.isInitialized) nextButton.isEnabled = queueIndex < queue.lastIndex
    }
    private fun createPlayer() {
        removePlayer()
        pageErrorVisible = false

        val player = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
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

            val safeId = sanitizeVideoId(videoId)
            // YouTube's current Android guidance allows a mobile app to host the
            // embedded player inside an HTML page and use the page baseUrl as the
            // HTTP Referer. This gives the embed a real enclosing web context and
            // is preferable to navigating directly to the embed URL.
            val appReferrer = "https://com.ngocsi.music/"
            val embedUrl = "https://www.youtube.com/embed/" + safeId +
                "?playsinline=1&autoplay=0&rel=0&controls=1&fs=1" +
                "&hl=vi&cc_lang_pref=vi"

            val html = """
                <!doctype html>
                <html lang="vi">
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <meta name="referrer" content="origin">
                    <style>
                        html, body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            height: 100%;
                            background: #000;
                            overflow: hidden;
                        }
                        iframe {
                            display: block;
                            width: 100%;
                            height: 100%;
                            border: 0;
                        }
                    </style>
                </head>
                <body>
                    <iframe
                        src="$embedUrl"
                        title="YouTube"
                        allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                        allowfullscreen>
                    </iframe>
                </body>
                </html>
            """.trimIndent()

            loadDataWithBaseURL(appReferrer, html, "text/html", "UTF-8", appReferrer)
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
        saveLastPlayedState()
    }

    private fun updateQueueButton() {
        root.findViewWithTag<Button>("queue_button")?.text = "☷ HÀNG ĐỢI (" + (queueIndex + 1) + "/" + queue.size + ")"
        root.findViewWithTag<ProgressBar>("queue_progress")?.apply {
            max = queue.size.coerceAtLeast(1)
            progress = (queueIndex + 1).coerceIn(0, max)
        }
    }

    private fun showError(message: String) {
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

                addView(messageView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(retry)
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
        errorView?.visibility = View.VISIBLE
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
        if (customView != null) exitFullscreen(notifyCallback = false)
        removePlayer()
        loadingBar = null
        super.onDestroy()
    }
}

package com.ngocsi.music

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.view.Window
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class YouTubePlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_title"
        const val EXTRA_CHANNEL = "youtube_channel"
    }

    private lateinit var root: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var videoId: String = ""
    private var title: String = "YouTube"
    private var channel: String = "YouTube"
    private var errorView: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoId = sanitizeVideoId(intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty())
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "YouTube" }
        channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().ifBlank { "YouTube" }

        if (videoId.isBlank()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK

        buildUi()
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
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundColor(AndroidColor.rgb(18, 18, 22))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(AndroidColor.WHITE)
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val channelView = TextView(this).apply {
            text = channel
            setTextColor(AndroidColor.rgb(155, 155, 166))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        info.addView(titleView)
        info.addView(channelView)

        val close = Button(this).apply {
            text = "Đóng"
            setTextColor(AndroidColor.WHITE)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setOnClickListener { finish() }
        }

        header.addView(
            info,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(close, LinearLayout.LayoutParams(dp(76), dp(48)))

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }

        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(playerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
    }

    private fun createPlayer() {
        removePlayer()

        val player = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowContentAccess = true
                allowFileAccess = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                databaseEnabled = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            CookieManager.getInstance().flush()

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    errorView?.visibility = View.GONE
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
                    exitFullscreen()
                }
            }

            isFocusable = true
            isFocusableInTouchMode = true

            val safeId = sanitizeVideoId(videoId)
            val embedUrl =
                "https://www.youtube.com/embed/$safeId" +
                "?playsinline=1&autoplay=0&rel=0&controls=1&fs=1" +
                "&enablejsapi=1&origin=https%3A%2F%2Fcom.ngocsi.music"

            loadUrl(
                embedUrl,
                mapOf(
                    "Referer" to "https://com.ngocsi.music/",
                    "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
                )
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
    }

    private fun showError(message: String) {
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(AndroidColor.BLACK)

                val text = TextView(this@YouTubePlayerActivity).apply {
                    setTextColor(AndroidColor.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }

                val retry = Button(this@YouTubePlayerActivity).apply {
                    this.text = "THỬ LẠI"
                    setOnClickListener {
                        visibility = View.GONE
                        createPlayer()
                    }
                }

                val open = Button(this@YouTubePlayerActivity).apply {
                    this.text = "MỞ YOUTUBE"
                    setOnClickListener {
                        runCatching {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.youtube.com/watch?v=$videoId")
                                )
                            )
                        }
                    }
                }

                addView(text, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(retry)
                addView(open)
                tag = text
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        customView?.let { root.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        playerContainer.visibility = View.VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun sanitizeVideoId(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        if (customView != null) exitFullscreen()
        removePlayer()
        super.onDestroy()
    }
}

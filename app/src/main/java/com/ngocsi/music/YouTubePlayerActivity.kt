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
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

class YouTubePlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "youtube_video_id"
        const val EXTRA_TITLE = "youtube_title"
        const val EXTRA_CHANNEL = "youtube_channel"
    }

    private lateinit var container: FrameLayout
    private var webView: WebView? = null
    private var videoId: String = ""
    private var title: String = "YouTube"
    private var channel: String = "YouTube"
    private var errorView: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setStatusBarColor(AndroidColor.BLACK)
        window.setNavigationBarColor(AndroidColor.BLACK)

        videoId = sanitizeVideoId(intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty())
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "YouTube" }
        channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().ifBlank { "YouTube" }

        if (videoId.isBlank()) {
            finish()
            return
        }

        buildUi()
        createPlayer()
    }

    private fun buildUi() {
        container = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AndroidColor.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(AndroidColor.rgb(18, 18, 22))
            gravity = android.view.Gravity.CENTER_VERTICAL
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

        info.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        info.addView(channelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = Button(this).apply {
            text = "Đóng"
            setTextColor(AndroidColor.WHITE)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setOnClickListener { finish() }
        }

        header.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(76), dp(48)))

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
    }

    private fun createPlayer() {
        removePlayer()

        val player = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)

            // Không ép LAYER_TYPE_SOFTWARE/HARDWARE; dùng compositor mặc định
            // của WebView + Activity riêng để tránh lỗi surface trắng/đen.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowContentAccess = true
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.requestLayout()
                    view.invalidate()
                    errorView?.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) showError("Không tải được trình phát YouTube.")
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    showError(
                        if (detail.didCrash()) {
                            "Trình phát YouTube gặp lỗi render. Hãy thử tải lại."
                        } else {
                            "Android đã dừng bộ render WebView. Hãy thử tải lại."
                        }
                    )
                    removePlayer()
                    return true
                }
            }

            webChromeClient = WebChromeClient()

            val embedUrl =
                "https://www.youtube.com/embed/$videoId" +
                    "?playsinline=1&autoplay=1&rel=0&controls=1&fs=1" +
                    "&enablejsapi=1&origin=https%3A%2F%2Fcom.ngocsi.music"

            // YouTube yêu cầu Referer cho embedded player trong Android WebView.
            loadUrl(
                embedUrl,
                mapOf("Referer" to "https://com.ngocsi.music/")
            )
        }

        webView = player
        container.addView(
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
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(AndroidColor.BLACK)

                val text = TextView(this@YouTubePlayerActivity).apply {
                    setTextColor(AndroidColor.WHITE)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                }

                val retry = Button(this@YouTubePlayerActivity).apply {
                    text = "THỬ LẠI"
                    setOnClickListener {
                        visibility = View.GONE
                        createPlayer()
                    }
                }

                addView(text, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(retry, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                tag = text
            }
            container.addView(
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
        container.removeAllViews()
        errorView = null
    }

    private fun sanitizeVideoId(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removePlayer()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack() else finish()
    }
}

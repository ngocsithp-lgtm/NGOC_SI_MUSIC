package com.ngocsi.music

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class TvPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "tv_url"
        const val EXTRA_TITLE = "tv_title"
        const val EXTRA_DESCRIPTION = "tv_description"
    }

    private lateinit var root: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var sourceUrl: String = ""
    private var sourceTitle: String = "TV"
    private var sourceDescription: String = "Truyền hình trực tuyến"
    private var errorView: LinearLayout? = null
    private var loadingBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        sourceTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "TV" }
        sourceDescription = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
            .ifBlank { "Truyền hình trực tuyến" }

        val parsed = runCatching { Uri.parse(sourceUrl) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> finish()
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
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setBackgroundColor(AndroidColor.rgb(18, 18, 22))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(this).apply {
            text = sourceTitle
            setTextColor(AndroidColor.WHITE)
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val descriptionView = TextView(this).apply {
            text = sourceDescription
            setTextColor(AndroidColor.rgb(155, 155, 166))
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        info.addView(titleView)
        info.addView(descriptionView)

        val openExternal = Button(this).apply {
            text = "Mở ngoài"
            setTextColor(AndroidColor.WHITE)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                }
            }
        }

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
        header.addView(openExternal, LinearLayout.LayoutParams(dp(98), dp(48)))
        header.addView(close, LinearLayout.LayoutParams(dp(76), dp(48)))

        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
        }

        content.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            playerContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        loadingBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
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

    private fun createPlayer() {
        removePlayer()

        val player = WebView(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                databaseEnabled = true
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
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    errorView?.visibility = View.GONE
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
                        showError(
                            "Không tải được nguồn TV. Nguồn có thể đang giới hạn WebView hoặc tạm ngừng."
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        showError(
                            "Nguồn TV từ chối tải trong ứng dụng (HTTP " +
                                errorResponse.statusCode + ")."
                        )
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    showError(
                        if (detail.didCrash()) {
                            "Trình render TV gặp lỗi. Hãy bấm Thử lại."
                        } else {
                            "Android đã dừng bộ render TV. Hãy bấm Thử lại."
                        }
                    )
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    loadingBar?.progress = newProgress
                    loadingBar?.visibility =
                        if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    request.deny()
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
            loadUrl(sourceUrl)
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

                val messageView = TextView(this@TvPlayerActivity).apply {
                    setTextColor(AndroidColor.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }

                val retry = Button(this@TvPlayerActivity).apply {
                    text = "THỬ LẠI"
                    setOnClickListener {
                        visibility = View.GONE
                        createPlayer()
                    }
                }

                val open = Button(this@TvPlayerActivity).apply {
                    text = "MỞ NGOÀI"
                    setOnClickListener {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                        }
                    }
                }

                addView(
                    messageView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(retry)
                addView(open)
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
        if (::playerContainer.isInitialized) {
            playerContainer.removeAllViews()
        }
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
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerContainer.visibility = View.VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        if (customView != null) {
            exitFullscreen(notifyCallback = false)
        }
        removePlayer()
        loadingBar = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

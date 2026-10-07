package com.ngocsi.music

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Internal map surface for NGOC SI MUSIC.
 *
 * The old osmdroid layer initialized but rendered only a blank/grid surface
 * on the affected devices. This keeps the map inside the app through WebView.
 */
class NgocSiMapController internal constructor(
    private val webView: WebView
) {
    private var ready = false
    private var lat = 10.8231
    private var lon = 106.6297
    private var layer = "standard"
    private var route = emptyList<Pair<Double, Double>>()
    private var zoom = 14

    internal fun markReady() {
        ready = true
    }

    private fun render() {
        if (!ready) return
        webView.post { webView.loadUrl(buildMapUrl()) }
    }

    private fun buildMapUrl(): String {
        if (route.size >= 2) {
            val start = route.first()
            val end = route.last()
            return "https://www.google.com/maps/dir/?api=1" +
                "&origin=" + Uri.encode(start.first.toString() + "," + start.second.toString()) +
                "&destination=" + Uri.encode(end.first.toString() + "," + end.second.toString()) +
                "&travelmode=driving"
        }

        val query = lat.toString() + "," + lon.toString()
        val layerArg = if (layer == "satellite") "&layer=s" else ""
        return "https://maps.google.com/maps?q=" + Uri.encode(query) +
            "&z=" + zoom + "&output=embed" + layerArg
    }

    fun setView(newLat: Double, newLon: Double) {
        lat = safeLat(newLat)
        lon = safeLon(newLon)
        zoom = max(zoom, 14)
        route = emptyList()
        render()
    }

    fun zoomIn() {
        zoom = min(19, zoom + 1)
        render()
    }

    fun zoomOut() {
        zoom = max(2, zoom - 1)
        render()
    }

    fun setLayer(newLayer: String) {
        layer = if (newLayer == "satellite") "satellite" else "standard"
        render()
    }

    fun fitRoute(routePoints: List<Pair<Double, Double>>) {
        route = routePoints.filter { it.first.isFinite() && it.second.isFinite() }
        if (route.size < 2) return

        val start = route.first()
        val end = route.last()
        lat = safeLat((start.first + end.first) / 2.0)
        lon = safeLon((start.second + end.second) / 2.0)

        val distance = approximateDistanceKm(
            start.first, start.second, end.first, end.second
        )
        zoom = when {
            distance > 1000 -> 7
            distance > 400 -> 8
            distance > 150 -> 9
            distance > 70 -> 10
            distance > 30 -> 11
            distance > 12 -> 12
            distance > 5 -> 13
            else -> 14
        }
        render()
    }

    fun updateMap(
        newLat: Double,
        newLon: Double,
        selectedLayer: String,
        routePoints: List<Pair<Double, Double>>,
        fitRoute: Boolean
    ) {
        lat = safeLat(newLat)
        lon = safeLon(newLon)
        layer = if (selectedLayer == "satellite") "satellite" else "standard"

        val newRoute = routePoints.filter { it.first.isFinite() && it.second.isFinite() }
        if (newRoute.size >= 2 && fitRoute) {
            fitRoute(newRoute)
        } else {
            route = newRoute
            render()
        }
    }

    fun destroy() {
        ready = false
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
}

@Composable
fun NativeOsmMapView(
    modifier: Modifier = Modifier,
    lat: Double,
    lon: Double,
    selectedLayer: String,
    routePoints: List<Pair<Double, Double>>,
    isCurrentLocation: Boolean,
    accuracyMeters: Float,
    onMapReady: (NgocSiMapController) -> Unit
) {
    var controller by remember { mutableStateOf<NgocSiMapController?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                val webView = WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.rgb(14, 17, 22))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString =
                        "NGOC-SI-MUSIC/" + BuildConfig.VERSION_NAME + " Android WebView Map"
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            controller?.markReady()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            controller?.markReady()
                        }
                    }
                }

                val mapController = NgocSiMapController(webView)
                controller = mapController
                onMapReady(mapController)
                webView.loadUrl(buildInitialUrl(lat, lon, selectedLayer, routePoints))
                webView
            },
            update = {
                controller?.let { mapController ->
                    onMapReady(mapController)
                    mapController.updateMap(
                        newLat = lat,
                        newLon = lon,
                        selectedLayer = selectedLayer,
                        routePoints = routePoints,
                        fitRoute = routePoints.size >= 2
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .background(
                    ComposeColor(0xD9000000),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                when {
                    routePoints.size >= 2 -> "CHỈ ĐƯỜNG • BẢN ĐỒ TRONG ỨNG DỤNG"
                    selectedLayer == "satellite" -> "VỆ TINH • BẢN ĐỒ TRONG ỨNG DỤNG"
                    isCurrentLocation -> "VỊ TRÍ HIỆN TẠI • BẢN ĐỒ TRONG ỨNG DỤNG"
                    else -> "BẢN ĐỒ • TRONG ỨNG DỤNG"
                },
                color = ComposeColor.White,
                fontSize = 8.sp,
                maxLines = 1
            )
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller?.destroy()
            controller = null
        }
    }
}

private fun buildInitialUrl(
    lat: Double,
    lon: Double,
    selectedLayer: String,
    routePoints: List<Pair<Double, Double>>
): String {
    val validRoute = routePoints.filter { it.first.isFinite() && it.second.isFinite() }
    if (validRoute.size >= 2) {
        val start = validRoute.first()
        val end = validRoute.last()
        return "https://www.google.com/maps/dir/?api=1" +
            "&origin=" + Uri.encode(start.first.toString() + "," + start.second.toString()) +
            "&destination=" + Uri.encode(end.first.toString() + "," + end.second.toString()) +
            "&travelmode=driving"
    }

    val layerArg = if (selectedLayer == "satellite") "&layer=s" else ""
    return "https://maps.google.com/maps?q=" +
        Uri.encode(safeLat(lat).toString() + "," + safeLon(lon).toString()) +
        "&z=14&output=embed" + layerArg
}

private fun approximateDistanceKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
    val x = dLon * cos(meanLat)
    val y = dLat
    return 6371.0 * kotlin.math.sqrt(x * x + y * y)
}

private fun safeLat(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-85.0, 85.0) else 10.8231

private fun safeLon(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-180.0, 180.0) else 106.6297

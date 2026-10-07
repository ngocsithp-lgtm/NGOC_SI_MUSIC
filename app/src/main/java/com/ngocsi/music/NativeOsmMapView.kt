package com.ngocsi.music

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon

private val NgocSiSatelliteSource = XYTileSource(
    "NgocSi-Esri-WorldImagery",
    1,
    19,
    256,
    ".jpg",
    arrayOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    ),
    "Esri World Imagery"
)

@Composable
fun NativeOsmMapView(
    modifier: Modifier = Modifier,
    lat: Double,
    lon: Double,
    selectedLayer: String,
    routePoints: List<Pair<Double, Double>>,
    isCurrentLocation: Boolean,
    accuracyMeters: Float,
    onMapReady: (MapView) -> Unit
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setMultiTouchControls(true)
                setBuiltInZoomControls(false)
                setUseDataConnection(true)
                isClickable = true
                configureNativeMap(
                    this,
                    context,
                    lat,
                    lon,
                    selectedLayer,
                    routePoints,
                    isCurrentLocation,
                    accuracyMeters,
                    fitRoute = routePoints.size >= 2
                )
                mapView = this
                onMapReady(this)
                onResume()
            }
        },
        update = { view ->
            mapView = view
            onMapReady(view)
            val stateKey = buildString {
                append(safeLat(lat))
                append('|')
                append(safeLon(lon))
                append('|')
                append(selectedLayer)
                append('|')
                append(isCurrentLocation)
                append('|')
                append(accuracyMeters.toInt())
                append('|')
                append(routePoints.hashCode())
            }
            if (view.tag != stateKey) {
                configureNativeMap(
                    view,
                    view.context,
                    lat,
                    lon,
                    selectedLayer,
                    routePoints,
                    isCurrentLocation,
                    accuracyMeters,
                    fitRoute = routePoints.size >= 2
                )
                view.tag = stateKey
            }
        }
    )

    DisposableEffect(mapView) {
        mapView?.onResume()
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }
}

private fun configureOsmdroid(context: Context) {
    val appContext = context.applicationContext
    val baseDir = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
    val cacheDir = File(baseDir, "tiles").apply { mkdirs() }
    val preferences = appContext.getSharedPreferences("ngocsi_osmdroid", Context.MODE_PRIVATE)
    Configuration.getInstance().load(appContext, preferences)
    Configuration.getInstance().userAgentValue = "com.ngocsi.music/${BuildConfig.VERSION_NAME}"
    Configuration.getInstance().osmdroidBasePath = baseDir
    Configuration.getInstance().osmdroidTileCache = cacheDir
}

private fun configureNativeMap(
    map: MapView,
    context: Context,
    lat: Double,
    lon: Double,
    selectedLayer: String,
    routePoints: List<Pair<Double, Double>>,
    isCurrentLocation: Boolean,
    accuracyMeters: Float,
    fitRoute: Boolean
) {
    val safeCenter = GeoPoint(safeLat(lat), safeLon(lon))
    map.setTileSource(if (selectedLayer == "satellite") NgocSiSatelliteSource else TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    map.setBuiltInZoomControls(false)
    map.setUseDataConnection(true)

    val overlays = map.overlays
    overlays.clear()

    val safeRoute = routePoints.mapNotNull { (pointLat, pointLon) ->
        val a = safeLat(pointLat)
        val o = safeLon(pointLon)
        if (a.isFinite() && o.isFinite()) GeoPoint(a, o) else null
    }

    if (safeRoute.size >= 2) {
        val line = Polyline(map, true).apply {
            setColor(Color.rgb(108, 92, 231))
            setWidth(11f * context.resources.displayMetrics.density)
            setGeodesic(false)
            setPoints(safeRoute)
        }
        overlays.add(line)
        overlays.add(createLabelMarker(map, safeRoute.first(), "A", Color.rgb(32, 166, 106)))
        overlays.add(createLabelMarker(map, safeRoute.last(), "B", Color.rgb(229, 82, 104)))

        if (fitRoute) {
            map.post {
                if (safeRoute.size >= 2) {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(safeRoute), true, dp(context, 72))
                }
            }
        }
    } else {
        if (isCurrentLocation && accuracyMeters > 0f) {
            overlays.add(createAccuracyRing(map, safeCenter, accuracyMeters.coerceIn(5f, 1000f)))
        }
        overlays.add(createDotMarker(map, safeCenter, context, if (isCurrentLocation) "VỊ TRÍ HIỆN TẠI" else null))
        map.controller.setCenter(safeCenter)
        if (map.zoomLevel < 10.0) map.controller.setZoom(14.0)
    }

    map.invalidate()
}

private fun createDotMarker(
    map: MapView,
    point: GeoPoint,
    context: Context,
    title: String?
): Marker {
    val size = dp(context, 24)
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(108, 92, 231))
        setStroke(dp(context, 3), Color.WHITE)
        setSize(size, size)
    }
    return Marker(map).apply {
        position = point
        icon = drawable
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title
    }
}

private fun createAccuracyRing(map: MapView, point: GeoPoint, accuracyMeters: Float): Polygon {
    val polygon = Polygon(map)
    val samples = 72
    val radius = accuracyMeters.toDouble()
    val earth = 6_378_137.0
    val latRad = Math.toRadians(point.latitude)
    val points = ArrayList<GeoPoint>(samples)
    for (i in 0 until samples) {
        val angle = 2.0 * Math.PI * i / samples
        val dLat = (radius * Math.cos(angle)) / earth
        val dLon = (radius * Math.sin(angle)) / (earth * Math.max(0.1, Math.cos(latRad)))
        points += GeoPoint(
            point.latitude + Math.toDegrees(dLat),
            point.longitude + Math.toDegrees(dLon)
        )
    }
    polygon.setPoints(points)
    polygon.setFillColor(Color.argb(32, 108, 92, 231))
    polygon.setStrokeColor(Color.argb(150, 108, 92, 231))
    polygon.setStrokeWidth(dp(map.context, 2).toFloat())
    return polygon
}

private fun createLabelMarker(
    map: MapView,
    point: GeoPoint,
    label: String,
    color: Int
): Marker {
    val density = map.context.resources.displayMetrics.density
    val size = (32 * density).toInt().coerceAtLeast(24)
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * density).toInt().coerceAtLeast(2), Color.WHITE)
        setSize(size, size)
    }
    return Marker(map).apply {
        position = point
        icon = drawable
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "Điểm $label"
    }
}

fun centerNativeOsmMap(map: MapView?, lat: Double, lon: Double) {
    map?.let {
        it.controller.animateTo(GeoPoint(safeLat(lat), safeLon(lon)))
        it.invalidate()
    }
}

fun zoomInNativeOsmMap(map: MapView?) {
    map?.controller?.zoomIn()
    map?.invalidate()
}

fun zoomOutNativeOsmMap(map: MapView?) {
    map?.controller?.zoomOut()
    map?.invalidate()
}

fun fitNativeOsmMapRoute(map: MapView?, routePoints: List<Pair<Double, Double>>) {
    val points = routePoints.map { GeoPoint(safeLat(it.first), safeLon(it.second)) }
    if (map != null && points.size >= 2) {
        map.post {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, dp(map.context, 72))
        }
    }
}

private fun safeLat(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-85.0, 85.0) else 10.8231

private fun safeLon(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-180.0, 180.0) else 106.6297

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

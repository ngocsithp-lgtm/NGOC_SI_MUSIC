package com.ngocsi.music

import android.content.Context
import android.os.Bundle
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.max
import kotlin.math.min

private const val BASE_SOURCE = "ngocsi-base"
private const val BASE_LAYER = "ngocsi-base-layer"
private const val ROUTE_SOURCE = "ngocsi-route-source"
private const val ROUTE_LAYER = "ngocsi-route-layer"
private const val MARKER_SOURCE = "ngocsi-marker-source"
private const val MARKER_LAYER = "ngocsi-marker-layer"
private const val ROUTE_START_SOURCE = "ngocsi-route-start-source"
private const val ROUTE_START_LAYER = "ngocsi-route-start-layer"
private const val ROUTE_DEST_SOURCE = "ngocsi-route-dest-source"
private const val ROUTE_DEST_LAYER = "ngocsi-route-dest-layer"
private const val ACCURACY_SOURCE = "ngocsi-accuracy-source"
private const val ACCURACY_LAYER = "ngocsi-accuracy-layer"
private const val BEARING_SOURCE = "ngocsi-bearing-source"
private const val BEARING_LAYER = "ngocsi-bearing-layer"

private const val MAP_CACHE_SIZE_BYTES = 50L * 1024L * 1024L
private val mapHttpLock = Any()
@Volatile private var mapHttpConfigured = false

private fun configureMapHttp(context: Context) {
    if (mapHttpConfigured) return
    synchronized(mapHttpLock) {
        if (mapHttpConfigured) return
        val cacheDir = java.io.File(context.cacheDir, "ngocsi-map-cache").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, MAP_CACHE_SIZE_BYTES))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "NGOC-SI-MUSIC/${BuildConfig.VERSION_NAME} (+https://github.com/ngocsithp-lgtm/NGOC_SI_MUSIC)"
                    )
                    .header("X-Requested-With", BuildConfig.APPLICATION_ID)
                    .build()
                chain.proceed(request)
            }
            .build()
        HttpRequestUtil.setOkHttpClient(client)
        mapHttpConfigured = true
    }
}

class NgocSiMapController internal constructor(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val onLoadError: (String) -> Unit,
    private val onMapLoaded: () -> Unit
) {
    private var lat = 10.8231
    private var lon = 106.6297
    private var layer = "standard"
    private var route = emptyList<Pair<Double, Double>>()
    private var currentLocation = false
    private var accuracyMeters = 0f
    private var bearingDegrees = 0f
    private var hasBearing = false
    private var zoom = 14.0
    private val failListener = object : MapView.OnDidFailLoadingMapListener {
        override fun onDidFailLoadingMap(errorMessage: String) {
            onLoadError(errorMessage.ifBlank { "Không tải được dữ liệu bản đồ" })
        }
    }
    private val finishListener = object : MapView.OnDidFinishLoadingMapListener {
        override fun onDidFinishLoadingMap() {
            onMapLoaded()
        }
    }

    init {
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.addOnDidFinishLoadingMapListener(finishListener)
    }

    fun setView(newLat: Double, newLon: Double) {
        lat = safeLat(newLat)
        lon = safeLon(newLon)
        route = emptyList()
        currentLocation = true
        zoom = max(zoom, 14.0)
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom),
            350
        )
        updateSources()
    }

    fun zoomIn() {
        zoom = min(19.0, map.cameraPosition.zoom + 1.0)
        map.animateCamera(CameraUpdateFactory.zoomTo(zoom), 180)
    }

    fun zoomOut() {
        zoom = max(2.0, map.cameraPosition.zoom - 1.0)
        map.animateCamera(CameraUpdateFactory.zoomTo(zoom), 180)
    }

    fun reload() {
        applyStyle(preserveCamera = true)
    }

    fun setLayer(newLayer: String) {
        val normalized = if (newLayer == "satellite") "satellite" else "standard"
        if (layer == normalized) return
        layer = normalized
        applyStyle(preserveCamera = true)
    }

    fun fitRoute(routePoints: List<Pair<Double, Double>>) {
        val valid = routePoints.filter { it.first.isFinite() && it.second.isFinite() }
        if (valid.size < 2) return
        route = valid
        val start = valid.first()
        val end = valid.last()
        lat = safeLat((start.first + end.first) / 2.0)
        lon = safeLon((start.second + end.second) / 2.0)
        // Fit the whole route instead of estimating zoom from only the
        // start/end points. Curved routes can otherwise be clipped badly.
        runCatching {
            val builder = LatLngBounds.Builder()
            valid.forEach { (pointLat, pointLon) ->
                builder.include(LatLng(pointLat, pointLon))
            }
            val bounds = builder.build()
            map.getCameraForLatLngBounds(bounds, intArrayOf(96, 120, 96, 160))?.let { camera ->
                zoom = camera.zoom
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(camera),
                    450
                )
            } ?: map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 13.0),
                350
            )
        }.onFailure {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 13.0),
                350
            )
        }
        updateSources()
    }

    fun updateMap(
        newLat: Double,
        newLon: Double,
        selectedLayer: String,
        routePoints: List<Pair<Double, Double>>,
        isCurrentLocation: Boolean,
        accuracyMeters: Float,
        bearingDegrees: Float,
        hasBearing: Boolean,
        fitRoute: Boolean,
        followLocation: Boolean
    ) {
        lat = safeLat(newLat)
        lon = safeLon(newLon)
        currentLocation = isCurrentLocation
        this.accuracyMeters = accuracyMeters.coerceAtLeast(0f)
        this.bearingDegrees = if (bearingDegrees.isFinite()) {
            ((bearingDegrees % 360f) + 360f) % 360f
        } else {
            0f
        }
        this.hasBearing = hasBearing

        val valid = routePoints.filter { it.first.isFinite() && it.second.isFinite() }
        val requestedLayer = if (selectedLayer == "satellite") "satellite" else "standard"
        val routeChanged = valid != route

        // Persist the complete visible state before changing style. Otherwise a
        // layer toggle could briefly rebuild the style with stale GPS/route data.
        route = if (valid.size >= 2) valid else emptyList()

        if (requestedLayer != layer) {
            layer = requestedLayer
            applyStyle(preserveCamera = true)
            return
        }

        if (valid.size >= 2) {
            if (fitRoute && routeChanged) {
                fitRoute(valid)
            } else if (followLocation && isCurrentLocation) {
                zoom = max(zoom, 16.0)
                val camera = CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .bearing(
                        if (hasBearing) bearingDegrees.toDouble()
                        else map.cameraPosition.bearing
                    )
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(camera),
                    300
                )
                updateSources()
            } else {
                updateSources()
            }
        } else {
            updateSources()
            if (map.cameraPosition.zoom < 10.0) {
                zoom = 14.0
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
            }
        }
    }

    private fun applyStyle(preserveCamera: Boolean) {
        val camera = map.cameraPosition
        map.setStyle(Style.Builder().fromJson(buildStyleJson(layer))) {
            addOverlayLayers(it)
            if (preserveCamera) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(camera).build()))
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
            }
            updateSources()
        }
    }

    private fun updateSources() {
        val style = map.style ?: return
        val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val markerSource = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE) ?: return
        val startSource = style.getSourceAs<GeoJsonSource>(ROUTE_START_SOURCE) ?: return
        val destinationSource = style.getSourceAs<GeoJsonSource>(ROUTE_DEST_SOURCE) ?: return
        val accuracySource = style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE) ?: return
        val bearingSource = style.getSourceAs<GeoJsonSource>(BEARING_SOURCE) ?: return

        routeSource.setGeoJson(buildRouteGeoJson(route))
        markerSource.setGeoJson(
            if (currentLocation) buildMarkerGeoJson(lat, lon, true, accuracyMeters) else EMPTY_GEO_JSON
        )
        accuracySource.setGeoJson(
            if (currentLocation && accuracyMeters > 0f) buildAccuracyGeoJson(lat, lon, accuracyMeters)
            else EMPTY_GEO_JSON
        )
        bearingSource.setGeoJson(
            if (currentLocation && hasBearing) buildBearingGeoJson(lat, lon, bearingDegrees)
            else EMPTY_GEO_JSON
        )
        if (route.size >= 2) {
            startSource.setGeoJson(buildPointGeoJson(route.first()))
            destinationSource.setGeoJson(buildPointGeoJson(route.last()))
        } else {
            startSource.setGeoJson(EMPTY_GEO_JSON)
            destinationSource.setGeoJson(EMPTY_GEO_JSON)
        }
    }

    fun destroy() {
        runCatching { mapView.removeOnDidFailLoadingMapListener(failListener) }
        runCatching { mapView.removeOnDidFinishLoadingMapListener(finishListener) }
        runCatching { mapView.onPause() }
        runCatching { mapView.onStop() }
        runCatching { mapView.onDestroy() }
    }
}

private fun addOverlayLayers(style: Style) {
    val routeSource = GeoJsonSource(ROUTE_SOURCE, EMPTY_GEO_JSON)
    val markerSource = GeoJsonSource(MARKER_SOURCE, EMPTY_GEO_JSON)
    val startSource = GeoJsonSource(ROUTE_START_SOURCE, EMPTY_GEO_JSON)
    val destinationSource = GeoJsonSource(ROUTE_DEST_SOURCE, EMPTY_GEO_JSON)
    val accuracySource = GeoJsonSource(ACCURACY_SOURCE, EMPTY_GEO_JSON)
    val bearingSource = GeoJsonSource(BEARING_SOURCE, EMPTY_GEO_JSON)
    style.addSource(routeSource)
    style.addSource(markerSource)
    style.addSource(startSource)
    style.addSource(destinationSource)
    style.addSource(accuracySource)
    style.addSource(bearingSource)
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor("#6C5CE7"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineOpacity(0.95f)
        )
    )
    style.addLayer(
        CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
            PropertyFactory.circleColor("#6C5CE7"),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleOpacity(0.95f)
        )
    )
    style.addLayer(
        CircleLayer(ROUTE_START_LAYER, ROUTE_START_SOURCE).withProperties(
            PropertyFactory.circleColor("#20C997"),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleOpacity(1f)
        )
    )
    style.addLayer(
        CircleLayer(ROUTE_DEST_LAYER, ROUTE_DEST_SOURCE).withProperties(
            PropertyFactory.circleColor("#FF5A67"),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleOpacity(1f)
        )
    )
    style.addLayer(
        FillLayer(ACCURACY_LAYER, ACCURACY_SOURCE).withProperties(
            PropertyFactory.fillColor("#6C5CE7"),
            PropertyFactory.fillOpacity(0.16f),
            PropertyFactory.fillOutlineColor("#6C5CE7")
        )
    )
    style.addLayer(
        FillLayer(BEARING_LAYER, BEARING_SOURCE).withProperties(
            PropertyFactory.fillColor("#FFFFFF"),
            PropertyFactory.fillOpacity(0.95f),
            PropertyFactory.fillOutlineColor("#6C5CE7")
        )
    )
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
    bearingDegrees: Float = 0f,
    hasBearing: Boolean = false,
    followLocation: Boolean = false,
    onMapReady: (NgocSiMapController) -> Unit
) {
    var controller by remember { mutableStateOf<NgocSiMapController?>(null) }
    var mapLoadError by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                MapLibre.getInstance(context.applicationContext)
                configureMapHttp(context.applicationContext)
                val mapOptions = MapLibreMapOptions()
                    .textureMode(true)
                    .logoEnabled(false)
                    .attributionEnabled(true)
                    .compassEnabled(true)
                    .scrollGesturesEnabled(true)
                    .zoomGesturesEnabled(true)
                    .rotateGesturesEnabled(true)
                    .tiltGesturesEnabled(false)
                    .also { it.prefetchesTiles = false }

                MapView(context, mapOptions).apply {
                    onCreate(Bundle())
                    getMapAsync { map ->
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true
                        map.uiSettings.isScrollGesturesEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = false

                        // Register load listeners before the first style is requested.
                        val mapController = NgocSiMapController(
                            mapView = this,
                            map = map,
                            onLoadError = { message -> mapLoadError = message.take(160) },
                            onMapLoaded = { mapLoadError = null }
                        )
                        controller = mapController
                        onMapReady(mapController)

                        map.setStyle(Style.Builder().fromJson(buildStyleJson(selectedLayer))) { style ->
                            addOverlayLayers(style)
                            mapController.updateMap(
                                newLat = lat,
                                newLon = lon,
                                selectedLayer = selectedLayer,
                                routePoints = routePoints,
                                isCurrentLocation = isCurrentLocation,
                                accuracyMeters = accuracyMeters,
                                bearingDegrees = bearingDegrees,
                                hasBearing = hasBearing,
                                fitRoute = routePoints.size >= 2,
                                followLocation = followLocation
                            )
                        }
                    }
                    onStart()
                    onResume()
                }
            },
            update = {
                controller?.let { c ->
                    onMapReady(c)
                    c.updateMap(
                        newLat = lat,
                        newLon = lon,
                        selectedLayer = selectedLayer,
                        routePoints = routePoints,
                        isCurrentLocation = isCurrentLocation,
                        accuracyMeters = accuracyMeters,
                        bearingDegrees = bearingDegrees,
                        hasBearing = hasBearing,
                        fitRoute = false,
                        followLocation = followLocation
                    )
                }
            }
        )

        mapLoadError?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp)
                    .background(ComposeColor(0xF20D1118), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "BẢN ĐỒ CHƯA TẢI ĐƯỢC",
                            color = ComposeColor.White,
                            fontSize = 9.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            message,
                            color = ComposeColor(0xFFB8C0CC),
                            fontSize = 8.sp,
                            maxLines = 2
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            mapLoadError = null
                            controller?.reload()
                        }
                    ) {
                        Text("THỬ LẠI", fontSize = 9.sp)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .background(ComposeColor(0xD9000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                when {
                    selectedLayer == "satellite" -> "© Esri • dữ liệu ảnh vệ tinh"
                    routePoints.size >= 2 -> "© OpenStreetMap contributors"
                    isCurrentLocation -> "© OpenStreetMap contributors"
                    else -> "© OpenStreetMap contributors"
                },
                color = ComposeColor.White,
                fontSize = 8.sp,
                maxLines = 1
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.destroy()
            controller = null
        }
    }
}

private const val EMPTY_GEO_JSON =
    "{\"type\":\"FeatureCollection\",\"features\":[]}";

private fun buildStyleJson(selectedLayer: String): String {
    val satellite = selectedLayer == "satellite"

    // Use more than one tile endpoint. On some mobile networks a single
    // hostname can fail while another endpoint is still reachable. MapLibre
    // supports multiple raster tile URLs for one source and will request
    // whichever endpoint is available.
    val rasterTile = if (satellite) {
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    } else {
        // Use an OSM-derived raster endpoint that is independent from the
        // OSMF standard tile hostname. This avoids a blank basemap when the
        // standard endpoint is unreachable from a particular mobile network.
        // Normal viewport-only requests are kept; no prefetch/bulk download.
        "https://tile.openstreetmap.de/{z}/{x}/{y}.png"
    }

    val attribution = if (satellite) "© Esri" else "© OpenStreetMap contributors"
    val backgroundColor = if (satellite) "#11151B" else "#E9EDF1"
    val tileJson = "\"" + rasterTile + "\""

    return """
        {
          "version":8,
          "name":"NGOC SI MUSIC MAP",
          "sources":{
            "$BASE_SOURCE":{
              "type":"raster",
              "tiles":[$tileJson],
              "tileSize":256,
              "minzoom":1,
              "maxzoom":19,
              "scheme":"xyz",
              "bounds":[-180,-85.051129,180,85.051129],
              "attribution":"$attribution"
            }
          },
          "layers":[
            {
              "id":"ngocsi-background",
              "type":"background",
              "paint":{"background-color":"$backgroundColor"}
            },
            {
              "id":"$BASE_LAYER",
              "type":"raster",
              "source":"$BASE_SOURCE",
              "minzoom":1,
              "maxzoom":19,
              "paint":{
                "raster-opacity":1,
                "raster-fade-duration":0
              }
            }
          ]
        }
    """.trimIndent()
}
private fun buildRouteGeoJson(routePoints: List<Pair<Double, Double>>): String {
    if (routePoints.size < 2) return EMPTY_GEO_JSON
    val coordinates = routePoints.joinToString(",") { (pointLat, pointLon) ->
        "[" + safeLon(pointLon) + "," + safeLat(pointLat) + "]"
    }
    return """
        {"type":"FeatureCollection","features":[{"type":"Feature","properties":{},
        "geometry":{"type":"LineString","coordinates":[$coordinates]}}]}
    """.trimIndent()
}

private fun buildPointGeoJson(point: Pair<Double, Double>): String {
    val pointLat = safeLat(point.first)
    val pointLon = safeLon(point.second)
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + pointLon + "," + pointLat + "]}}]}"
}

private fun buildAccuracyGeoJson(lat: Double, lon: Double, accuracyMeters: Float): String {
    val radiusMeters = accuracyMeters.coerceIn(1f, 5_000f).toDouble()
    val latRad = Math.toRadians(safeLat(lat))
    val metersPerLat = 111_320.0
    val metersPerLon = (111_320.0 * kotlin.math.cos(latRad)).coerceAtLeast(10.0)
    val dLat = radiusMeters / metersPerLat
    val dLon = radiusMeters / metersPerLon
    val points = (0 until 40).joinToString(",") { index ->
        val angle = (2.0 * Math.PI * index) / 40.0
        "[" + safeLon(lon + kotlin.math.cos(angle) * dLon) + "," +
            safeLat(lat + kotlin.math.sin(angle) * dLat) + "]"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[" + points + "]]}}]}"
}

private fun buildBearingGeoJson(lat: Double, lon: Double, bearingDegrees: Float): String {
    val bearing = Math.toRadians(bearingDegrees.toDouble())
    val latRad = Math.toRadians(safeLat(lat))
    val metersPerLat = 111_320.0
    val metersPerLon = (111_320.0 * kotlin.math.cos(latRad)).coerceAtLeast(10.0)

    fun offset(metersForward: Double, metersSide: Double): String {
        val east = metersForward * kotlin.math.sin(bearing) + metersSide * kotlin.math.cos(bearing)
        val north = metersForward * kotlin.math.cos(bearing) - metersSide * kotlin.math.sin(bearing)
        val pointLat = safeLat(lat + north / metersPerLat)
        val pointLon = safeLon(lon + east / metersPerLon)
        return "[" + pointLon + "," + pointLat + "]"
    }

    val polygon = listOf(
        offset(22.0, 0.0),
        offset(-10.0, -7.0),
        offset(-5.0, 0.0),
        offset(-10.0, 7.0),
        offset(22.0, 0.0)
    ).joinToString(",")

    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[" + polygon + "]]}}]}"
}
private fun buildMarkerGeoJson(lat: Double, lon: Double, isCurrentLocation: Boolean, accuracyMeters: Float): String {
    val kind = if (isCurrentLocation && accuracyMeters > 0f) "current" else "view"
    return """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "properties":{"kind":"$kind"},
        "geometry":{"type":"Point","coordinates":[${safeLon(lon)},${safeLat(lat)}]}}]}
    """.trimIndent()
}

private fun safeLat(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-85.0, 85.0) else 10.8231

private fun safeLon(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-180.0, 180.0) else 106.6297

fun centerNativeOsmMap(map: NgocSiMapController?, lat: Double, lon: Double) {
    map?.setView(lat, lon)
}

fun zoomInNativeOsmMap(map: NgocSiMapController?) {
    map?.zoomIn()
}

fun zoomOutNativeOsmMap(map: NgocSiMapController?) {
    map?.zoomOut()
}

fun fitNativeOsmMapRoute(map: NgocSiMapController?, routePoints: List<Pair<Double, Double>>) {
    map?.fitRoute(routePoints)
}
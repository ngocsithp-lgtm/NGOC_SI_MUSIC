package com.ngocsi.music

import android.os.Bundle
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
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
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

class NgocSiMapController internal constructor(
    private val mapView: MapView,
    private val map: MapLibreMap
) {
    private var lat = 10.8231
    private var lon = 106.6297
    private var layer = "standard"
    private var route = emptyList<Pair<Double, Double>>()
    private var currentLocation = false
    private var accuracyMeters = 0f
    private var zoom = 14.0

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
        currentLocation = false
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
            map.getCameraForLatLngBounds(bounds, arrayOf(96, 120, 96, 160))?.let { camera ->
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
        fitRoute: Boolean
    ) {
        lat = safeLat(newLat)
        lon = safeLon(newLon)
        val requestedLayer = if (selectedLayer == "satellite") "satellite" else "standard"
        if (requestedLayer != layer) {
            layer = requestedLayer
            applyStyle(preserveCamera = true)
            return
        }
        currentLocation = isCurrentLocation
        this.accuracyMeters = accuracyMeters.coerceAtLeast(0f)
        val valid = routePoints.filter { it.first.isFinite() && it.second.isFinite() }

        if (valid.size >= 2) {
            if (fitRoute && valid != route) {
                fitRoute(valid)
            } else {
                route = valid
                updateSources()
            }
        } else {
            route = emptyList()
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
        routeSource.setGeoJson(buildRouteGeoJson(route))
        markerSource.setGeoJson(buildMarkerGeoJson(lat, lon, currentLocation, accuracyMeters))
    }

    fun destroy() {
        runCatching { mapView.onPause() }
        runCatching { mapView.onStop() }
        runCatching { mapView.onDestroy() }
    }
}

private fun addOverlayLayers(style: Style) {
    val routeSource = GeoJsonSource(ROUTE_SOURCE, EMPTY_GEO_JSON)
    val markerSource = GeoJsonSource(MARKER_SOURCE, EMPTY_GEO_JSON)
    style.addSource(routeSource)
    style.addSource(markerSource)
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
                MapLibre.getInstance(context.applicationContext)
                MapView(context).apply {
                    onCreate(Bundle())
                    getMapAsync { map ->
                        map.uiSettings.isCompassEnabled = true
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true
                        map.uiSettings.isScrollGesturesEnabled = true
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isTiltGesturesEnabled = false
                        map.setStyle(Style.Builder().fromJson(buildStyleJson(selectedLayer))) { style ->
                            addOverlayLayers(style)
                            val mapController = NgocSiMapController(this, map)
                            controller = mapController
                            onMapReady(mapController)
                            mapController.updateMap(
                                newLat = lat,
                                newLon = lon,
                                selectedLayer = selectedLayer,
                                routePoints = routePoints,
                                isCurrentLocation = isCurrentLocation,
                                accuracyMeters = accuracyMeters,
                                fitRoute = routePoints.size >= 2
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
                        fitRoute = false
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
                .background(ComposeColor(0xD9000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                when {
                    routePoints.size >= 2 -> "CHỈ ĐƯỜNG • © OSM"
                    selectedLayer == "satellite" -> "VỆ TINH • © ESRI"
                    isCurrentLocation -> "VỊ TRÍ HIỆN TẠI • © OSM"
                    else -> "BẢN ĐỒ • © OSM"
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
    val rasterTiles = if (satellite) {
        // Current Esri World Imagery tile service.
        "https://wi.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    } else {
        // OpenStreetMap standard tiles replace the retired Esri World Street Map raster service.
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }
    val attribution = if (satellite) {
        "© Esri"
    } else {
        "© OpenStreetMap contributors"
    }
    val backgroundColor = if (satellite) "#11151B" else "#E9EDF1"

    return """
        {
          "version":8,
          "name":"NGOC SI MUSIC MAP",
          "sources":{
            "$BASE_SOURCE":{
              "type":"raster",
              "tiles":["$rasterTiles"],
              "tileSize":256,
              "minzoom":1,
              "maxzoom":19,
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
              "paint":{"raster-opacity":1}
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
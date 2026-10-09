package com.ngocsi.music

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/** Map controls stay independent from the renderer used by the phone. */
interface AppMapController {
    fun centerView(newLat: Double, newLon: Double)
    fun zoomIn()
    fun zoomOut()
    fun fitRoute(routePoints: List<Pair<Double, Double>>)
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
    )
    fun destroy()
}

private class LeafletMapController(
    private val webView: WebView,
    private val onMapReady: (AppMapController) -> Unit,
    private val onUserGesture: () -> Unit,
    private val onSatelliteFallback: () -> Unit
) : AppMapController {
    private var pageReady = false
    private var destroyed = false
    private var latestState = JSONObject()

    private val bridge = object {
        @JavascriptInterface
        fun mapReady() {
            webView.post {
                if (destroyed || pageReady) return@post
                pageReady = true
                onMapReady(this@LeafletMapController)
                pushLatestState()
            }
        }

        @JavascriptInterface
        fun userMovedMap() {
            webView.post { if (!destroyed) onUserGesture() }
        }

        @JavascriptInterface
        fun satelliteUnavailable() {
            webView.post { if (!destroyed) onSatelliteFallback() }
        }

        @JavascriptInterface
        fun mapLibraryUnavailable() = Unit
    }

    init {
        webView.addJavascriptInterface(bridge, "NgocSiNative")
    }

    override fun updateMap(
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
        val safeLat = if (newLat.isFinite()) newLat.coerceIn(-85.0, 85.0) else 10.8231
        val safeLon = if (newLon.isFinite()) newLon.coerceIn(-180.0, 180.0) else 106.6297
        val route = JSONArray()
        routePoints.filter { it.first.isFinite() && it.second.isFinite() }.forEach { point ->
            route.put(JSONArray()
                .put(point.first.coerceIn(-85.0, 85.0))
                .put(point.second.coerceIn(-180.0, 180.0)))
        }
        latestState = JSONObject()
            .put("lat", safeLat)
            .put("lon", safeLon)
            .put("layer", if (selectedLayer == "satellite") "satellite" else "standard")
            .put("route", route)
            .put("currentLocation", isCurrentLocation)
            .put("accuracy", accuracyMeters.coerceAtLeast(0f).toDouble())
            .put("bearing", if (bearingDegrees.isFinite()) bearingDegrees.toDouble() else 0.0)
            .put("hasBearing", hasBearing)
            .put("fitRoute", fitRoute)
            .put("followLocation", followLocation)
        pushLatestState()
    }

    override fun centerView(newLat: Double, newLon: Double) {
        val a = if (newLat.isFinite()) newLat.coerceIn(-85.0, 85.0) else 10.8231
        val b = if (newLon.isFinite()) newLon.coerceIn(-180.0, 180.0) else 106.6297
        evaluate("window.NgocSiMap && window.NgocSiMap.center($a,$b);")
    }

    override fun zoomIn() = evaluate("window.NgocSiMap && window.NgocSiMap.zoomIn();")
    override fun zoomOut() = evaluate("window.NgocSiMap && window.NgocSiMap.zoomOut();")

    override fun fitRoute(routePoints: List<Pair<Double, Double>>) {
        val route = JSONArray()
        routePoints.filter { it.first.isFinite() && it.second.isFinite() }.forEach { point ->
            route.put(JSONArray()
                .put(point.first.coerceIn(-85.0, 85.0))
                .put(point.second.coerceIn(-180.0, 180.0)))
        }
        evaluate("window.NgocSiMap && window.NgocSiMap.fitRoute($route);")
    }

    private fun pushLatestState() {
        if (pageReady && !destroyed) {
            evaluate("window.NgocSiMap && window.NgocSiMap.update($latestState);")
        }
    }

    private fun evaluate(script: String) {
        if (destroyed) return
        webView.post {
            if (!destroyed && pageReady) webView.evaluateJavascript(script, null)
        }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        pageReady = false
        runCatching {
            webView.removeJavascriptInterface("NgocSiNative")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapView(
    modifier: Modifier = Modifier,
    lat: Double,
    lon: Double,
    selectedLayer: String,
    routePoints: List<Pair<Double, Double>>,
    isCurrentLocation: Boolean,
    accuracyMeters: Float,
    bearingDegrees: Float,
    hasBearing: Boolean,
    followLocation: Boolean,
    onUserGesture: () -> Unit,
    onSatelliteFallback: () -> Unit,
    onMapReady: (AppMapController) -> Unit
) {
    val latestOnMapReady = rememberUpdatedState(onMapReady)
    val latestOnUserGesture = rememberUpdatedState(onUserGesture)
    val latestOnSatelliteFallback = rememberUpdatedState(onSatelliteFallback)

    Box(modifier = modifier.background(ComposeColor(0xFFE9EDF1))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(AndroidColor.rgb(233, 237, 241))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " NGOC-SI-MUSIC/5.19"

                    val mapController = LeafletMapController(
                        webView = this,
                        onMapReady = { latestOnMapReady.value(it) },
                        onUserGesture = { latestOnUserGesture.value() },
                        onSatelliteFallback = { latestOnSatelliteFallback.value() }
                    )
                    tag = mapController
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                        }
                    }
                    loadDataWithBaseURL(
                        "https://github.com/ngocsithp-lgtm/NGOC_SI_MUSIC/",
                        buildLeafletMapHtml(lat, lon, selectedLayer),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { view ->
                (view.tag as? LeafletMapController)?.updateMap(
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
            },
            onRelease = { view ->
                (view.tag as? LeafletMapController)?.destroy()
            }
        )

        Text(
            "BẢN ĐỒ TRONG ỨNG DỤNG • © OpenStreetMap",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 9.dp, bottom = 8.dp)
                .background(ComposeColor(0xD90D1118), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            color = ComposeColor.White,
            fontSize = 8.sp
        )
    }

}

private fun buildLeafletMapHtml(lat: Double, lon: Double, selectedLayer: String): String {
    val safeLat = if (lat.isFinite()) lat.coerceIn(-85.0, 85.0) else 10.8231
    val safeLon = if (lon.isFinite()) lon.coerceIn(-180.0, 180.0) else 106.6297
    val safeLayer = if (selectedLayer == "satellite") "satellite" else "standard"
    return """
<!doctype html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta name="referrer" content="origin">
<title>NGỌC SĨ MAP</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
html,body,#map{width:100%;height:100%;margin:0;padding:0;overflow:hidden;background:#e9edf1}
.leaflet-container{background:#e9edf1;font-family:Arial,sans-serif}
.leaflet-control-attribution{font-size:10px!important}
#status{position:absolute;z-index:1200;top:12px;left:12px;right:12px;border-radius:12px;padding:11px 13px;background:rgba(13,17,24,.94);color:#fff;font:13px Arial,sans-serif;box-shadow:0 3px 12px rgba(0,0,0,.18);line-height:1.35}
#status small{display:block;color:#c7cfdb;margin-top:3px;font-size:11px}
</style>
</head>
<body>
<div id="map"></div>
<div id="status">Đang tải bản đồ trong ứng dụng…<small>Đang kết nối dữ liệu bản đồ.</small></div>
<script>
(function(){
  var status=document.getElementById('status');
  var map=null,baseLayer=null,layerName='standard',marker=null,accuracyCircle=null,routeLine=null,startMarker=null,endMarker=null,bearingLine=null;
  var lastRouteKey='',lastLat=${safeLat},lastLon=${safeLon},mapInitialised=false,tileErrors=0;
  var pendingState=null;
  function showStatus(title,detail){
    status.innerHTML='';
    status.appendChild(document.createTextNode(title));
    if(detail){var small=document.createElement('small');small.textContent=detail;status.appendChild(small);}
    status.style.display='block';
  }
  function hideStatus(){status.style.display='none';}
  function validPoint(p){return Array.isArray(p)&&p.length>=2&&isFinite(p[0])&&isFinite(p[1]);}
  function makeBaseLayer(name){
    var layer=name==='satellite'
      ? L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'Tiles © Esri',crossOrigin:true})
      : L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{minZoom:1,maxZoom:19,attribution:'© OpenStreetMap contributors',crossOrigin:true});
    layer.on('tileload',function(){if(layer===baseLayer){tileErrors=0;hideStatus();}});
    layer.on('tileerror',function(){
      if(layer!==baseLayer)return;
      tileErrors++;
      if(tileErrors>=3){
        if(layerName==='satellite'){
          setBaseLayer('standard');
          showStatus('Ảnh vệ tinh tạm thời không tải được','Đã chuyển về bản đồ đường phố.');
          if(window.NgocSiNative)window.NgocSiNative.satelliteUnavailable();
        }else{
          showStatus('Chưa tải được dữ liệu bản đồ','Kiểm tra Internet. Bản đồ vẫn nằm trong ứng dụng.');
        }
      }
    });
    return layer;
  }
  function setBaseLayer(name){
    var next=name==='satellite'?'satellite':'standard';
    if(layerName===next&&baseLayer)return;
    if(baseLayer&&map)map.removeLayer(baseLayer);
    layerName=next;tileErrors=0;baseLayer=makeBaseLayer(layerName);
    if(map)baseLayer.addTo(map);
    showStatus(layerName==='satellite'?'Đang tải ảnh vệ tinh…':'Đang tải bản đồ đường phố…','Đang tải các ô bản đồ trong vùng nhìn thấy.');
  }
  function updatePointLayers(cfg){
    var current=cfg.currentLocation===true,pointColor=current?'#1688ff':'#6c5ce7';
    if(!marker)marker=L.circleMarker([cfg.lat,cfg.lon],{radius:8,color:'#fff',weight:3,fillColor:pointColor,fillOpacity:1}).addTo(map);
    else{marker.setLatLng([cfg.lat,cfg.lon]);marker.setStyle({fillColor:pointColor,radius:current?9:6});}
    if(current&&Number(cfg.accuracy)>0){
      if(!accuracyCircle)accuracyCircle=L.circle([cfg.lat,cfg.lon],{radius:cfg.accuracy,color:'#6c5ce7',weight:1,fillColor:'#6c5ce7',fillOpacity:.14}).addTo(map);
      else{accuracyCircle.setLatLng([cfg.lat,cfg.lon]);accuracyCircle.setRadius(Math.max(1,Math.min(5000,Number(cfg.accuracy))));}
    }else if(accuracyCircle){map.removeLayer(accuracyCircle);accuracyCircle=null;}
    if(current&&cfg.hasBearing){
      var rad=Number(cfg.bearing||0)*Math.PI/180;
      var latOffset=Math.cos(rad)*.00035;
      var lonOffset=Math.sin(rad)*.00035/Math.max(.1,Math.cos(cfg.lat*Math.PI/180));
      var end=[cfg.lat+latOffset,cfg.lon+lonOffset];
      if(!bearingLine)bearingLine=L.polyline([[cfg.lat,cfg.lon],end],{color:'#1688ff',weight:4,opacity:.9}).addTo(map);
      else bearingLine.setLatLngs([[cfg.lat,cfg.lon],end]);
    }else if(bearingLine){map.removeLayer(bearingLine);bearingLine=null;}
  }
  function applyState(cfg){
    if(!map||!cfg){pendingState=cfg;return;}
    var requested=cfg.layer==='satellite'?'satellite':'standard';
    if(requested!==layerName)setBaseLayer(requested);
    var a=Number(cfg.lat),b=Number(cfg.lon);
    if(!isFinite(a)||!isFinite(b)){a=${safeLat};b=${safeLon};}
    updatePointLayers({lat:a,lon:b,currentLocation:cfg.currentLocation,accuracy:cfg.accuracy,bearing:cfg.bearing,hasBearing:cfg.hasBearing});
    var route=Array.isArray(cfg.route)?cfg.route.filter(validPoint):[];
    var routeKey=JSON.stringify(route);
    if(!routeLine){routeLine=L.polyline([],{color:'#fff',weight:9,opacity:.9,lineCap:'round',lineJoin:'round'}).addTo(map);routeLine.topLine=L.polyline([],{color:'#6c5ce7',weight:5,opacity:1,lineCap:'round',lineJoin:'round'}).addTo(map);}
    routeLine.setLatLngs(route);routeLine.topLine.setLatLngs(route);
    if(route.length>=2){
      if(!startMarker)startMarker=L.circleMarker(route[0],{radius:7,color:'#fff',weight:2,fillColor:'#22a06b',fillOpacity:1}).addTo(map);else startMarker.setLatLng(route[0]);
      if(!endMarker)endMarker=L.circleMarker(route[route.length-1],{radius:8,color:'#fff',weight:2,fillColor:'#ed5264',fillOpacity:1}).addTo(map);else endMarker.setLatLng(route[route.length-1]);
      if(cfg.fitRoute&&routeKey!==lastRouteKey)map.fitBounds(routeLine.getBounds().pad(.16),{animate:false,maxZoom:16});
    }else{
      if(startMarker){map.removeLayer(startMarker);startMarker=null;}
      if(endMarker){map.removeLayer(endMarker);endMarker=null;}
    }
    if(cfg.followLocation&&cfg.currentLocation&&(Math.abs(a-lastLat)>.00001||Math.abs(b-lastLon)>.00001))map.setView([a,b],Math.max(map.getZoom(),16),{animate:false});
    else if(!mapInitialised){map.setView([a,b],14,{animate:false});mapInitialised=true;}
    lastLat=a;lastLon=b;lastRouteKey=routeKey;
  }
  function init(){
    if(!window.L||map)return;
    try{
      map=L.map('map',{zoomControl:true,attributionControl:true,preferCanvas:true}).setView([${safeLat},${safeLon}],14);
      layerName='${safeLayer}';baseLayer=makeBaseLayer(layerName).addTo(map);
      map.on('dragstart',function(){if(window.NgocSiNative)window.NgocSiNative.userMovedMap();});
      routeLine=L.polyline([],{color:'#fff',weight:9,opacity:.9,lineCap:'round',lineJoin:'round'}).addTo(map);
      routeLine.topLine=L.polyline([],{color:'#6c5ce7',weight:5,opacity:1,lineCap:'round',lineJoin:'round'}).addTo(map);
      mapInitialised=true;
      if(window.NgocSiNative)window.NgocSiNative.mapReady();
      if(pendingState)applyState(pendingState);
    }catch(e){showStatus('Không khởi tạo được bản đồ','Hãy đóng bản đồ rồi mở lại. '+String(e).slice(0,100));}
  }
  function loadScript(urls,index){
    if(index>=urls.length){showStatus('Không tải được thư viện bản đồ','Kiểm tra Internet hoặc bộ lọc nội dung trên thiết bị.');if(window.NgocSiNative)window.NgocSiNative.mapLibraryUnavailable();return;}
    var script=document.createElement('script');script.src=urls[index];script.onload=init;script.onerror=function(){loadScript(urls,index+1);};document.head.appendChild(script);
  }
  window.NgocSiMap={
    update:applyState,
    center:function(a,b){if(map)map.setView([a,b],Math.max(map.getZoom(),14),{animate:true});},
    zoomIn:function(){if(map)map.zoomIn();},
    zoomOut:function(){if(map)map.zoomOut();},
    fitRoute:function(points){if(!map||!Array.isArray(points))return;var valid=points.filter(validPoint);if(valid.length>=2)map.fitBounds(L.latLngBounds(valid).pad(.16),{animate:true,maxZoom:16});}
  };
  loadScript([
    'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
    'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js'
  ],0);
})();
</script>
</body>
</html>
""".trimIndent()
}

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    private val onSatelliteFallback: () -> Unit,
    private val onMapStatus: (String?) -> Unit
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
        fun reportMapStatus(message: String) {
            webView.post {
                if (!destroyed) onMapStatus(if (message == "OK" || message.isBlank()) null else message.take(180))
            }
        }
        @JavascriptInterface
        fun userMovedMap() { webView.post { if (!destroyed) onUserGesture() } }
        @JavascriptInterface
        fun satelliteUnavailable() {
            webView.post {
                if (!destroyed) {
                    onSatelliteFallback()
                    onMapStatus("Ảnh vệ tinh không tải được; đã chuyển sang bản đồ đường phố.")
                }
            }
        }
        @JavascriptInterface
        fun mapLibraryUnavailable() {
            webView.post { if (!destroyed) onMapStatus("Bộ hiển thị bản đồ không khởi tạo được.") }
        }
    }

    init { webView.addJavascriptInterface(bridge, "NgocSiNative") }

    fun reportNativeStatus(message: String) { if (!destroyed) onMapStatus(message.take(180)) }

    override fun updateMap(
        newLat: Double, newLon: Double, selectedLayer: String,
        routePoints: List<Pair<Double, Double>>, isCurrentLocation: Boolean,
        accuracyMeters: Float, bearingDegrees: Float, hasBearing: Boolean,
        fitRoute: Boolean, followLocation: Boolean
    ) {
        val safeLat = if (newLat.isFinite()) newLat.coerceIn(-85.0, 85.0) else 10.8231
        val safeLon = if (newLon.isFinite()) newLon.coerceIn(-180.0, 180.0) else 106.6297
        val route = JSONArray()
        routePoints.filter { it.first.isFinite() && it.second.isFinite() }.forEach { p ->
            route.put(JSONArray().put(p.first.coerceIn(-85.0, 85.0)).put(p.second.coerceIn(-180.0, 180.0)))
        }
        latestState = JSONObject()
            .put("lat", safeLat).put("lon", safeLon)
            .put("layer", if (selectedLayer == "satellite") "satellite" else "standard")
            .put("route", route).put("currentLocation", isCurrentLocation)
            .put("accuracy", accuracyMeters.coerceAtLeast(0f).toDouble())
            .put("bearing", if (bearingDegrees.isFinite()) bearingDegrees.toDouble() else 0.0)
            .put("hasBearing", hasBearing).put("fitRoute", fitRoute).put("followLocation", followLocation)
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
        routePoints.filter { it.first.isFinite() && it.second.isFinite() }.forEach { p ->
            route.put(JSONArray().put(p.first.coerceIn(-85.0, 85.0)).put(p.second.coerceIn(-180.0, 180.0)))
        }
        evaluate("window.NgocSiMap && window.NgocSiMap.fitRoute($route);")
    }
    private fun pushLatestState() {
        if (pageReady && !destroyed) evaluate("window.NgocSiMap && window.NgocSiMap.update($latestState);")
    }
    private fun evaluate(script: String) {
        if (destroyed) return
        webView.post { if (!destroyed && pageReady) webView.evaluateJavascript(script, null) }
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
    modifier: Modifier = Modifier, lat: Double, lon: Double, selectedLayer: String,
    routePoints: List<Pair<Double, Double>>, isCurrentLocation: Boolean, accuracyMeters: Float,
    bearingDegrees: Float, hasBearing: Boolean, followLocation: Boolean,
    onUserGesture: () -> Unit, onSatelliteFallback: () -> Unit,
    onMapReady: (AppMapController) -> Unit
) {
    val latestOnMapReady = rememberUpdatedState(onMapReady)
    val latestOnUserGesture = rememberUpdatedState(onUserGesture)
    val latestOnSatelliteFallback = rememberUpdatedState(onSatelliteFallback)
    var mapStatus by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>("Đang khởi tạo bản đồ trong ứng dụng…")
    }
    Box(modifier = modifier.background(ComposeColor(0xFFE9EDF1))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(AndroidColor.rgb(233, 237, 241))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " NGOC-SI-MUSIC/5.20"
                    val controller = LeafletMapController(
                        webView = this,
                        onMapReady = { latestOnMapReady.value(it) },
                        onUserGesture = { latestOnUserGesture.value() },
                        onSatelliteFallback = { latestOnSatelliteFallback.value() },
                        onMapStatus = { mapStatus = it }
                    )
                    tag = controller
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                controller.reportNativeStatus("Lỗi hiển thị bản đồ: " + message.message().take(120))
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("if(window.NgocSiMap){'ready'}else{'script-not-ready'}") { result ->
                                if (result?.contains("script-not-ready") == true) {
                                    controller.reportNativeStatus("Trang mở nhưng bộ vẽ bản đồ chưa khởi tạo.")
                                }
                            }
                        }
                        override fun onReceivedError(
                            view: WebView?, request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                controller.reportNativeStatus("WebView không tải được bản đồ: " + (error?.description?.toString() ?: "lỗi mạng"))
                            }
                        }
                        override fun onReceivedHttpError(
                            view: WebView?, request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame == true) {
                                controller.reportNativeStatus("Máy chủ trả về HTTP " + (errorResponse?.statusCode?.toString() ?: "lỗi"))
                            }
                        }
                    }
                    loadDataWithBaseURL(
                        "https://ngocsi-map.local/",
                        buildLeafletMapHtml(lat, lon, selectedLayer),
                        "text/html", "UTF-8", null
                    )
                }
            },
            update = { view ->
                (view.tag as? LeafletMapController)?.updateMap(
                    newLat = lat, newLon = lon, selectedLayer = selectedLayer,
                    routePoints = routePoints, isCurrentLocation = isCurrentLocation,
                    accuracyMeters = accuracyMeters, bearingDegrees = bearingDegrees,
                    hasBearing = hasBearing, fitRoute = routePoints.size >= 2,
                    followLocation = followLocation
                )
            },
            onRelease = { view -> (view.tag as? LeafletMapController)?.destroy() }
        )
        mapStatus?.let { message ->
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                    .background(ComposeColor(0xF20D1118), RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp)
            ) { Text(message, color = ComposeColor.White, fontSize = 11.sp) }
        }
        Text(
            "BẢN ĐỒ TÍCH HỢP • © OpenStreetMap · © CARTO",
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 8.dp)
                .background(ComposeColor(0xD90D1118), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            color = ComposeColor.White, fontSize = 8.sp
        )
    }
}

private fun buildLeafletMapHtml(lat: Double, lon: Double, selectedLayer: String): String {
    val safeLat = if (lat.isFinite()) lat.coerceIn(-85.0, 85.0) else 10.8231
    val safeLon = if (lon.isFinite()) lon.coerceIn(-180.0, 180.0) else 106.6297
    val safeLayer = if (selectedLayer == "satellite") "satellite" else "standard"
    return """
<!doctype html><html lang="vi"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta name="referrer" content="no-referrer"><title>NGỌC SĨ MAP</title>
<style>
html,body,#map{width:100%;height:100%;margin:0;padding:0;overflow:hidden;background:#e9edf1;touch-action:none}
body{font-family:Arial,sans-serif}#map{position:absolute;inset:0}
#mapCanvas{width:100%;height:100%;display:block;background:#e9edf1;touch-action:none}
#mapControls{position:absolute;right:12px;top:12px;z-index:20;display:flex;flex-direction:column;gap:7px}
.mapButton{border:1px solid #cfd4de;border-radius:12px;background:rgba(255,255,255,.96);color:#222936;font:bold 17px Arial;padding:9px 13px;box-shadow:0 2px 7px #0002}
#tileHint{position:absolute;left:10px;bottom:38px;max-width:80%;font:10px Arial;color:white;background:#111d;padding:5px 8px;border-radius:7px}
</style></head><body><div id="map">
<canvas id="mapCanvas" aria-label="Bản đồ tương tác"></canvas>
<div id="mapControls"><button class="mapButton" id="zoomIn">＋</button><button class="mapButton" id="zoomOut">－</button><button class="mapButton" id="centerMap">⌖</button></div>
<div id="tileHint">© OpenStreetMap contributors · © CARTO</div></div>
<script>
(function(){
'use strict';
var canvas=document.getElementById('mapCanvas'),ctx=canvas.getContext('2d',{alpha:false});
var layerName='${safeLayer}',centerLat=${safeLat},centerLon=${safeLon},zoom=14;
var width=0,height=0,dpr=1,mapReady=false,hasFirstTile=false,failedTiles=0,renderPending=false;
var tileCache=Object.create(null),state={lat:${safeLat},lon:${safeLon},layer:'${safeLayer}',route:[],currentLocation:false,accuracy:0,bearing:0,hasBearing:false,fitRoute:false,followLocation:false};
var route=[],lastRouteKey='';
var standardServers=['https://tile.openstreetmap.org/{z}/{x}/{y}.png','https://tile.openstreetmap.de/{z}/{x}/{y}.png','https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png','https://b.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'];
var satelliteServers=['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'];
function bridgeStatus(m){try{if(window.NgocSiNative)window.NgocSiNative.reportMapStatus(m);}catch(e){}}
function clamp(v,a,b){return Math.max(a,Math.min(b,v));}
function mod(v,n){return ((v%n)+n)%n;}
function project(lat,lon,z){
 var size=256*Math.pow(2,z),safeLat=clamp(lat,-85.05112878,85.05112878),s=Math.sin(safeLat*Math.PI/180);
 return{x:(lon+180)/360*size,y:(.5-Math.log((1+s)/(1-s))/(4*Math.PI))*size};
}
function unproject(x,y,z){
 var size=256*Math.pow(2,z),lon=x/size*360-180,n=Math.PI-2*Math.PI*y/size;
 return{lat:clamp(180/Math.PI*Math.atan(.5*(Math.exp(n)-Math.exp(-n))),-85,85),lon:clamp(lon,-180,180)};
}
function urlFor(layer,z,x,y,index){
 var list=layer==='satellite'?satelliteServers:standardServers;
 var host=(list[index]||list[0]);
 return host.replace('{z}',z).replace('{x}',x).replace('{y}',y);
}
function requestTile(z,x,y){
 var n=Math.pow(2,z),wx=mod(x,n);if(y<0||y>=n)return null;
 var key=layerName+'/'+z+'/'+wx+'/'+y;if(tileCache[key])return tileCache[key];
 var e={status:'loading',image:null,index:0,layer:layerName};tileCache[key]=e;loadTile(e,z,wx,y);return e;
}
function loadTile(e,z,x,y){
 var img=new Image();e.image=img;
 img.onload=function(){e.status='loaded';if(!hasFirstTile){hasFirstTile=true;bridgeStatus('OK');}queueDraw();};
 img.onerror=function(){
  var servers=e.layer==='satellite'?satelliteServers:standardServers;
  if(e.index+1<servers.length){e.index++;img.src=urlFor(e.layer,z,x,y,e.index);return;}
  e.status='error';failedTiles++;
  if(!hasFirstTile&&failedTiles>=Math.min(3,standardServers.length)){
   if(layerName==='satellite'){bridgeStatus('Không tải được ảnh vệ tinh; đang chuyển về bản đồ đường phố…');if(window.NgocSiNative)window.NgocSiNative.satelliteUnavailable();}
   else bridgeStatus('Không tải được dữ liệu bản đồ. Kiểm tra Internet hoặc DNS rồi mở lại BẢN ĐỒ.');
  }
  queueDraw();
 };
 img.src=urlFor(e.layer,z,x,y,e.index);
}
function drawTile(z,x,y,left,top){
 var n=Math.pow(2,z);if(y<0||y>=n)return;
 var e=requestTile(z,x,y);
 if(e&&e.status==='loaded'&&e.image&&e.image.naturalWidth>0){try{ctx.drawImage(e.image,left,top,256,256);}catch(err){}}
 else{ctx.fillStyle='#e9edf1';ctx.fillRect(left,top,256,256);ctx.strokeStyle='#dce2e8';ctx.lineWidth=1;ctx.strokeRect(left+.5,top+.5,255,255);}
}
function drawOverlay(){
 var cp=project(centerLat,centerLon,zoom),n=Math.pow(2,zoom);
 function screen(p){var q=project(p[0],p[1],zoom),dx=q.x-cp.x;if(dx>128*n)dx-=256*n;if(dx< -128*n)dx+=256*n;return{x:width/2+dx,y:height/2+q.y-cp.y};}
 if(route.length>=2){
  ctx.beginPath();route.forEach(function(p,i){var q=screen(p);if(i===0)ctx.moveTo(q.x,q.y);else ctx.lineTo(q.x,q.y);});
  ctx.lineCap='round';ctx.lineJoin='round';ctx.lineWidth=9;ctx.strokeStyle='#fff';ctx.stroke();ctx.lineWidth=5;ctx.strokeStyle='#6c5ce7';ctx.stroke();
  [{p:route[0],c:'#22a06b'},{p:route[route.length-1],c:'#ed5264'}].forEach(function(v){var q=screen(v.p);ctx.beginPath();ctx.arc(q.x,q.y,7,0,Math.PI*2);ctx.fillStyle=v.c;ctx.fill();ctx.lineWidth=2;ctx.strokeStyle='#fff';ctx.stroke();});
 }
 var m=screen([state.lat,state.lon]);
 if(state.currentLocation&&Number(state.accuracy)>0){
  var mpp=156543.03392*Math.cos(state.lat*Math.PI/180)/Math.pow(2,zoom),rad=clamp(Number(state.accuracy)/Math.max(mpp,.01),2,Math.max(width,height));
  ctx.beginPath();ctx.arc(m.x,m.y,rad,0,Math.PI*2);ctx.fillStyle='#6c5ce728';ctx.fill();ctx.strokeStyle='#6c5ce7aa';ctx.lineWidth=1;ctx.stroke();
 }
 ctx.beginPath();ctx.arc(m.x,m.y,state.currentLocation?9:6,0,Math.PI*2);ctx.fillStyle=state.currentLocation?'#1688ff':'#6c5ce7';ctx.fill();ctx.lineWidth=3;ctx.strokeStyle='#fff';ctx.stroke();
 if(state.currentLocation&&state.hasBearing){var a=Number(state.bearing||0)*Math.PI/180;ctx.beginPath();ctx.moveTo(m.x,m.y);ctx.lineTo(m.x+Math.sin(a)*28,m.y-Math.cos(a)*28);ctx.strokeStyle='#1688ff';ctx.lineWidth=4;ctx.stroke();}
}
function draw(){
 renderPending=false;if(!ctx){bridgeStatus('Không tạo được vùng vẽ bản đồ trên WebView.');return;}
 var rect=canvas.getBoundingClientRect();if(rect.width<2||rect.height<2){bridgeStatus('Vùng hiển thị bản đồ chưa có kích thước.');return;}
 width=rect.width;height=rect.height;dpr=Math.min(window.devicePixelRatio||1,2);
 var tw=Math.max(1,Math.round(width*dpr)),th=Math.max(1,Math.round(height*dpr));if(canvas.width!==tw||canvas.height!==th){canvas.width=tw;canvas.height=th;}
 ctx.setTransform(dpr,0,0,dpr,0,0);ctx.fillStyle='#e9edf1';ctx.fillRect(0,0,width,height);
 var cp=project(centerLat,centerLon,zoom),fx=Math.floor((cp.x-width/2)/256),lx=Math.floor((cp.x+width/2)/256),fy=Math.floor((cp.y-height/2)/256),ly=Math.floor((cp.y+height/2)/256);
 for(var y=fy;y<=ly;y++)for(var x=fx;x<=lx;x++)drawTile(zoom,x,y,x*256-(cp.x-width/2),y*256-(cp.y-height/2));
 drawOverlay();
}
function queueDraw(){if(renderPending)return;renderPending=true;requestAnimationFrame(draw);}
// AndroidView can finish loading before Compose assigns its final size. Observe
// the actual map container as well as the browser window so a zero-size first
// frame cannot leave the map permanently blank.
var mapElement=document.getElementById('map');
if(window.ResizeObserver){var mapResizeObserver=new ResizeObserver(function(){queueDraw();});mapResizeObserver.observe(mapElement);mapResizeObserver.observe(canvas);}
window.addEventListener('load',function(){queueDraw();requestAnimationFrame(queueDraw);});
requestAnimationFrame(function(){queueDraw();requestAnimationFrame(queueDraw);});
function setCenter(a,b){centerLat=clamp(Number(a)||10.8231,-85,85);centerLon=clamp(Number(b)||106.6297,-180,180);queueDraw();}
function changeZoom(d){zoom=clamp(zoom+d,2,19);queueDraw();}
function fitRoute(points){
 var valid=Array.isArray(points)?points.filter(function(p){return Array.isArray(p)&&p.length>=2&&isFinite(p[0])&&isFinite(p[1]);}):[];if(valid.length<2)return;
 route=valid;var chosen=10,cx=0,cy=0;
 for(var z=18;z>=2;z--){var xs=[],ys=[];valid.forEach(function(p){var v=project(p[0],p[1],z);xs.push(v.x);ys.push(v.y);});
  var minX=Math.min.apply(null,xs),maxX=Math.max.apply(null,xs),minY=Math.min.apply(null,ys),maxY=Math.max.apply(null,ys);
  if(maxX-minX<width*.72&&maxY-minY<height*.58){chosen=z;cx=(minX+maxX)/2;cy=(minY+maxY)/2;break;}
 }
 zoom=chosen;var c=unproject(cx,cy,zoom);centerLat=c.lat;centerLon=c.lon;queueDraw();
}
function update(cfg){
 if(!cfg)return;var oldLayer=layerName;layerName=cfg.layer==='satellite'?'satellite':'standard';state=cfg;
 state.lat=isFinite(Number(cfg.lat))?clamp(Number(cfg.lat),-85,85):${safeLat};
 state.lon=isFinite(Number(cfg.lon))?clamp(Number(cfg.lon),-180,180):${safeLon};
 route=Array.isArray(cfg.route)?cfg.route.filter(function(p){return Array.isArray(p)&&p.length>=2&&isFinite(p[0])&&isFinite(p[1]);}):[];
 if(oldLayer!==layerName){hasFirstTile=false;failedTiles=0;}
 var key=JSON.stringify(route);
 if(route.length>=2&&cfg.fitRoute&&key!==lastRouteKey)fitRoute(route);
 else if(cfg.followLocation&&cfg.currentLocation){centerLat=state.lat;centerLon=state.lon;zoom=Math.max(zoom,16);}
 else if(!mapReady){centerLat=state.lat;centerLon=state.lon;zoom=14;}
 mapReady=true;lastRouteKey=key;queueDraw();
}
var drag=null,pinchDistance=0,pinchZoom=zoom;
canvas.addEventListener('touchstart',function(e){if(e.touches.length===1)drag={x:e.touches[0].clientX,y:e.touches[0].clientY};if(e.touches.length>=2){drag=null;var a=e.touches[0],b=e.touches[1];pinchDistance=Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);pinchZoom=zoom;}},{passive:false});
canvas.addEventListener('touchmove',function(e){
 e.preventDefault();
 if(e.touches.length>=2){var a=e.touches[0],b=e.touches[1],d=Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);if(pinchDistance>0&&Math.abs(d-pinchDistance)>35){zoom=clamp(pinchZoom+(d>pinchDistance?1:-1),2,19);pinchZoom=zoom;pinchDistance=d;queueDraw();}return;}
 if(e.touches.length===1&&drag){var t=e.touches[0],dx=t.clientX-drag.x,dy=t.clientY-drag.y,cp=project(centerLat,centerLon,zoom),c=unproject(cp.x-dx,cp.y-dy,zoom);centerLat=c.lat;centerLon=c.lon;drag={x:t.clientX,y:t.clientY};queueDraw();}
},{passive:false});
canvas.addEventListener('touchend',function(){drag=null;pinchDistance=0;if(window.NgocSiNative)window.NgocSiNative.userMovedMap();},{passive:true});
canvas.addEventListener('wheel',function(e){e.preventDefault();changeZoom(e.deltaY<0?1:-1);},{passive:false});
document.getElementById('zoomIn').addEventListener('click',function(){changeZoom(1);});
document.getElementById('zoomOut').addEventListener('click',function(){changeZoom(-1);});
document.getElementById('centerMap').addEventListener('click',function(){setCenter(state.lat,state.lon);});
window.addEventListener('resize',queueDraw);
window.NgocSiMap={update:update,center:setCenter,zoomIn:function(){changeZoom(1);},zoomOut:function(){changeZoom(-1);},fitRoute:fitRoute};
try{if(window.NgocSiNative)window.NgocSiNative.mapReady();bridgeStatus('Đang tải dữ liệu bản đồ…');update(state);}
catch(e){bridgeStatus('Không khởi tạo được bản đồ: '+String(e).slice(0,120));}
queueDraw();
})();
</script></body></html>
""".trimIndent()
}

package com.torve.android.ui.replays

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Official IFrame API, with the installed app identity as both origin and HTTP
 * Referer. No video extraction, custom media transport or player overlays. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ReplayYouTubeEmbed(videoId: String, modifier: Modifier = Modifier,
    onReady: () -> Unit, onState: (Int) -> Unit, onError: (Int) -> Unit,
    onViewReady: (WebView) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(onReady)
    val state by rememberUpdatedState(onState)
    val error by rememberUpdatedState(onError)
    val web = remember(videoId) { WebView(context) }
    val active = remember(web) { AtomicBoolean(true) }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                web.evaluateJavascript("if(window.player){window.player.pauseVideo();}", null)
                web.onPause()
            }
            if (event == Lifecycle.Event.ON_RESUME) web.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose {
            active.set(false)
            lifecycle.removeObserver(observer)
            web.stopLoading(); web.loadUrl("about:blank")
            web.removeJavascriptInterface("TorveYouTube")
            web.removeAllViews(); web.destroy()
        }
    }
    AndroidView(factory = {
        web.apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(ReplayYouTubeBridge(active, { ready() }, { state(it) }, { error(it) }), "TorveYouTube")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame || request.url.scheme != "https"
                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame && active.get()) error(-2)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (active.get()) error(-3)
                    return true
                }
            }
            if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) error(2)
            else {
                val origin = "https://${context.packageName.lowercase()}"
                onViewReady(this)
                loadDataWithBaseURL("$origin/", replayYouTubeHtml(videoId, origin), "text/html", "utf-8", null)
            }
        }
    }, modifier = modifier)
}

private class ReplayYouTubeBridge(private val active: AtomicBoolean,
    private val readyCallback: () -> Unit, private val stateCallback: (Int) -> Unit,
    private val errorCallback: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun ready() { main.post { if (active.get()) readyCallback() } }
    @JavascriptInterface fun state(value: Int) { main.post { if (active.get()) stateCallback(value) } }
    @JavascriptInterface fun error(value: Int) { main.post { if (active.get()) errorCallback(value) } }
}

internal fun replayYouTubeHtml(videoId: String, origin: String) = """
<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
html,body{position:fixed;inset:0;width:100vw;height:100vh;margin:0;padding:0;background:#000;overflow:hidden}
#player,iframe{position:absolute;inset:0;display:block;width:100vw!important;height:100vh!important;border:0}
</style>
</head><body><div id="player"></div><script>
var player;
function fitPlayer(){
  var w=window.innerWidth,h=window.innerHeight;
  if(w<1||h<1)return;
  var f=player&&player.getIframe?player.getIframe():document.getElementById('player');
  [document.documentElement,document.body,f].forEach(function(e){
    if(e){e.style.setProperty('height',h+'px','important');e.style.setProperty('width',w+'px','important');}
  });
  if(player&&player.setSize)player.setSize(w,h);
}
window.addEventListener('resize',fitPlayer);
function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
width:window.innerWidth,height:window.innerHeight,videoId:'$videoId',
playerVars:{origin:'$origin',controls:1,playsinline:1,autoplay:1,rel:0,fs:0},
events:{onReady:function(e){fitPlayer();TorveYouTube.ready();e.target.loadVideoById('$videoId',0);},
onStateChange:function(e){fitPlayer();TorveYouTube.state(Number(e.data));},
onError:function(e){TorveYouTube.error(Number(e.data));}}
});}
</script><script defer src="https://www.youtube.com/iframe_api"></script></body></html>
""".trimIndent()

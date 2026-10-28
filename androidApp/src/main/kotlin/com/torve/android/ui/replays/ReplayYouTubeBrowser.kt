package com.torve.android.ui.replays

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torve.android.remote.TorveRemoteDialog
import kotlinx.coroutines.delay

/** A visible, ordinary YouTube website. Navigation never launches a different app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun ReplayYouTubeBrowser(url: String,
    onViewReady: (WebView) -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    val web = remember(url) { WebView(context) }
    var fullScreenView by remember(web) { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember(web) { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) web.onPause()
            if (event == Lifecycle.Event.ON_RESUME) web.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            fullScreenCallback?.onCustomViewHidden()
            web.stopLoading(); web.loadUrl("about:blank"); web.removeAllViews(); web.destroy()
        }
    }
    LaunchedEffect(url) {
        delay(15_000)
        if (!loaded) { web.stopLoading(); web.loadUrl("about:blank"); failed = true }
    }
    // Opening a watch page is an explicit Play choice. Its own visible Play control
    // can appear after page-finished; activate it without replacing YouTube's player.
    LaunchedEffect(url, loaded) {
        if (loaded && Uri.parse(url).path == "/watch") {
            web.requestFocus()
            var started = false
            repeat(12) {
                delay(500)
                if (!started) web.evaluateJavascript("""
                    (function(){
                      var v=document.querySelector('video');
                      if(v&&!v.paused)return 'playing';
                      var b=document.querySelector('.ytp-large-play-button');
                      if(b&&b.getBoundingClientRect().width>0){b.focus();b.click();}
                      else if(v){var p=v.play();if(p&&p.catch)p.catch(function(){});}
                      return 'requested';
                    })()
                """.trimIndent()) { started = it == "\"playing\"" }
            }
        }
    }
    TorveRemoteDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReplayAction(localizedUiText("Back to highlights"), Modifier.focusRequester(closeFocus).testTag("highlights.browser.close"), onClick = onDismiss)
            if (failed) {
                Text(localizedUiText("YouTube isn’t responding. Return to highlights and try another video."), color = Color.White)
            } else Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = {
                web.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // This page is entered by an explicit Play action.
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url
                            val host = target.host.orEmpty().lowercase()
                            return target.scheme != "https" || !(host == "youtube.com" || host.endsWith(".youtube.com") ||
                                host == "youtu.be" || host == "consent.google.com" || host == "accounts.google.com")
                        }
                        override fun onPageFinished(view: WebView, url: String) { loaded = true }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failed = true
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { failed = true; return true }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            fullScreenView = view; fullScreenCallback = callback
                        }
                        override fun onHideCustomView() {
                            fullScreenView = null
                            val callback = fullScreenCallback; fullScreenCallback = null
                            callback?.onCustomViewHidden()
                        }
                    }
                    onViewReady(this)
                    val target = if (Uri.parse(url).path == "/watch") Uri.parse(url).buildUpon()
                        .appendQueryParameter("autoplay", "1").appendQueryParameter("playsinline", "1").build().toString() else url
                    loadUrl(target)
                }
                }, modifier = Modifier.fillMaxSize().testTag("highlights.browser"))
                fullScreenView?.let { view ->
                    AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; closeFocus.requestFocus() }
    }
}

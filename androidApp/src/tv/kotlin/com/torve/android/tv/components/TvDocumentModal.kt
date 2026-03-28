package com.torve.android.tv.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface TvDocumentContentState {
    data object Loading : TvDocumentContentState

    data class Ready(
        val html: String,
        val source: TvDocumentSource,
        val warningMessage: String? = null,
    ) : TvDocumentContentState

    data class Error(
        val message: String,
    ) : TvDocumentContentState
}

enum class TvDocumentSource {
    REMOTE,
    ASSET_FALLBACK,
}

@Composable
fun rememberTvDocumentContentState(
    url: String,
    fallbackAssetName: String,
): TvDocumentContentState {
    val context = androidx.compose.ui.platform.LocalContext.current
    return produceState<TvDocumentContentState>(
        initialValue = TvDocumentContentState.Loading,
        key1 = url,
        key2 = fallbackAssetName,
    ) {
        loadDocumentContent(
            context = context,
            url = url,
            fallbackAssetName = fallbackAssetName,
        )
    }.value
}

@Composable
fun TvDocumentModal(
    title: String,
    initialFocusRequester: FocusRequester,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    LaunchedEffect(initialFocusRequester) {
        withFrameNanos { }
        runCatching { initialFocusRequester.requestFocus() }
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian.copy(alpha = 0.92f))
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Back || event.key == Key.Escape) -> {
                            onDismiss()
                            true
                        }
                        event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Back || event.key == Key.Escape) -> true
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Charcoal.copy(alpha = 0.98f))
                    .border(2.dp, Steel.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.tv_settings_document_back_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Obsidian.copy(alpha = 0.45f))
                        .border(1.dp, Steel.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun TvHtmlDocumentPane(
    html: String,
    baseUrl: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Amber else Steel.copy(alpha = 0.32f)
    val backgroundColor = if (focused) GraphiteSurface else Color.Transparent

    AndroidView(
        factory = { context ->
            TvDocumentWebView(context).apply {
                loadDocument(html = html, baseUrl = baseUrl)
            }
        },
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .border(2.dp, borderColor, RoundedCornerShape(18.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        update = { view ->
            view.loadDocument(html = html, baseUrl = baseUrl)
        },
        onRelease = { view ->
            view.destroy()
        },
    )
}

private suspend fun ProduceStateScope<TvDocumentContentState>.loadDocumentContent(
    context: Context,
    url: String,
    fallbackAssetName: String,
) {
    value = TvDocumentContentState.Loading
    value = withContext(Dispatchers.IO) {
        val remote = runCatching { fetchRemoteHtml(url) }
        if (remote.isSuccess) {
            TvDocumentContentState.Ready(
                html = normalizeDocumentHtml(remote.getOrThrow()),
                source = TvDocumentSource.REMOTE,
            )
        } else {
            val fallback = runCatching { context.assets.open(fallbackAssetName).bufferedReader().use { it.readText() } }
            if (fallback.isSuccess) {
                TvDocumentContentState.Ready(
                    html = normalizeDocumentHtml(fallback.getOrThrow()),
                    source = TvDocumentSource.ASSET_FALLBACK,
                    warningMessage = remote.exceptionOrNull()?.message,
                )
            } else {
                TvDocumentContentState.Error(
                    message = remote.exceptionOrNull()?.message
                        ?: fallback.exceptionOrNull()?.message
                        ?: "Unable to load document.",
                )
            }
        }
    }
}

private fun fetchRemoteHtml(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        requestMethod = "GET"
        setRequestProperty("Accept", "text/html")
    }
    return connection.useAndRead()
}

private fun HttpURLConnection.useAndRead(): String {
    try {
        val code = responseCode
        if (code !in 200..299) {
            throw IOException("HTTP $code")
        }
        return inputStream.bufferedReader().use { it.readText() }
    } finally {
        disconnect()
    }
}

private fun normalizeDocumentHtml(rawHtml: String): String {
    val bodyContent = extractTagContents(rawHtml, "body") ?: rawHtml
    val mainContent = extractTagContents(bodyContent, "main")
        ?: extractTagContents(bodyContent, "article")
        ?: bodyContent
    val cleaned = stripSiteChrome(mainContent).ifBlank { stripSiteChrome(bodyContent) }
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <style>
            body {
              margin: 0;
              padding: 24px;
              background: transparent;
              color: #E5E7EB;
              font-family: sans-serif;
              line-height: 1.65;
              font-size: 20px;
            }
            h1, h2, h3, h4 {
              color: #F8FAFC;
              line-height: 1.25;
              margin-top: 1.2em;
              margin-bottom: 0.5em;
            }
            h1 {
              font-size: 2.1em;
              margin-top: 0;
            }
            h2 {
              font-size: 1.45em;
            }
            p, li {
              color: #CBD5E1;
            }
            ul, ol {
              padding-left: 1.4em;
            }
            a {
              color: #60A5FA;
            }
            hr, nav, header, footer {
              display: none !important;
            }
          </style>
        </head>
        <body>
          $cleaned
        </body>
        </html>
    """.trimIndent()
}

private fun extractTagContents(
    html: String,
    tagName: String,
): String? {
    val pattern = Regex("(?is)<$tagName\\b[^>]*>(.*)</$tagName>")
    return pattern.find(html)?.groupValues?.getOrNull(1)
}

private fun stripSiteChrome(html: String): String {
    var sanitized = html
    val structuralPatterns = listOf(
        Regex("(?is)<header\\b[^>]*>.*?</header>"),
        Regex("(?is)<nav\\b[^>]*>.*?</nav>"),
        Regex("(?is)<footer\\b[^>]*>.*?</footer>"),
        Regex("(?is)<button\\b[^>]*>\\s*Get\\s+Torve\\s*</button>"),
        Regex("(?is)<a\\b[^>]*>\\s*Get\\s+Torve\\s*</a>"),
    )
    structuralPatterns.forEach { pattern ->
        sanitized = sanitized.replace(pattern, "")
    }
    return sanitized
}

private class TvDocumentWebView(
    context: Context,
) : WebView(context) {
    private var lastHtml: String? = null
    private var lastBaseUrl: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        setBackgroundColor(AndroidColor.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.loadsImagesAutomatically = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }

    fun loadDocument(
        html: String,
        baseUrl: String,
    ) {
        if (lastHtml == html && lastBaseUrl == baseUrl) return
        lastHtml = html
        lastBaseUrl = baseUrl
        loadDataWithBaseURL(
            baseUrl,
            html,
            "text/html",
            "utf-8",
            null,
        )
        post { requestFocus() }
    }
}

private val GraphiteSurface = Color(0x332F3842)

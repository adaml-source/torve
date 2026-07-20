package com.torve.android.ui.legal

import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.torve.android.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface LegalDocumentContentState {
    data object Loading : LegalDocumentContentState

    data class Ready(
        val html: String,
        val source: LegalDocumentSource,
        val warningMessage: String? = null,
    ) : LegalDocumentContentState

    data class Error(
        val message: String,
    ) : LegalDocumentContentState
}

enum class LegalDocumentSource {
    REMOTE,
    ASSET_FALLBACK,
    ASSET_ONLY,
}

@Composable
private fun rememberLegalDocumentContentState(
    url: String?,
    fallbackAssetName: String,
): LegalDocumentContentState {
    val context = LocalContext.current
    return produceState<LegalDocumentContentState>(
        initialValue = LegalDocumentContentState.Loading,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(
    title: String,
    assetFileName: String,
    onBack: () -> Unit,
    remoteUrl: String? = null,
) {
    val contentState = rememberLegalDocumentContentState(
        url = remoteUrl,
        fallbackAssetName = assetFileName,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_cd))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        when (val state = contentState) {
            LegalDocumentContentState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.legal_document_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            is LegalDocumentContentState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.legal_document_error_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.legal_document_error_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.message.isNotBlank()) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            is LegalDocumentContentState.Ready -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.source == LegalDocumentSource.ASSET_FALLBACK) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.legal_document_fallback_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.legal_document_fallback_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.loadsImagesAutomatically = true
                                setBackgroundColor(AndroidColor.TRANSPARENT)
                                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        update = { webView ->
                            webView.loadDataWithBaseURL(
                                remoteUrl ?: "file:///android_asset/",
                                state.html,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        },
                        onRelease = { webView ->
                            webView.destroy()
                        },
                    )
                }
            }
        }
    }
}

private suspend fun ProduceStateScope<LegalDocumentContentState>.loadDocumentContent(
    context: Context,
    url: String?,
    fallbackAssetName: String,
) {
    value = LegalDocumentContentState.Loading
    value = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) {
            return@withContext runCatching { loadAssetHtml(context, fallbackAssetName) }
                .fold(
                    onSuccess = { html ->
                        LegalDocumentContentState.Ready(
                            html = normalizeDocumentHtml(html),
                            source = LegalDocumentSource.ASSET_ONLY,
                        )
                    },
                    onFailure = { error ->
                        LegalDocumentContentState.Error(
                            message = error.message ?: "Unable to load document.",
                        )
                    },
                )
        }

        val remote = runCatching { fetchRemoteHtml(url) }
        if (remote.isSuccess) {
            LegalDocumentContentState.Ready(
                html = normalizeDocumentHtml(remote.getOrThrow()),
                source = LegalDocumentSource.REMOTE,
            )
        } else {
            val fallback = runCatching { loadAssetHtml(context, fallbackAssetName) }
            if (fallback.isSuccess) {
                LegalDocumentContentState.Ready(
                    html = normalizeDocumentHtml(fallback.getOrThrow()),
                    source = LegalDocumentSource.ASSET_FALLBACK,
                    warningMessage = remote.exceptionOrNull()?.message,
                )
            } else {
                LegalDocumentContentState.Error(
                    message = remote.exceptionOrNull()?.message
                        ?: fallback.exceptionOrNull()?.message
                        ?: "Unable to load document.",
                )
            }
        }
    }
}

private fun loadAssetHtml(
    context: Context,
    assetFileName: String,
): String {
    return context.assets.open(assetFileName).bufferedReader().use { it.readText() }
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
              padding: 20px 18px 32px;
              background: #0B1020;
              color: #E5E7EB;
              font-family: sans-serif;
              line-height: 1.65;
              font-size: 16px;
            }
            h1, h2, h3, h4 {
              color: #F8FAFC;
              line-height: 1.25;
              margin-top: 1.2em;
              margin-bottom: 0.5em;
            }
            h1 {
              font-size: 1.85em;
              margin-top: 0;
            }
            h2 {
              font-size: 1.35em;
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
    val patterns = listOf(
        Regex("(?is)<header\\b[^>]*>.*?</header>"),
        Regex("(?is)<nav\\b[^>]*>.*?</nav>"),
        Regex("(?is)<footer\\b[^>]*>.*?</footer>"),
        Regex("(?is)<button\\b[^>]*>\\s*Get\\s+Torve\\s*</button>"),
        Regex("(?is)<a\\b[^>]*>\\s*Get\\s+Torve\\s*</a>"),
    )
    patterns.forEach { pattern ->
        sanitized = sanitized.replace(pattern, "")
    }
    return sanitized
}

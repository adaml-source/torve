package com.torve.android.tv.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import com.torve.android.ui.home.TrimTransparentPaddingTransformation
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem

/**
 * Displays transparent title/logo artwork without cropping. Text is shown only
 * after lookup/load has established that artwork is unavailable, avoiding the
 * distracting plain-title-to-logo swap while focus moves across a poster row.
 * The caller owns the maximum bounds, allowing the same behavior to fit both a
 * wide hero and a narrow preview.
 */
@Composable
fun TvTitleArtworkOrText(
    item: MediaItem,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    maxTextLines: Int = 2,
    textAlignment: Alignment = Alignment.CenterStart,
    artworkLookupPending: Boolean = false,
) {
    val logoUrl = item.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    val context = LocalContext.current
    val artworkModel = remember(logoUrl) {
        logoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .transformations(TrimTransparentPaddingTransformation)
                .build()
        }
    }
    var artworkFailed by remember(logoUrl) { mutableStateOf(false) }
    val showTextFallback = artworkFailed || (logoUrl == null && !artworkLookupPending)

    Box(
        modifier = modifier,
        contentAlignment = textAlignment,
    ) {
        if (showTextFallback) {
            Text(
                text = item.title,
                style = textStyle,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                maxLines = maxTextLines,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (logoUrl != null && !artworkFailed) {
            AsyncImage(
                model = artworkModel,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Error -> artworkFailed = true
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

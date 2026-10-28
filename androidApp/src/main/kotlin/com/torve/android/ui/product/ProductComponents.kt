package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

/** Shared touch surfaces. Insets are owned here; root-tab bottom space belongs to navigation. */
@Composable
fun ProductPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.TopCenter).statusBarsPadding()) {
            if (showHeader) Row(
                Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) com.torve.android.ui.components.BackButton(onClick = onBack, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    if (!subtitle.isNullOrBlank()) Text(localizedUiText(subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                actions()
            }
            content()
        }
      }
    }
}

@Composable
fun ProductToolRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().mobileGlassSurface(selected = selected, enabled = enabled, quiet = true),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(localizedUiText(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun ProductFeedback(message: String, isError: Boolean = false, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedUiText(message), style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.flow_try_again)) }
        }
    }
}

@Composable
fun ProductEmptyState(title: String, message: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(localizedUiText(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
fun ArtworkTitle(item: MediaItem, modifier: Modifier = Modifier, logoUrl: String? = item.logoUrl, onDarkArtwork: Boolean = false) {
    val permittedLogo = logoUrl?.takeIf { it.isNotBlank() && !item.isContentPlaceholder && !item.isStubDetail }
    var logoFailed by remember(item.id, permittedLogo) { mutableStateOf(false) }
    if (permittedLogo != null && !logoFailed) {
        AsyncImage(model = permittedLogo, contentDescription = item.title,
            contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp, max = 88.dp), onError = { logoFailed = true })
    } else {
        Text(item.title, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
            color = if (onDarkArtwork) Color.White else MaterialTheme.colorScheme.onBackground,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

@Composable
fun MediaTypeSymbol(type: MediaType, modifier: Modifier = Modifier) {
    Icon(if (type == MediaType.MOVIE) Icons.Rounded.Movie else Icons.Rounded.Tv,
        contentDescription = localizedUiText(if (type == MediaType.MOVIE) "Movie" else "TV show"),
        tint = Color.White, modifier = modifier.clip(RoundedCornerShape(7.dp))
            .background(Color.Black.copy(alpha = .65f)).padding(5.dp).size(14.dp))
}

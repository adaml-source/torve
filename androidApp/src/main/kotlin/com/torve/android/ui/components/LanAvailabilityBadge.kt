package com.torve.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torve.presentation.lanlibrary.LanLibraryConsumer
import org.koin.compose.koinInject

/**
 * Renders an "Available on desktop" pill when the LAN-library
 * consumer's manifest cache reports that this title is hosted on a
 * desktop hub on the same account. The check is synchronous and
 * presence-only — actual playback does the per-item token fetch on
 * tap.
 *
 * Hidden when no manifest match exists, so the pill never appears for
 * titles the user can't actually play over LAN.
 */
@Composable
fun LanAvailabilityBadge(
    title: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    modifier: Modifier = Modifier,
    consumer: LanLibraryConsumer = koinInject(),
) {
    // Subscribe to entries so the badge appears within ~1 minute of a
    // hub publishing a new manifest, without needing the surrounding
    // screen to plumb the flow itself.
    val entries by consumer.entries.collectAsState()
    val hasMatch = remember(entries, title, seasonNumber, episodeNumber) {
        consumer.hasLanMatch(title, seasonNumber, episodeNumber)
    }
    if (!hasMatch) return

    Row(
        modifier = modifier
            .background(
                color = Color(0xFF184D2E).copy(alpha = 0.85f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Computer,
            contentDescription = null,
            tint = Color(0xFF8FE3B0),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Available on desktop",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD9F1E1),
        )
    }
}

/**
 * One-shot kick to ensure the consumer has at least one refresh on
 * record. Drop this into the entry-point Composable of a screen that
 * shows multiple [LanAvailabilityBadge]s; without it the first render
 * after sign-in shows no badges until the next 60 s polling tick.
 */
@Composable
fun LanAvailabilityBootstrap(
    consumer: LanLibraryConsumer = koinInject(),
) {
    LaunchedEffect(consumer) {
        runCatching { consumer.refreshOnce() }
    }
}

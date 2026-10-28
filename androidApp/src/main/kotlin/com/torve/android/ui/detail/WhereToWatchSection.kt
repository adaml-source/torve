package com.torve.android.ui.detail

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.AvailabilityOffer
import com.torve.domain.model.AvailabilityOfferType

@Composable
internal fun WhereToWatchSection(
    contentKey: String,
    offers: List<AvailabilityOffer>,
    isLoading: Boolean,
    error: String?,
    onOpenOffer: (AvailabilityOffer) -> Unit,
) {
    var expanded by rememberSaveable(contentKey) { mutableStateOf(false) }
    val providers = remember(offers) { offers.groupBy { it.providerName } }
    val names = providers.keys.take(2).joinToString(" · ")
    val summary = when {
        providers.size > 2 -> stringResource(R.string.detail_watch_more_services, names, providers.size - 2)
        providers.isNotEmpty() -> names
        isLoading -> stringResource(R.string.common_loading)
        error != null -> stringResource(R.string.detail_watch_unavailable)
        else -> stringResource(R.string.detail_no_offers)
    }
    val expansionState = stringResource(if (expanded) R.string.detail_watch_expanded else R.string.detail_watch_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .semantics { stateDescription = expansionState }
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 56.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.detail_where_to_watch),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = Torve.colors.textPrimary)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null, tint = Torve.colors.textSecondary)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                }
                error?.let {
                    Text(localizedUiText(it), style = MaterialTheme.typography.bodySmall, color = Torve.colors.textTertiary)
                }
                // Each service appears once; availability types are information, not duplicate links.
                providers.forEach { (name, serviceOffers) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, color = Torve.colors.textPrimary)
                        Text(serviceOffers.map { it.offerType }.distinct().sortedBy { it.ordinal }
                            .map { offerTypeLabel(it) }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
                    }
                }
                // TMDB supplies one title-level destination for all of these services.
                offers.firstOrNull { !it.webUrl.isNullOrBlank() || !it.deeplinkUrl.isNullOrBlank() }?.let { offer ->
                    TextButton(onClick = { onOpenOffer(offer) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.detail_watch_on_tmdb), color = Amber)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null,
                            modifier = Modifier.size(16.dp), tint = Amber)
                    }
                }
                if (providers.isNotEmpty()) {
                    Text(stringResource(R.string.detail_watch_attribution),
                        style = MaterialTheme.typography.labelSmall, color = Torve.colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun offerTypeLabel(type: AvailabilityOfferType): String = stringResource(when (type) {
    AvailabilityOfferType.SUBSCRIPTION -> R.string.detail_watch_subscription
    AvailabilityOfferType.RENT -> R.string.detail_watch_rent
    AvailabilityOfferType.BUY -> R.string.detail_watch_buy
    AvailabilityOfferType.FREE -> R.string.detail_watch_free
    AvailabilityOfferType.ADS -> R.string.detail_watch_ads
})

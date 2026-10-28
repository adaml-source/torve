package com.torve.android.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.community.SettingsMenus
import com.torve.android.ui.community.parityLabel
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.mobileGlassSurface

/** Current source forms, shared by onboarding, Settings, repair links and old route aliases. */
@Composable
fun SourceSetupScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    termsAccepted: Boolean,
    onAcceptTerms: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("source_setup")) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Text(parityLabel("Source setup"), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.source_setup_intro), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!termsAccepted) {
                item {
                    Text(stringResource(R.string.setup_terms_title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.setup_terms_text), modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.setup_tmdb_text), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().clickable(onClick = onAcceptTerms),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(false, { if (it) onAcceptTerms() }, Modifier.testTag("source_setup.terms"))
                        Text(stringResource(R.string.setup_accept_terms), Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            items(SettingsMenus.sourceSetup(), key = { it.id }) { entry ->
                Surface(Modifier.fillMaxWidth().mobileGlassSurface(quiet = true),
                    shape = RoundedCornerShape(20.dp), color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = termsAccepted) { entry.action?.let(onOpen) }
                        .testTag("source_setup.${entry.id}").padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(parityLabel(entry.title), style = MaterialTheme.typography.titleMedium)
                            Text(parityLabel(entry.summary), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        TextButton(onBack, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.source_setup_return))
        }
    }
}

package com.torve.android.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R

// TODO: Before Play Store release, host this privacy policy text at a public URL
//  and reference that URL in the Google Play Store listing's privacy policy field.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_privacy_policy)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.privacy_last_updated), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))

            SectionTitle("1. Data We Collect")
            BodyText(
                "Torve stores the following data locally on your device:\n" +
                    "\u2022 Watch history and progress\n" +
                    "\u2022 User preferences and settings\n" +
                    "\u2022 API keys you enter for third-party services\n" +
                    "\u2022 Playlist URLs and channel favorites\n\n" +
                    "This data is stored only on your device and is not transmitted to Torve servers.",
            )

            SectionTitle("2. Data Sent to Third Parties")
            BodyText(
                "When you use certain features, data is sent to external services you configure:\n\n" +
                    "\u2022 TMDB API: Search queries and media IDs are sent to retrieve metadata " +
                    "(titles, posters, descriptions). No personal data is included.\n" +
                    "\u2022 Trakt.tv: If you enable Trakt sync, your watch history and watchlist " +
                    "are synchronized with Trakt's servers under your Trakt account.\n" +
                    "\u2022 AI Providers: If you configure an AI search provider (e.g., Claude, " +
                    "ChatGPT, Gemini), your search queries are sent to that provider's API. " +
                    "No other personal data is included.\n" +
                    "\u2022 Streaming Services: If you configure a streaming optimization service, content identifiers " +
                    "are sent to check availability. Your API key is sent for authentication.\n" +
                    "\u2022 Jellyfin/Plex: If configured, Torve communicates with your media server " +
                    "to retrieve library and playback data.",
            )

            SectionTitle("3. Analytics and Crash Reporting")
            BodyText(
                "The Google Play version of Torve includes Firebase Analytics and Firebase " +
                    "Crashlytics, provided by Google. These services collect:\n\n" +
                    "\u2022 Crash logs and stack traces to help us fix bugs\n" +
                    "\u2022 Anonymous usage statistics (e.g., screen views, feature usage)\n" +
                    "\u2022 Device information (model, OS version, app version)\n\n" +
                    "This data is sent to Google's servers and is governed by Google's Privacy " +
                    "Policy. No personally identifiable information (names, email addresses, " +
                    "API keys, or content you watch) is included in analytics data.\n\n" +
                    "The Amazon Appstore version of Torve does not include Firebase or any " +
                    "analytics SDKs.",
            )

            SectionTitle("4. Data Sharing")
            BodyText(
                "Torve does not sell, share, or transfer your personal data to advertisers " +
                    "or data brokers.",
            )

            SectionTitle("5. Third-Party Services")
            BodyText(
                "Third-party services you connect to Torve (Trakt, AI providers, cloud services, " +
                    "media servers) are governed by their own privacy policies. We encourage you " +
                    "to review those policies before connecting your accounts.",
            )

            SectionTitle("6. Data Security")
            BodyText(
                "API keys and sensitive credentials are encrypted using the Android Keystore " +
                    "system. Other local data is stored in the app's private storage, which is " +
                    "protected by Android's sandboxing model.",
            )

            SectionTitle("7. Data Deletion")
            BodyText(
                "You can delete all app data by clearing the app storage in Android Settings, " +
                    "or by uninstalling the app. Individual data (watch history, playlists, " +
                    "API keys) can be managed within the app settings.",
            )

            SectionTitle("8. Contact")
            BodyText(
                "For privacy-related questions or concerns, contact us at:\n" +
                    "privacy@torve.app",
            )

            SectionTitle("TMDB Attribution")
            BodyText(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

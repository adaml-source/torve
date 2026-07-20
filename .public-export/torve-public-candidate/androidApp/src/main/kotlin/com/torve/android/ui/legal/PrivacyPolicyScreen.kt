package com.torve.android.ui.legal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torve.android.R

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    LegalScreen(
        title = stringResource(R.string.settings_privacy_policy),
        assetFileName = "privacy.html",
        remoteUrl = "https://torve.app/privacy.html",
        onBack = onBack,
    )
}

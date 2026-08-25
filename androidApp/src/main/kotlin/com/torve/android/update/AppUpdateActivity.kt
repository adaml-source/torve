package com.torve.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.ui.theme.TorveTheme

internal const val FIRE_TV_DOWNLOADER_PACKAGE = "com.esaba.downloader"

internal fun fireTvDownloaderDeepLink(downloadUrl: String): String =
    "downloader://$downloadUrl"

/**
 * Amazon-TV-only update handoff. Immediately opens the Downloader app with the APK URL
 * so Fire TV handles the download and installation natively.
 *
 * Registered only by the amazonTv manifest overlay.
 */
class AppUpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!usesVpsReleaseUpdates(BuildConfig.FLAVOR)) {
            finish()
            return
        }
        val update = intent.toAvailableAppUpdate()
        if (update == null) {
            finish()
            return
        }

        if (openInDownloaderApp(update.downloadUrl)) {
            finish()
            return
        }

        val titleText = getString(R.string.app_update_notification_title, update.version)
        val bodyText = getString(R.string.app_update_downloader_not_found)
        setContent {
            TorveTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 620.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                text = bodyText,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openInDownloaderApp(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fireTvDownloaderDeepLink(url))).apply {
            setPackage(FIRE_TV_DOWNLOADER_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { startActivity(intent) }.isSuccess
    }

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SHA256 = "update_sha256"
        private const val EXTRA_SIZE_BYTES = "update_size_bytes"

        internal fun createIntent(context: Context, update: AvailableAppUpdate): Intent =
            Intent(context, AppUpdateActivity::class.java).apply {
                putExtra(EXTRA_VERSION, update.version)
                putExtra(EXTRA_URL, update.downloadUrl)
                putExtra(EXTRA_SHA256, update.sha256)
                putExtra(EXTRA_SIZE_BYTES, update.sizeBytes)
            }

        private fun Intent.toAvailableAppUpdate(): AvailableAppUpdate? {
            val version = getStringExtra(EXTRA_VERSION)?.trim().orEmpty()
            val url = getStringExtra(EXTRA_URL)?.trim().orEmpty()
            val sha256 = getStringExtra(EXTRA_SHA256)?.trim()?.lowercase().orEmpty()
            val sizeBytes = getLongExtra(EXTRA_SIZE_BYTES, 0L)
            if (
                version.isBlank() ||
                !isTrustedUpdateUrl(url) ||
                !sha256.matches(Regex("^[0-9a-f]{64}$")) ||
                sizeBytes <= 0L
            ) return null
            return AvailableAppUpdate(version, url, sha256, sizeBytes)
        }
    }
}

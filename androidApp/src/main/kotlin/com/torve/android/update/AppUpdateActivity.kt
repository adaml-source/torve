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
 * Keeps the validated release handoff available across process death and the switch to
 * Downloader. The entry intentionally remains until the installed version catches up so a
 * recreated update activity never falls back to opening Downloader without its APK URL.
 */
internal class AppUpdateHandoffStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(update: AvailableAppUpdate): Boolean = prefs.edit()
        .putString(KEY_VERSION, update.version)
        .putString(KEY_URL, update.downloadUrl)
        .putString(KEY_SHA256, update.sha256)
        .putLong(KEY_SIZE_BYTES, update.sizeBytes)
        // This must reach disk before Torve hands control to another application.
        .commit()

    fun load(): AvailableAppUpdate? {
        val version = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val url = prefs.getString(KEY_URL, null)?.trim().orEmpty()
        val sha256 = prefs.getString(KEY_SHA256, null)?.trim()?.lowercase().orEmpty()
        val sizeBytes = prefs.getLong(KEY_SIZE_BYTES, 0L)
        if (
            version.isBlank() ||
            !isTrustedUpdateUrl(url) ||
            !sha256.matches(Regex("^[0-9a-f]{64}$")) ||
            sizeBytes <= 0L
        ) return null
        return AvailableAppUpdate(version, url, sha256, sizeBytes)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update_handoff"
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE_BYTES = "size_bytes"
    }
}

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
        val handoffStore = AppUpdateHandoffStore(applicationContext)
        val update = intent.toAvailableAppUpdate()
            ?.also { handoffStore.save(it) }
            ?: handoffStore.load()
        if (update == null || !isNewerRelease(update.version, BuildConfig.VERSION_NAME)) {
            handoffStore.clear()
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
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
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

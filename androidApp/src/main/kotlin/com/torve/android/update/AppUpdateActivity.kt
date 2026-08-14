package com.torve.android.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.ui.theme.TorveTheme
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Amazon-TV-only update handoff. The activity is registered only by the
 * amazonTv manifest overlay; Google Play variants cannot launch or expose it.
 */
class AppUpdateActivity : AppCompatActivity() {
    private var update: AvailableAppUpdate? = null
    private var downloadedApk: File? = null
    private var statusText by mutableStateOf("")
    private var progress by mutableFloatStateOf(0f)
    private var action by mutableStateOf(UpdateAction.NONE)

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (canRequestPackageInstalls()) {
            downloadedApk?.let(::openSystemInstaller)
        } else {
            statusText = getString(R.string.app_update_permission_required)
            action = UpdateAction.ALLOW_INSTALLATION
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!usesVpsReleaseUpdates(BuildConfig.FLAVOR)) {
            finish()
            return
        }
        update = intent.toAvailableAppUpdate()
        if (update == null) {
            finish()
            return
        }

        setContent {
            TorveTheme {
                UpdateInstallScreen(
                    version = update?.version.orEmpty(),
                    status = statusText,
                    progress = progress,
                    action = action,
                    onAction = ::handleAction,
                )
            }
        }
        startDownload()
    }

    private fun startDownload() {
        val candidate = update ?: return
        action = UpdateAction.NONE
        progress = 0f
        statusText = getString(R.string.app_update_downloading, candidate.version)
        lifecycleScope.launch {
            runCatching {
                AppUpdateDownloader.downloadAndVerify(
                    context = this@AppUpdateActivity,
                    update = candidate,
                    onProgress = { value -> runOnUiThread { progress = value } },
                )
            }.onSuccess { apk ->
                downloadedApk = apk
                if (canRequestPackageInstalls()) {
                    openSystemInstaller(apk)
                } else {
                    statusText = getString(R.string.app_update_permission_required)
                    action = UpdateAction.ALLOW_INSTALLATION
                }
            }.onFailure {
                statusText = getString(R.string.app_update_download_failed)
                action = UpdateAction.RETRY
            }
        }
    }

    private fun handleAction() {
        when (action) {
            UpdateAction.RETRY -> startDownload()
            UpdateAction.ALLOW_INSTALLATION -> openUnknownSourcesSettings()
            UpdateAction.NONE -> Unit
        }
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        val appSettings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        runCatching { unknownSourcesLauncher.launch(appSettings) }
            .onFailure {
                runCatching {
                    unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }.onFailure {
                    statusText = getString(R.string.app_update_download_failed)
                    action = UpdateAction.RETRY
                }
            }
    }

    private fun openSystemInstaller(apk: File) {
        statusText = getString(R.string.app_update_opening_installer)
        action = UpdateAction.NONE
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.update.files",
            apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Torve update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(installIntent) }
            .onSuccess { finish() }
            .onFailure {
                statusText = getString(R.string.app_update_download_failed)
                action = UpdateAction.RETRY
            }
    }

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SHA256 = "update_sha256"
        private const val EXTRA_SIZE_BYTES = "update_size_bytes"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

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

private enum class UpdateAction {
    NONE,
    RETRY,
    ALLOW_INSTALLATION,
}

@Composable
private fun UpdateInstallScreen(
    version: String,
    status: String,
    progress: Float,
    action: UpdateAction,
    onAction: () -> Unit,
) {
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
                    text = "Torve $version",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(text = status, style = MaterialTheme.typography.bodyLarge)
                if (action == UpdateAction.NONE) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onAction) {
                        Text(
                            stringResource(
                                when (action) {
                                    UpdateAction.RETRY -> R.string.app_update_retry
                                    UpdateAction.ALLOW_INSTALLATION -> R.string.app_update_allow_installation
                                    UpdateAction.NONE -> R.string.app_update_download_install
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

internal object AppUpdateDownloader {
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L

    suspend fun downloadAndVerify(
        context: Context,
        update: AvailableAppUpdate,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(isTrustedUpdateUrl(update.downloadUrl)) { "Untrusted update URL" }
        require(update.sizeBytes in 1..MAX_APK_BYTES) { "Invalid update size" }

        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(updateDirectory, "torve-$safeVersion.apk")
        if (
            destination.isFile &&
            destination.length() == update.sizeBytes &&
            sha256(destination) == update.sha256 &&
            isValidSignedUpgrade(context, destination)
        ) {
            onProgress(1f)
            return@withContext destination
        }

        val partial = File(updateDirectory, "torve-$safeVersion.apk.part")
        if (partial.exists()) partial.delete()
        try {
            val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
            }
            try {
                if (connection.responseCode !in 200..299) throw IOException("Update download failed")
                if (!isTrustedUpdateUrl(connection.url.toString())) throw IOException("Untrusted redirect")
                val contentLength = connection.contentLengthLong
                if (contentLength > 0L && contentLength != update.sizeBytes) {
                    throw IOException("Update size mismatch")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > update.sizeBytes || total > MAX_APK_BYTES) {
                                throw IOException("Update exceeds declared size")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(total.toFloat() / update.sizeBytes.toFloat())
                        }
                    }
                }
                if (total != update.sizeBytes) throw IOException("Incomplete update")
                val actualSha256 = digest.digest().toHex()
                if (actualSha256 != update.sha256) throw IOException("Update checksum mismatch")
            } finally {
                connection.disconnect()
            }

            if (destination.exists() && !destination.delete()) throw IOException("Could not replace cached update")
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            if (!isValidSignedUpgrade(context, destination)) {
                destination.delete()
                throw IOException("Update signature or package mismatch")
            }
            onProgress(1f)
            destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun isValidSignedUpgrade(context: Context, apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) return false
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) return false
        return archive.signerDigests().intersect(installed.signerDigests()).isNotEmpty()
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signerDigests(): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            this.signatures
        }
        return signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

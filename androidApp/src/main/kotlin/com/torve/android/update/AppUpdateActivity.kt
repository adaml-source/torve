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
import android.util.Log
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
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the validated release available across process death while Android's
 * package-install permission screen owns the foreground.
 */
internal class AppUpdateHandoffStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(update: AvailableAppUpdate): Boolean = prefs.edit()
        .putString(KEY_VERSION, update.version)
        .putLong(KEY_VERSION_CODE, update.versionCode)
        .putString(KEY_URL, update.downloadUrl)
        .putString(KEY_SHA256, update.sha256)
        .putLong(KEY_SIZE_BYTES, update.sizeBytes)
        .commit()

    fun load(): AvailableAppUpdate? {
        val version = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val versionCode = prefs.getLong(KEY_VERSION_CODE, 0L)
        val url = prefs.getString(KEY_URL, null)?.trim().orEmpty()
        val sha256 = prefs.getString(KEY_SHA256, null)?.trim()?.lowercase().orEmpty()
        val sizeBytes = prefs.getLong(KEY_SIZE_BYTES, 0L)
        if (
            version.isBlank() ||
            !isTrustedUpdateUrl(url) ||
            !sha256.matches(Regex("^[0-9a-f]{64}$")) ||
            sizeBytes <= 0L
        ) return null
        return AvailableAppUpdate(version, url, sha256, sizeBytes, versionCode)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update_handoff"
        const val KEY_VERSION = "version"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE_BYTES = "size_bytes"
    }
}

/**
 * Amazon-TV-only canonical updater. Torve downloads the exact manifest asset,
 * verifies its size, SHA-256, package identity and signing certificate, then
 * opens Android's package installer. This avoids treating a successful launch
 * of a third-party browser as proof that a download actually started.
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
        val handoffStore = AppUpdateHandoffStore(applicationContext)
        update = intent.toAvailableAppUpdate()
            ?.also { handoffStore.save(it) }
            ?: handoffStore.load()
        val candidate = update
        if (
            candidate == null ||
            !isNewerRelease(
                candidate = candidate.version,
                installed = BuildConfig.VERSION_NAME,
                candidateVersionCode = candidate.versionCode,
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            )
        ) {
            handoffStore.clear()
            finish()
            return
        }

        setContent {
            TorveTheme {
                UpdateInstallScreen(
                    version = candidate.version,
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
        statusText = getString(R.string.app_update_resolving, candidate.version)
        lifecycleScope.launch {
            runCatching {
                AppUpdateDownloader.downloadAndVerify(
                    context = this@AppUpdateActivity,
                    update = candidate,
                    onAccepted = { finalUrl ->
                        Log.i(TAG, "Validated update download URL: ${redactUpdateUrl(finalUrl)}")
                        runOnUiThread {
                            statusText = getString(R.string.app_update_downloading, candidate.version)
                        }
                    },
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
            }.onFailure { error ->
                Log.e(TAG, "Update download failed", error)
                statusText = getString(error.updateFailureMessage())
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
                    statusText = getString(R.string.app_update_permission_failed)
                    action = UpdateAction.RETRY
                }
            }
    }

    private fun openSystemInstaller(apk: File) {
        statusText = getString(R.string.app_update_opening_installer)
        action = UpdateAction.NONE
        val uri = FileProvider.getUriForFile(this, "$packageName.update.files", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Torve update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(installIntent) }
            .onSuccess { finish() }
            .onFailure { error ->
                Log.e(TAG, "Could not open package installer", error)
                statusText = getString(R.string.app_update_installer_failed)
                action = UpdateAction.RETRY
            }
    }

    companion object {
        private const val TAG = "TorveUpdate"
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_VERSION_CODE = "update_version_code"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SHA256 = "update_sha256"
        private const val EXTRA_SIZE_BYTES = "update_size_bytes"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        internal fun createIntent(context: Context, update: AvailableAppUpdate): Intent =
            Intent(context, AppUpdateActivity::class.java).apply {
                putExtra(EXTRA_VERSION, update.version)
                putExtra(EXTRA_VERSION_CODE, update.versionCode)
                putExtra(EXTRA_URL, update.downloadUrl)
                putExtra(EXTRA_SHA256, update.sha256)
                putExtra(EXTRA_SIZE_BYTES, update.sizeBytes)
            }

        private fun Intent.toAvailableAppUpdate(): AvailableAppUpdate? {
            val version = getStringExtra(EXTRA_VERSION)?.trim().orEmpty()
            val versionCode = getLongExtra(EXTRA_VERSION_CODE, 0L)
            val url = getStringExtra(EXTRA_URL)?.trim().orEmpty()
            val sha256 = getStringExtra(EXTRA_SHA256)?.trim()?.lowercase().orEmpty()
            val sizeBytes = getLongExtra(EXTRA_SIZE_BYTES, 0L)
            if (
                version.isBlank() ||
                !isTrustedUpdateUrl(url) ||
                !sha256.matches(Regex("^[0-9a-f]{64}$")) ||
                sizeBytes <= 0L
            ) return null
            return AvailableAppUpdate(version, url, sha256, sizeBytes, versionCode)
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
                Text(text = "Torve $version", style = MaterialTheme.typography.headlineMedium)
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

internal enum class UpdateDownloadFailure {
    NETWORK,
    HTTP,
    INVALID_ASSET,
    VERIFICATION,
}

internal class UpdateDownloadException(
    val reason: UpdateDownloadFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun Throwable.updateFailureMessage(): Int = when ((this as? UpdateDownloadException)?.reason) {
    UpdateDownloadFailure.NETWORK -> R.string.app_update_network_failed
    UpdateDownloadFailure.HTTP -> R.string.app_update_http_failed
    UpdateDownloadFailure.INVALID_ASSET -> R.string.app_update_invalid_asset
    UpdateDownloadFailure.VERIFICATION -> R.string.app_update_verification_failed
    null -> R.string.app_update_download_failed
}

internal fun isUpdateRedirectStatus(statusCode: Int): Boolean =
    statusCode == HttpURLConnection.HTTP_MOVED_PERM ||
        statusCode == HttpURLConnection.HTTP_MOVED_TEMP ||
        statusCode == HttpURLConnection.HTTP_SEE_OTHER ||
        statusCode == 307 ||
        statusCode == 308

internal fun redactUpdateUrl(value: String): String = runCatching {
    val uri = URI(value)
    URI(uri.scheme, uri.authority, uri.path, null, null).toString()
}.getOrDefault("<invalid-url>")

internal object AppUpdateDownloader {
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private const val MAX_REDIRECTS = 8
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    suspend fun downloadAndVerify(
        context: Context,
        update: AvailableAppUpdate,
        onAccepted: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!isTrustedUpdateUrl(update.downloadUrl)) {
            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Untrusted update URL")
        }
        if (update.sizeBytes !in 1..MAX_APK_BYTES) {
            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Invalid update size")
        }

        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val buildSuffix = update.versionCode.takeIf { it > 0L }?.let { "-$it" }.orEmpty()
        val destination = File(updateDirectory, "torve-$safeVersion$buildSuffix.apk")
        if (
            destination.isFile &&
            destination.length() == update.sizeBytes &&
            sha256(destination) == update.sha256 &&
            isValidSignedUpgrade(context, destination)
        ) {
            onAccepted(update.downloadUrl)
            onProgress(1f)
            return@withContext destination
        }

        val partial = File(updateDirectory, "torve-$safeVersion$buildSuffix.apk.part")
        if (partial.exists()) partial.delete()
        try {
            val connection = openUpdateConnection(update.downloadUrl)
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw UpdateDownloadException(UpdateDownloadFailure.HTTP, "Update returned HTTP $responseCode")
                }
                val finalUrl = connection.url.toString()
                if (!isTrustedUpdateUrl(finalUrl)) {
                    throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Untrusted redirect")
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > 0L && contentLength != update.sizeBytes) {
                    throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Update size mismatch")
                }
                onAccepted(finalUrl)

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    connection.inputStream.buffered().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > update.sizeBytes || total > MAX_APK_BYTES) {
                                    throw UpdateDownloadException(
                                        UpdateDownloadFailure.INVALID_ASSET,
                                        "Update exceeds declared size",
                                    )
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(total.toFloat() / update.sizeBytes.toFloat())
                            }
                        }
                    }
                } catch (error: UpdateDownloadException) {
                    throw error
                } catch (error: IOException) {
                    throw UpdateDownloadException(UpdateDownloadFailure.NETWORK, "Update download interrupted", error)
                }
                if (total != update.sizeBytes) {
                    throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Incomplete update")
                }
                val actualSha256 = digest.digest().toHex()
                if (actualSha256 != update.sha256) {
                    throw UpdateDownloadException(UpdateDownloadFailure.VERIFICATION, "Update checksum mismatch")
                }
            } finally {
                connection.disconnect()
            }

            if (destination.exists() && !destination.delete()) {
                throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Could not replace cached update")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            if (!isValidSignedUpgrade(context, destination)) {
                destination.delete()
                throw UpdateDownloadException(
                    UpdateDownloadFailure.VERIFICATION,
                    "Update signature, package, or version mismatch",
                )
            }
            onProgress(1f)
            destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun openUpdateConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = try {
                (currentUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "$APK_MIME_TYPE, application/octet-stream")
                    setRequestProperty("User-Agent", "Torve/${BuildConfig.VERSION_NAME} FireTV updater")
                }
            } catch (error: IOException) {
                throw UpdateDownloadException(UpdateDownloadFailure.NETWORK, "Could not open update URL", error)
            }
            val statusCode = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw UpdateDownloadException(UpdateDownloadFailure.NETWORK, "Update request failed", error)
            }
            if (!isUpdateRedirectStatus(statusCode)) return connection

            val location = connection.getHeaderField("Location")?.trim().orEmpty()
            connection.disconnect()
            if (location.isBlank()) {
                throw UpdateDownloadException(UpdateDownloadFailure.HTTP, "Update redirect has no destination")
            }
            if (redirectCount >= MAX_REDIRECTS) {
                throw UpdateDownloadException(UpdateDownloadFailure.HTTP, "Update redirect limit exceeded")
            }
            val redirected = runCatching { URL(currentUrl, location) }.getOrElse { error ->
                throw UpdateDownloadException(UpdateDownloadFailure.HTTP, "Invalid update redirect", error)
            }
            if (!isTrustedUpdateUrl(redirected.toString())) {
                throw UpdateDownloadException(UpdateDownloadFailure.INVALID_ASSET, "Untrusted update redirect")
            }
            currentUrl = redirected
        }
        throw UpdateDownloadException(UpdateDownloadFailure.HTTP, "Update redirect limit exceeded")
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

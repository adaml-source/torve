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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persists trusted release metadata while Android owns the foreground. */
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
            versionCode <= 0L ||
            !isTrustedUpdateUrl(url) ||
            !sha256.matches(SHA256_PATTERN) ||
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

internal enum class UpdaterPhase {
    IDLE,
    CHECKING,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING_INTEGRITY,
    VERIFYING_PACKAGE,
    VERIFYING_SIGNATURE,
    READY_TO_INSTALL,
    LAUNCHING_INSTALLER,
    INSTALLER_LAUNCHED,
    UP_TO_DATE,
    FAILED,
}

internal enum class UpdateFailureReason {
    NETWORK_ERROR,
    HTTP_ERROR,
    INVALID_CONTENT,
    DOWNLOAD_INCOMPLETE,
    INVALID_APK,
    HASH_MISMATCH,
    SIGNATURE_MISMATCH,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    FILE_PROVIDER_ERROR,
    INSTALLER_UNAVAILABLE,
    UNKNOWN,
}

internal data class UpdateDiagnostics(
    val manifestUrl: String = APP_UPDATE_MANIFEST_URL,
    val requestedUrl: String = "",
    val finalUrl: String? = null,
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val declaredBytes: Long? = null,
    val expectedBytes: Long = 0L,
    val actualBytes: Long = 0L,
    val destination: String = "",
    val expectedSha256: String = "",
    val actualSha256: String? = null,
    val expectedPackage: String = "",
    val downloadedPackage: String? = null,
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    val availableVersionName: String = "",
    val availableVersionCode: Long = 0L,
    val downloadedVersionName: String? = null,
    val downloadedVersionCode: Long? = null,
    val installedSignerSha256: Set<String> = emptySet(),
    val downloadedSignerSha256: Set<String> = emptySet(),
    val failureReason: UpdateFailureReason? = null,
) {
    fun debugSummary(phase: UpdaterPhase): String = buildString {
        append("state=").append(phase.name)
        append("\nbytes=").append(actualBytes).append('/').append(expectedBytes)
        append("\nsha=").append(expectedSha256.take(12)).append("/")
            .append(actualSha256?.take(12) ?: "-")
        append("\npackage=").append(downloadedPackage ?: "-")
        append("\nversion=").append(downloadedVersionName ?: "-")
            .append(" (").append(downloadedVersionCode ?: 0L).append(')')
        append("\nsignature=").append(
            when {
                downloadedSignerSha256.isEmpty() -> "not read"
                installedSignerSha256.intersect(downloadedSignerSha256).isNotEmpty() -> "match"
                else -> "mismatch"
            },
        )
        failureReason?.let { append("\nfailure=").append(it.name) }
    }
}

internal data class AppUpdaterState(
    val phase: UpdaterPhase = UpdaterPhase.IDLE,
    val progress: Float = 0f,
    val failureReason: UpdateFailureReason? = null,
    val verifiedApk: File? = null,
    val requiresInstallPermission: Boolean = false,
    val diagnostics: UpdateDiagnostics = UpdateDiagnostics(),
)

internal class UpdateDownloadException(
    val reason: UpdateFailureReason,
    message: String,
    val diagnostics: UpdateDiagnostics? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Amazon-TV canonical updater. Both Settings and update notifications enter this
 * activity, which owns download, verification, permission and installer handoff.
 */
class AppUpdateActivity : AppCompatActivity() {
    private var update: AvailableAppUpdate? = null
    private var updaterState by mutableStateOf(AppUpdaterState())
    private var downloadJob: Job? = null
    private val attemptMutex = Mutex()

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = updaterState.verifiedApk
        if (apk != null && canRequestPackageInstalls()) {
            openSystemInstaller(apk)
        } else if (apk != null) {
            updaterState = updaterState.copy(
                phase = UpdaterPhase.READY_TO_INSTALL,
                requiresInstallPermission = true,
            )
        } else {
            fail(UpdateFailureReason.INVALID_APK)
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

        val initialDiagnostics = diagnosticsFor(candidate)
        updaterState = AppUpdaterState(
            phase = UpdaterPhase.UPDATE_AVAILABLE,
            diagnostics = initialDiagnostics,
        )
        logDiagnostics("update_available", initialDiagnostics)
        setContent {
            TorveTheme {
                UpdateInstallScreen(
                    version = update?.version ?: candidate.version,
                    state = updaterState,
                    onPrimaryAction = ::handlePrimaryAction,
                    onBack = ::finish,
                )
            }
        }
        startAttempt(refreshMetadata = false, cleanAttempt = false)
    }

    private fun startAttempt(refreshMetadata: Boolean, cleanAttempt: Boolean) {
        if (updaterState.phase in ACTIVE_PHASES) return
        updaterState = updaterState.copy(
            phase = if (refreshMetadata) UpdaterPhase.CHECKING else UpdaterPhase.UPDATE_AVAILABLE,
            progress = 0f,
            failureReason = null,
            verifiedApk = null,
            requiresInstallPermission = false,
        )
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            attemptMutex.withLock {
                try {
                    var candidate = update ?: throw UpdateDownloadException(
                        UpdateFailureReason.UNKNOWN,
                        "No update metadata",
                    )
                    if (cleanAttempt) {
                        AppUpdateDownloader.cleanupAttemptFiles(this@AppUpdateActivity, candidate)
                    }
                    if (refreshMetadata) {
                        candidate = try {
                            AppUpdateChecker.checkForUpdate()
                        } catch (error: IOException) {
                            throw UpdateDownloadException(
                                UpdateFailureReason.NETWORK_ERROR,
                                "Could not refresh update metadata",
                                updaterState.diagnostics,
                                error,
                            )
                        } ?: run {
                            updaterState = updaterState.copy(phase = UpdaterPhase.UP_TO_DATE)
                            AppUpdateHandoffStore(applicationContext).clear()
                            return@withLock
                        }
                        update = candidate
                        AppUpdateHandoffStore(applicationContext).save(candidate)
                        updaterState = updaterState.copy(
                            phase = UpdaterPhase.UPDATE_AVAILABLE,
                            diagnostics = diagnosticsFor(candidate),
                        )
                    }
                    val apk = AppUpdateDownloader.downloadAndVerify(
                        context = this@AppUpdateActivity,
                        update = candidate,
                        onAccepted = { finalUrl ->
                            Log.i(TAG, "event=download_accepted final_url=${redactUpdateUrl(finalUrl)}")
                        },
                        onPhase = { phase ->
                            runOnUiThread { updaterState = updaterState.copy(phase = phase) }
                        },
                        onProgress = { value ->
                            runOnUiThread { updaterState = updaterState.copy(progress = value) }
                        },
                        onDiagnostics = { diagnostics ->
                            runOnUiThread { updaterState = updaterState.copy(diagnostics = diagnostics) }
                        },
                    )
                    updaterState = updaterState.copy(
                        phase = UpdaterPhase.READY_TO_INSTALL,
                        progress = 1f,
                        verifiedApk = apk,
                    )
                    if (canRequestPackageInstalls()) {
                        openSystemInstaller(apk)
                    } else {
                        updaterState = updaterState.copy(requiresInstallPermission = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "event=update_failed reason=${error.failureReason().name}", error)
                    fail(error.failureReason(), (error as? UpdateDownloadException)?.diagnostics)
                }
            }
        }
    }

    private fun handlePrimaryAction() {
        when {
            updaterState.phase == UpdaterPhase.FAILED ->
                startAttempt(refreshMetadata = true, cleanAttempt = true)
            updaterState.phase == UpdaterPhase.READY_TO_INSTALL && updaterState.requiresInstallPermission ->
                openUnknownSourcesSettings()
        }
    }

    private fun fail(reason: UpdateFailureReason, diagnostics: UpdateDiagnostics? = null) {
        val resolved = (diagnostics ?: updaterState.diagnostics).copy(failureReason = reason)
        logDiagnostics("verification_failed", resolved)
        updaterState = updaterState.copy(
            phase = UpdaterPhase.FAILED,
            failureReason = reason,
            verifiedApk = null,
            requiresInstallPermission = false,
            diagnostics = resolved,
        )
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            runCatching { unknownSourcesLauncher.launch(appSettings) }
                .recoverCatching { unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                .onFailure { fail(UpdateFailureReason.INSTALLER_UNAVAILABLE) }
        } else {
            runCatching { unknownSourcesLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                .onFailure { fail(UpdateFailureReason.INSTALLER_UNAVAILABLE) }
        }
    }

    private fun openSystemInstaller(apk: File) {
        updaterState = updaterState.copy(phase = UpdaterPhase.LAUNCHING_INSTALLER)
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.update.files", apk)
        }.getOrElse { error ->
            Log.e(TAG, "event=file_provider_failed path=${apk.absolutePath}", error)
            fail(UpdateFailureReason.FILE_PROVIDER_ERROR)
            return
        }
        Log.i(TAG, "event=file_provider_ready authority=$packageName.update.files")
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Torve update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(installIntent) }
            .onSuccess {
                Log.i(TAG, "event=installer_intent_accepted package=$packageName")
                updaterState = updaterState.copy(phase = UpdaterPhase.INSTALLER_LAUNCHED)
                finish()
            }
            .onFailure { error ->
                Log.e(TAG, "event=installer_intent_failed", error)
                fail(UpdateFailureReason.INSTALLER_UNAVAILABLE)
            }
    }

    private fun diagnosticsFor(candidate: AvailableAppUpdate): UpdateDiagnostics = UpdateDiagnostics(
        requestedUrl = redactUpdateUrl(candidate.downloadUrl),
        expectedBytes = candidate.sizeBytes,
        expectedSha256 = candidate.sha256,
        expectedPackage = packageName,
        installedVersionName = BuildConfig.VERSION_NAME,
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        availableVersionName = candidate.version,
        availableVersionCode = candidate.versionCode,
        destination = AppUpdateDownloader.finalFile(this, candidate).absolutePath,
    )

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
                versionCode <= 0L ||
                !isTrustedUpdateUrl(url) ||
                !sha256.matches(SHA256_PATTERN) ||
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
    state: AppUpdaterState,
    onPrimaryAction: () -> Unit,
    onBack: () -> Unit,
) {
    val action = when {
        state.phase == UpdaterPhase.FAILED -> UpdateAction.RETRY
        state.phase == UpdaterPhase.READY_TO_INSTALL && state.requiresInstallPermission ->
            UpdateAction.ALLOW_INSTALLATION
        else -> UpdateAction.NONE
    }
    val actionRequester = remember { FocusRequester() }
    LaunchedEffect(action) {
        if (action != UpdateAction.NONE) actionRequester.requestFocus()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(text = "Torve $version", style = MaterialTheme.typography.headlineMedium)
                val statusMessage = state.statusMessage()
                Text(
                    text = if (
                        statusMessage == R.string.app_update_resolving ||
                        statusMessage == R.string.app_update_downloading
                    ) {
                        stringResource(statusMessage, version)
                    } else {
                        stringResource(statusMessage)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.phase in ACTIVE_PHASES) {
                    if (state.phase == UpdaterPhase.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (BuildConfig.DEBUG) {
                    Text(
                        text = state.diagnostics.debugSummary(state.phase),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (action != UpdateAction.NONE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onPrimaryAction,
                            modifier = Modifier.focusRequester(actionRequester),
                        ) {
                            Text(
                                stringResource(
                                    if (action == UpdateAction.RETRY) R.string.app_update_retry
                                    else R.string.app_update_allow_installation,
                                ),
                            )
                        }
                        Button(onClick = onBack) {
                            Text(stringResource(R.string.app_update_back))
                        }
                    }
                }
            }
        }
    }
}

private fun AppUpdaterState.statusMessage(): Int = when (phase) {
    UpdaterPhase.IDLE,
    UpdaterPhase.CHECKING,
    UpdaterPhase.UPDATE_AVAILABLE -> R.string.app_update_resolving
    UpdaterPhase.DOWNLOADING -> R.string.app_update_downloading
    UpdaterPhase.VERIFYING_INTEGRITY,
    UpdaterPhase.VERIFYING_PACKAGE,
    UpdaterPhase.VERIFYING_SIGNATURE -> R.string.app_update_verifying
    UpdaterPhase.READY_TO_INSTALL -> if (requiresInstallPermission) {
        R.string.app_update_permission_required
    } else {
        R.string.app_update_verifying
    }
    UpdaterPhase.LAUNCHING_INSTALLER,
    UpdaterPhase.INSTALLER_LAUNCHED -> R.string.app_update_opening_installer
    UpdaterPhase.UP_TO_DATE -> R.string.tv_settings_check_for_updates_up_to_date
    UpdaterPhase.FAILED -> failureReason.failureMessage()
}

internal fun UpdateFailureReason?.failureMessage(): Int = when (this) {
    UpdateFailureReason.NETWORK_ERROR -> R.string.app_update_network_failed
    UpdateFailureReason.HTTP_ERROR -> R.string.app_update_http_failed
    UpdateFailureReason.INVALID_CONTENT -> R.string.app_update_invalid_content
    UpdateFailureReason.DOWNLOAD_INCOMPLETE -> R.string.app_update_download_incomplete
    UpdateFailureReason.INVALID_APK -> R.string.app_update_invalid_apk
    UpdateFailureReason.HASH_MISMATCH -> R.string.app_update_hash_mismatch
    UpdateFailureReason.SIGNATURE_MISMATCH -> R.string.app_update_signature_mismatch
    UpdateFailureReason.PACKAGE_MISMATCH -> R.string.app_update_package_mismatch
    UpdateFailureReason.VERSION_MISMATCH -> R.string.app_update_version_mismatch
    UpdateFailureReason.FILE_PROVIDER_ERROR -> R.string.app_update_file_provider_failed
    UpdateFailureReason.INSTALLER_UNAVAILABLE -> R.string.app_update_installer_failed
    UpdateFailureReason.UNKNOWN,
    null -> R.string.app_update_download_failed
}

private fun Throwable.failureReason(): UpdateFailureReason =
    (this as? UpdateDownloadException)?.reason ?: UpdateFailureReason.UNKNOWN

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

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

internal fun validateApkIdentity(
    downloaded: ApkIdentity,
    installed: ApkIdentity,
    update: AvailableAppUpdate,
): UpdateFailureReason? = validateApkPackageAndVersion(downloaded, installed, update)
    ?: validateApkSigners(downloaded, installed)

private fun validateApkPackageAndVersion(
    downloaded: ApkIdentity,
    installed: ApkIdentity,
    update: AvailableAppUpdate,
): UpdateFailureReason? = when {
    downloaded.packageName != installed.packageName -> UpdateFailureReason.PACKAGE_MISMATCH
    downloaded.versionName != update.version -> UpdateFailureReason.VERSION_MISMATCH
    downloaded.versionCode != update.versionCode || downloaded.versionCode <= installed.versionCode ->
        UpdateFailureReason.VERSION_MISMATCH
    else -> null
}

private fun validateApkSigners(
    downloaded: ApkIdentity,
    installed: ApkIdentity,
): UpdateFailureReason? = when {
    downloaded.signerDigests.isEmpty() || installed.signerDigests.isEmpty() ->
        UpdateFailureReason.SIGNATURE_MISMATCH
    downloaded.signerDigests.intersect(installed.signerDigests).isEmpty() ->
        UpdateFailureReason.SIGNATURE_MISMATCH
    else -> null
}

internal fun hasApkZipMagic(bytes: ByteArray, count: Int = bytes.size): Boolean =
    count >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

internal object AppUpdateDownloader {
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private const val MAX_REDIRECTS = 8
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun finalFile(context: Context, update: AvailableAppUpdate): File =
        File(updateDirectory(context), updateFileBase(update) + ".apk")

    private fun partialFile(context: Context, update: AvailableAppUpdate): File =
        File(updateDirectory(context), updateFileBase(update) + ".apk.part")

    private fun updateDirectory(context: Context): File = File(context.cacheDir, "updates")

    private fun updateFileBase(update: AvailableAppUpdate): String {
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "torve-$safeVersion-${update.versionCode}"
    }

    fun cleanupAttemptFiles(context: Context, update: AvailableAppUpdate) {
        listOf(partialFile(context, update), finalFile(context, update)).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w("TorveUpdate", "event=cleanup_failed path=${file.absolutePath}")
            }
        }
    }

    fun cleanupInstalledUpdateFiles(context: Context) {
        val directory = updateDirectory(context)
        directory.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                file.name.startsWith("torve-") &&
                (file.name.endsWith(".apk") || file.name.endsWith(".apk.part")) &&
                !file.delete()
            ) {
                Log.w("TorveUpdate", "event=post_install_cleanup_failed path=${file.absolutePath}")
            }
        }
        AppUpdateHandoffStore(context).clear()
    }

    suspend fun downloadAndVerify(
        context: Context,
        update: AvailableAppUpdate,
        onAccepted: (String) -> Unit = {},
        onPhase: (UpdaterPhase) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onDiagnostics: (UpdateDiagnostics) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!isTrustedUpdateUrl(update.downloadUrl)) {
            throw UpdateDownloadException(UpdateFailureReason.INVALID_CONTENT, "Untrusted update URL")
        }
        if (update.versionCode <= 0L || update.sizeBytes !in 1..MAX_APK_BYTES) {
            throw UpdateDownloadException(UpdateFailureReason.INVALID_CONTENT, "Invalid update metadata")
        }

        val directory = updateDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw UpdateDownloadException(UpdateFailureReason.UNKNOWN, "Could not create update directory")
        }
        val destination = finalFile(context, update)
        val partial = partialFile(context, update)
        var diagnostics = UpdateDiagnostics(
            requestedUrl = redactUpdateUrl(update.downloadUrl),
            expectedBytes = update.sizeBytes,
            expectedSha256 = update.sha256.lowercase(),
            expectedPackage = context.packageName,
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            availableVersionName = update.version,
            availableVersionCode = update.versionCode,
            destination = destination.absolutePath,
        )
        onDiagnostics(diagnostics)
        logDiagnostics("download_start", diagnostics)

        if (destination.isFile) {
            val cachedResult = verifyArtifact(context, destination, update, diagnostics, onPhase, onDiagnostics)
            if (cachedResult.failure == null) {
                onAccepted(update.downloadUrl)
                onProgress(1f)
                logDiagnostics("cached_update_ready", cachedResult.diagnostics)
                return@withContext destination
            }
            if (!destination.delete()) {
                throw UpdateDownloadException(
                    UpdateFailureReason.INVALID_CONTENT,
                    "Could not discard stale cached update",
                    cachedResult.diagnostics,
                )
            }
        }
        if (partial.exists() && !partial.delete()) {
            throw UpdateDownloadException(UpdateFailureReason.INVALID_CONTENT, "Could not discard partial update")
        }

        try {
            onPhase(UpdaterPhase.DOWNLOADING)
            val connection = openUpdateConnection(update.downloadUrl)
            try {
                val responseCode = connection.responseCode
                val finalUrl = connection.url.toString()
                val contentType = connection.contentType
                val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
                diagnostics = diagnostics.copy(
                    finalUrl = redactUpdateUrl(finalUrl),
                    httpStatus = responseCode,
                    contentType = contentType,
                    declaredBytes = contentLength,
                )
                onDiagnostics(diagnostics)
                logDiagnostics("http_response", diagnostics)
                if (responseCode !in 200..299) {
                    throw UpdateDownloadException(
                        UpdateFailureReason.HTTP_ERROR,
                        "Update returned HTTP $responseCode",
                        diagnostics,
                    )
                }
                if (!isTrustedUpdateUrl(finalUrl)) {
                    throw UpdateDownloadException(
                        UpdateFailureReason.INVALID_CONTENT,
                        "Untrusted redirect",
                        diagnostics,
                    )
                }
                if (contentLength != null && contentLength != update.sizeBytes) {
                    throw UpdateDownloadException(
                        UpdateFailureReason.DOWNLOAD_INCOMPLETE,
                        "Declared update size mismatch",
                        diagnostics,
                    )
                }
                onAccepted(finalUrl)

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    connection.inputStream.buffered().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val header = ByteArray(4)
                            var headerBytes = 0
                            while (headerBytes < header.size) {
                                val count = input.read(header, headerBytes, header.size - headerBytes)
                                if (count < 0) break
                                headerBytes += count
                            }
                            if (!hasApkZipMagic(header, headerBytes)) {
                                throw UpdateDownloadException(
                                    UpdateFailureReason.INVALID_CONTENT,
                                    "Response is not an APK/ZIP",
                                    diagnostics.copy(actualBytes = headerBytes.toLong()),
                                )
                            }
                            digest.update(header, 0, headerBytes)
                            output.write(header, 0, headerBytes)
                            total = headerBytes.toLong()
                            onProgress(total.toFloat() / update.sizeBytes.toFloat())
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > update.sizeBytes || total > MAX_APK_BYTES) {
                                    throw UpdateDownloadException(
                                        UpdateFailureReason.DOWNLOAD_INCOMPLETE,
                                        "Update exceeds declared size",
                                        diagnostics.copy(actualBytes = total),
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
                    throw UpdateDownloadException(
                        UpdateFailureReason.NETWORK_ERROR,
                        "Update download interrupted",
                        diagnostics.copy(actualBytes = total),
                        error,
                    )
                }
                val actualSha256 = digest.digest().toHex()
                diagnostics = diagnostics.copy(actualBytes = total, actualSha256 = actualSha256)
                onDiagnostics(diagnostics)
                logDiagnostics("download_complete", diagnostics)
                if (total != update.sizeBytes) {
                    throw UpdateDownloadException(
                        UpdateFailureReason.DOWNLOAD_INCOMPLETE,
                        "Incomplete update",
                        diagnostics,
                    )
                }
                onPhase(UpdaterPhase.VERIFYING_INTEGRITY)
                if (!actualSha256.equals(update.sha256, ignoreCase = true)) {
                    throw UpdateDownloadException(
                        UpdateFailureReason.HASH_MISMATCH,
                        "Update checksum mismatch",
                        diagnostics,
                    )
                }
            } finally {
                connection.disconnect()
            }

            val verified = verifyArtifact(context, partial, update, diagnostics, onPhase, onDiagnostics)
            diagnostics = verified.diagnostics
            verified.failure?.let { reason ->
                throw UpdateDownloadException(reason, "Downloaded APK verification failed", diagnostics)
            }
            if (destination.exists() && !destination.delete()) {
                throw UpdateDownloadException(
                    UpdateFailureReason.INVALID_CONTENT,
                    "Could not replace cached update",
                    diagnostics,
                )
            }
            if (!partial.renameTo(destination)) {
                throw UpdateDownloadException(
                    UpdateFailureReason.INVALID_CONTENT,
                    "Could not atomically finalize update",
                    diagnostics,
                )
            }
            onProgress(1f)
            logDiagnostics("update_ready", diagnostics)
            destination
        } catch (cancelled: CancellationException) {
            partial.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            if (destination.exists() && (error as? UpdateDownloadException)?.reason != null) {
                destination.delete()
            }
            throw error
        }
    }

    private data class ArtifactVerification(
        val failure: UpdateFailureReason?,
        val diagnostics: UpdateDiagnostics,
    )

    private fun verifyArtifact(
        context: Context,
        apk: File,
        update: AvailableAppUpdate,
        baseDiagnostics: UpdateDiagnostics,
        onPhase: (UpdaterPhase) -> Unit,
        onDiagnostics: (UpdateDiagnostics) -> Unit,
    ): ArtifactVerification {
        if (!apk.isFile || apk.length() != update.sizeBytes) {
            return ArtifactVerification(UpdateFailureReason.DOWNLOAD_INCOMPLETE, baseDiagnostics)
        }
        if (!apk.inputStream().use { input ->
                val header = ByteArray(4)
                hasApkZipMagic(header, input.read(header))
            }
        ) {
            return ArtifactVerification(UpdateFailureReason.INVALID_APK, baseDiagnostics)
        }
        onPhase(UpdaterPhase.VERIFYING_INTEGRITY)
        val actualSha = sha256(apk)
        var diagnostics = baseDiagnostics.copy(actualBytes = apk.length(), actualSha256 = actualSha)
        if (!actualSha.equals(update.sha256, ignoreCase = true)) {
            return ArtifactVerification(UpdateFailureReason.HASH_MISMATCH, diagnostics)
        }

        onPhase(UpdaterPhase.VERIFYING_PACKAGE)
        val archiveInfo = context.packageManager.readArchiveIdentity(apk) ?: return ArtifactVerification(
            UpdateFailureReason.INVALID_APK,
            diagnostics,
        )
        val installedInfo = context.packageManager.readInstalledIdentity(context.packageName)
            ?: return ArtifactVerification(UpdateFailureReason.INVALID_APK, diagnostics)
        diagnostics = diagnostics.copy(
            downloadedPackage = archiveInfo.packageName,
            downloadedVersionName = archiveInfo.versionName,
            downloadedVersionCode = archiveInfo.versionCode,
            installedSignerSha256 = installedInfo.signerDigests,
            downloadedSignerSha256 = archiveInfo.signerDigests,
        )
        onDiagnostics(diagnostics)
        logDiagnostics("package_read", diagnostics)
        val packageFailure = validateApkPackageAndVersion(archiveInfo, installedInfo, update)
        if (packageFailure != null) {
            return ArtifactVerification(packageFailure, diagnostics.copy(failureReason = packageFailure))
        }
        onPhase(UpdaterPhase.VERIFYING_SIGNATURE)
        val signatureFailure = validateApkSigners(archiveInfo, installedInfo)
        return ArtifactVerification(signatureFailure, diagnostics.copy(failureReason = signatureFailure))
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
                throw UpdateDownloadException(UpdateFailureReason.NETWORK_ERROR, "Could not open update URL", cause = error)
            }
            val statusCode = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw UpdateDownloadException(UpdateFailureReason.NETWORK_ERROR, "Update request failed", cause = error)
            }
            if (!isUpdateRedirectStatus(statusCode)) return connection

            val location = connection.getHeaderField("Location")?.trim().orEmpty()
            Log.i("TorveUpdate", "event=redirect status=$statusCode from=${redactUpdateUrl(currentUrl.toString())} " +
                "to=${redactUpdateUrl(location)} count=${redirectCount + 1}")
            connection.disconnect()
            if (location.isBlank()) {
                throw UpdateDownloadException(UpdateFailureReason.HTTP_ERROR, "Update redirect has no destination")
            }
            if (redirectCount >= MAX_REDIRECTS) {
                throw UpdateDownloadException(UpdateFailureReason.HTTP_ERROR, "Update redirect limit exceeded")
            }
            val redirected = runCatching { URL(currentUrl, location) }.getOrElse { error ->
                throw UpdateDownloadException(UpdateFailureReason.HTTP_ERROR, "Invalid update redirect", cause = error)
            }
            if (!isTrustedUpdateUrl(redirected.toString())) {
                throw UpdateDownloadException(UpdateFailureReason.INVALID_CONTENT, "Untrusted update redirect")
            }
            currentUrl = redirected
        }
        throw UpdateDownloadException(UpdateFailureReason.HTTP_ERROR, "Update redirect limit exceeded")
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

    private fun PackageManager.readArchiveIdentity(apk: File): ApkIdentity? {
        // Android 9 only collects archive certificates when GET_SIGNATURES is
        // present, while the public API exposes them through signingInfo. Ask
        // for both on API 28/29 and retain the legacy data as a secure fallback.
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        return getPackageArchiveInfo(apk.absolutePath, flags)?.toIdentity()
    }

    private fun PackageManager.readInstalledIdentity(packageName: String): ApkIdentity? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        return runCatching { getPackageInfo(packageName, flags).toIdentity() }.getOrNull()
    }

    private fun PackageInfo.toIdentity(): ApkIdentity = ApkIdentity(
        packageName = packageName,
        versionName = versionName.orEmpty(),
        versionCode = longVersionCodeCompat(),
        signerDigests = signerDigestsCompat(),
    )

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signerDigestsCompat(): Set<String> {
        @Suppress("DEPRECATION")
        val legacySignatures = signatures.orEmpty()
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo
            when {
                info == null -> legacySignatures
                info.hasMultipleSigners() -> info.apkContentsSigners.orEmpty()
                else -> info.signingCertificateHistory.orEmpty()
            }
        } else {
            legacySignatures
        }
        return resolved.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
    }
}

private fun logDiagnostics(event: String, diagnostics: UpdateDiagnostics) {
    Log.i(
        "TorveUpdate",
        "event=$event installed=${diagnostics.installedVersionName}/${diagnostics.installedVersionCode} " +
            "available=${diagnostics.availableVersionName}/${diagnostics.availableVersionCode} " +
            "manifest=${redactUpdateUrl(diagnostics.manifestUrl)} requested=${diagnostics.requestedUrl} " +
            "final=${diagnostics.finalUrl ?: "-"} http=${diagnostics.httpStatus ?: -1} " +
            "content_type=${diagnostics.contentType ?: "-"} declared_bytes=${diagnostics.declaredBytes ?: -1} " +
            "actual_bytes=${diagnostics.actualBytes} destination=${diagnostics.destination} " +
            "expected_sha=${diagnostics.expectedSha256} actual_sha=${diagnostics.actualSha256 ?: "-"} " +
            "expected_package=${diagnostics.expectedPackage} downloaded_package=${diagnostics.downloadedPackage ?: "-"} " +
            "downloaded_version=${diagnostics.downloadedVersionName ?: "-"}/${diagnostics.downloadedVersionCode ?: -1} " +
            "installed_signers=${diagnostics.installedSignerSha256.sorted().joinToString(",")} " +
            "downloaded_signers=${diagnostics.downloadedSignerSha256.sorted().joinToString(",")} " +
            "failure=${diagnostics.failureReason?.name ?: "-"}",
    )
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val ACTIVE_PHASES = setOf(
    UpdaterPhase.CHECKING,
    UpdaterPhase.DOWNLOADING,
    UpdaterPhase.VERIFYING_INTEGRITY,
    UpdaterPhase.VERIFYING_PACKAGE,
    UpdaterPhase.VERIFYING_SIGNATURE,
    UpdaterPhase.LAUNCHING_INSTALLER,
)

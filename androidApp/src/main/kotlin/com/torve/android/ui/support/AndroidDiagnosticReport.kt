package com.torve.android.ui.support

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.torve.android.BuildConfig
import com.torve.android.diagnostics.AndroidDiagnosticsRecorder
import com.torve.data.auth.AuthClient
import com.torve.data.support.SupportBugReportPayload
import com.torve.domain.diagnostics.BugReportBundleBuilder
import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.TimeZone

private const val BUG_REPORT_PREFS = "torve_pending_bug_reports"
private const val KEY_PENDING_TV_REPORT = "pending_tv_report"

internal suspend fun buildAndroidFullBugReportPayload(
    context: Context,
    settingsState: SettingsUiState,
    providerEntries: List<ProviderHealthEntry>,
    transferSnapshot: TransferDiagnosticsSnapshot?,
    issueType: String,
    userDescription: String?,
    includeDiagnostics: Boolean,
    authClient: AuthClient?,
    subscriptionRepository: SubscriptionRepository?,
    addonRepository: AddonRepository?,
): SupportBugReportPayload {
    val now = System.currentTimeMillis()
    val versionParts = androidBugReportVersionParts(context)
    val platform = androidBugReportPlatformLabel()
    val store = androidDistributionChannel()
    val user = runCatching { authClient?.getCurrentUser() }.getOrNull()
    val signedIn = runCatching { authClient?.isLoggedIn() }.getOrNull()
        ?: (user != null)
    val activeSubscription = runCatching { subscriptionRepository?.getActiveSubscription() }.getOrNull()
    val premiumAccess = runCatching { subscriptionRepository?.hasLocallyVerifiedPremiumAccess() }.getOrNull()
    val installedAddons = runCatching { addonRepository?.getInstalledAddons() }.getOrNull().orEmpty()
    val enabledAddons = runCatching { addonRepository?.getEnabledAddons() }.getOrNull().orEmpty()

    val device = buildDeviceObject(context)
    val diagnostics = if (includeDiagnostics) {
        buildDiagnosticsObject(
            context = context,
            settingsState = settingsState,
            providerEntries = providerEntries,
            transferSnapshot = transferSnapshot,
            versionParts = versionParts,
            store = store,
            signedIn = signedIn,
            verifiedKnownByClient = user?.isVerified,
            accessTier = activeSubscription?.tier?.name ?: "FREE",
            hasPremiumAccess = premiumAccess,
            deviceActivationState = when {
                premiumAccess == true -> "active"
                activeSubscription?.isPro == true -> "premium_on_account_or_stale_local_state"
                signedIn -> "none_or_unknown"
                else -> "signed_out"
            },
            installationIdPresent = true,
            installedAddonCount = installedAddons.size,
            enabledAddonCount = enabledAddons.size,
            now = now,
        )
    } else {
        minimalDiagnosticsObject(now)
    }
    val logs = AndroidDiagnosticsRecorder.logsForReport()
        .map { DiagnosticsRedactor.redact(it) }
        .takeLast(1_200)
    val summary = buildMarkdownSummary(
        issueType = issueType,
        userDescription = userDescription.orEmpty(),
        platform = platform,
        appVersion = versionParts.appVersion,
        diagnostics = diagnostics,
        logs = logs,
        now = now,
    )
    return SupportBugReportPayload(
        issueType = issueType,
        message = userDescription?.takeIf { it.isNotBlank() }?.let(DiagnosticsRedactor::redact),
        report = summary,
        platform = platform,
        appVersion = versionParts.appVersion,
        buildNumber = versionParts.versionCode,
        distributionChannel = store,
        device = device,
        diagnostics = diagnostics,
        logs = logs,
    )
}

internal fun savePendingTvBugReport(context: Context, payload: SupportBugReportPayload) {
    val encoded = Json.encodeToString(payload)
    context.getSharedPreferences(BUG_REPORT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PENDING_TV_REPORT, encoded)
        .apply()
}

internal fun loadPendingTvBugReport(context: Context): SupportBugReportPayload? {
    val raw = context.getSharedPreferences(BUG_REPORT_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PENDING_TV_REPORT, null)
        ?: return null
    return runCatching { Json.decodeFromString<SupportBugReportPayload>(raw) }.getOrNull()
}

internal fun clearPendingTvBugReport(context: Context) {
    context.getSharedPreferences(BUG_REPORT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_PENDING_TV_REPORT)
        .apply()
}

internal data class AndroidBugReportVersionParts(
    val versionName: String,
    val versionCode: String,
    val appVersion: String,
)

internal fun androidBugReportVersionParts(context: Context): AndroidBugReportVersionParts {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "unknown"
    @Suppress("DEPRECATION")
    val versionCode = packageInfo?.longVersionCode?.toString() ?: "unknown"
    return AndroidBugReportVersionParts(
        versionName = versionName,
        versionCode = versionCode,
        appVersion = "$versionName ($versionCode)",
    )
}

private fun buildDeviceObject(context: Context): JsonObject {
    val resources = context.resources
    val metrics = resources.displayMetrics
    val config = resources.configuration
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    val statFs = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
    val isTv = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val hdrKnown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() }.getOrNull()
    } else {
        null
    }
    return buildJsonObject {
        put("platform", "Android")
        put("manufacturer", safe(Build.MANUFACTURER))
        put("brand", safe(Build.BRAND))
        put("model", safe(Build.MODEL))
        put("device", safe(Build.DEVICE))
        put("product", safe(Build.PRODUCT))
        put("hardware", safe(Build.HARDWARE))
        put("os", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        put("sdk", Build.VERSION.SDK_INT)
        put("locale", Locale.getDefault().toLanguageTag())
        put("timezone", TimeZone.getDefault().id)
        put("screen", buildJsonObject {
            put("widthPx", metrics.widthPixels)
            put("heightPx", metrics.heightPixels)
            put("density", metrics.density.toDouble())
            put("fontScale", config.fontScale.toDouble())
            put("uiMode", if (isTv) "tv" else "mobile")
            putNullable("hdr", hdrKnown)
        })
        put("memory", buildJsonObject {
            put("availableMb", bytesToMb(memoryInfo.availMem))
            put("totalMb", bytesToMb(memoryInfo.totalMem))
            put("lowMemory", memoryInfo.lowMemory)
        })
        put("storage", buildJsonObject {
            put("availableMb", bytesToMb(statFs?.availableBytes ?: 0L))
            put("totalMb", bytesToMb(statFs?.totalBytes ?: 0L))
        })
    }
}

private fun buildDiagnosticsObject(
    context: Context,
    settingsState: SettingsUiState,
    providerEntries: List<ProviderHealthEntry>,
    transferSnapshot: TransferDiagnosticsSnapshot?,
    versionParts: AndroidBugReportVersionParts,
    store: String,
    signedIn: Boolean,
    verifiedKnownByClient: Boolean?,
    accessTier: String,
    hasPremiumAccess: Boolean?,
    deviceActivationState: String,
    installationIdPresent: Boolean,
    installedAddonCount: Int,
    enabledAddonCount: Int,
    now: Long,
): JsonObject {
    return buildJsonObject {
        put("app", buildJsonObject {
            put("versionName", versionParts.versionName)
            put("versionCode", versionParts.versionCode)
            put("applicationId", BuildConfig.APPLICATION_ID)
            put("store", store)
            put("installerPackage", safe(installerPackage(context) ?: "unknown"))
            put("buildType", BuildConfig.BUILD_TYPE)
            put("activePlayerEngine", "ExoPlayer")
            put("startupTimeMs", SystemClock.elapsedRealtime() - AndroidDiagnosticsRecorder.processStartedElapsedMs)
            put("currentScreen", AndroidDiagnosticsRecorder.currentScreen)
            put("previousScreen", AndroidDiagnosticsRecorder.previousScreen)
            put("sessionId", AndroidDiagnosticsRecorder.sessionId)
            put("sessionStartedAtEpochMs", AndroidDiagnosticsRecorder.sessionStartedAtEpochMs)
            put("reportGeneratedAtEpochMs", now)
        })
        put("account", buildJsonObject {
            put("signedIn", signedIn)
            putNullable("verifiedKnownByClient", verifiedKnownByClient)
            put("accessTier", accessTier)
            putNullable("hasPremiumAccess", hasPremiumAccess)
            put("deviceActivationState", deviceActivationState)
            put("installationIdPresent", installationIdPresent)
        })
        put("network", buildNetworkObject(context))
        put("playback", buildPlaybackObject())
        put("focus", AndroidDiagnosticsRecorder.focusJson())
        put("performance", buildPerformanceObject())
        put("crashes", AndroidDiagnosticsRecorder.crashesJson(context))
        put("integrations", buildIntegrationsObject(settingsState, providerEntries, transferSnapshot))
        put("addons", buildAddonsObject(installedAddonCount, enabledAddonCount, providerEntries))
        put("recentActions", AndroidDiagnosticsRecorder.recentActionsJson())
    }
}

private fun minimalDiagnosticsObject(now: Long): JsonObject = buildJsonObject {
    put("app", buildJsonObject { put("reportGeneratedAtEpochMs", now) })
    put("network", buildJsonObject { put("connected", false) })
    put("integrations", JsonObject(emptyMap()))
    put("addons", JsonObject(emptyMap()))
    put("performance", JsonObject(emptyMap()))
    put("focus", JsonObject(emptyMap()))
    put("playback", JsonObject(emptyMap()))
}

private fun buildNetworkObject(context: Context): JsonObject {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
    val connected = capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val transport = when {
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
        else -> "unknown"
    }
    val proxyHost = System.getProperty("http.proxyHost").orEmpty()
    return buildJsonObject {
        put("connected", connected)
        put("transport", transport)
        put("metered", manager?.isActiveNetworkMetered ?: false)
        putNullable("vpnActive", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        putNullable("proxyActive", proxyHost.isNotBlank())
        put("backendReachable", connected)
        put("backendLatencyMs", JsonNull)
        put("lastHttpFailures", AndroidDiagnosticsRecorder.networkFailuresJson())
    }
}

private fun buildPlaybackObject(): JsonObject = buildJsonObject {
    put("lastPlaybackAttempt", buildJsonObject {
        put("contentType", "unknown")
        put("providerCategory", "unknown")
        put("sourceCategory", "unknown")
        put("playerEngine", "ExoPlayer")
        put("startedAtEpochMs", JsonNull)
        put("startupLatencyMs", JsonNull)
        put("bufferCount", 0)
        put("totalBufferMs", 0)
        put("droppedFrames", JsonNull)
        put("errorCode", JsonNull)
        put("errorMessage", JsonNull)
    })
    put("recentPlaybackErrors", AndroidDiagnosticsRecorder.recentPlaybackErrorsJson())
}

private fun buildPerformanceObject(): JsonObject = buildJsonObject {
    put("lastKnownFps", JsonNull)
    put("slowFrames", 0)
    put("frozenFrameEvents", 0)
    put("mainThreadStallEvents", JsonArray(emptyList()))
    put("memoryWarnings", JsonArray(emptyList()))
    put("appNotRespondingSuspected", false)
}

private fun buildIntegrationsObject(
    state: SettingsUiState,
    providerEntries: List<ProviderHealthEntry>,
    transferSnapshot: TransferDiagnosticsSnapshot?,
): JsonObject = buildJsonObject {
    put("realDebrid", providerStatus(providerEntries.findDebrid("real")))
    put("allDebrid", providerStatus(providerEntries.findDebrid("all")))
    put("premiumize", providerStatus(providerEntries.findDebrid("premiumize")))
    put("torbox", providerStatus(providerEntries.findDebrid("torbox")))
    put("plexJellyfin", aggregateProviderStatus(providerEntries, ProviderHealthCategory.PLEX_JELLYFIN))
    put("stremioAddons", buildJsonObject {
        val entry = providerEntries.firstOrNull { it.category == ProviderHealthCategory.ADDON }
        copyProviderStatus(entry)
        put("manifestCount", if (state.hasStreamAddon) 1 else 0)
        put("reachableCount", if (entry?.status == ProviderHealthStatus.GREEN) 1 else 0)
        put("failedManifests", JsonArray(emptyList()))
    })
    put("iptvPlaylist", aggregateProviderStatus(providerEntries, ProviderHealthCategory.IPTV))
    put("iptvEpg", aggregateProviderStatus(providerEntries, ProviderHealthCategory.EPG))
    put("trakt", buildJsonObject {
        copyProviderStatus(providerEntries.firstOrNull { it.category == ProviderHealthCategory.TRAKT })
        put("clientConnectedFlag", state.traktConnected)
        put("verifiedProfileKnown", state.traktUser != null)
    })
    put("simkl", buildJsonObject {
        copyProviderStatus(providerEntries.firstOrNull { it.category == ProviderHealthCategory.SIMKL })
        put("clientConnectedFlag", state.simklConnected)
        put("verifiedProfileKnown", state.simklUser != null)
    })
    put("usenetIndexer", aggregateProviderStatus(providerEntries, ProviderHealthCategory.USENET_INDEXER))
    put("usenetProvider", aggregateProviderStatus(providerEntries, ProviderHealthCategory.USENET_PROVIDER))
    put("downloadClient", aggregateProviderStatus(providerEntries, ProviderHealthCategory.DOWNLOAD_CLIENT))
    put("credentialTransfer", buildJsonObject {
        putNullable("cryptoEngineAvailable", transferSnapshot?.cryptoEngineAvailable)
        putNullable("signedIn", transferSnapshot?.signedIn)
        put("relayReachable", transferSnapshot?.relayReachable?.name ?: "UNKNOWN")
    })
}

private fun buildAddonsObject(
    installedCount: Int,
    enabledCount: Int,
    providerEntries: List<ProviderHealthEntry>,
): JsonObject = buildJsonObject {
    put("installedCount", installedCount)
    put("enabledCount", enabledCount)
    val addonEntry = providerEntries.firstOrNull { it.category == ProviderHealthCategory.ADDON }
    val manifestFailures = if (addonEntry?.status == ProviderHealthStatus.RED || addonEntry?.status == ProviderHealthStatus.YELLOW) {
        JsonArray(
            listOf(
                buildJsonObject {
                    put("host", "unknown")
                    put("status", addonEntry.status.name)
                    putNullable("errorClass", addonEntry.message)
                    put("latencyMs", JsonNull)
                },
            ),
        )
    } else {
        JsonArray(emptyList())
    }
    put("recentManifestFailures", manifestFailures)
    put("recentCatalogFailures", JsonArray(emptyList()))
    put("recentResolveFailures", JsonArray(emptyList()))
}

private fun buildMarkdownSummary(
    issueType: String,
    userDescription: String,
    platform: String,
    appVersion: String,
    diagnostics: JsonObject,
    logs: List<String>,
    now: Long,
): String {
    val compactDiagnostics = buildString {
        appendLine("platform: $platform")
        appendLine("appVersion: $appVersion")
        appendLine("diagnostics.app: ${diagnostics["app"]}")
        appendLine("diagnostics.network: ${diagnostics["network"]}")
        appendLine("diagnostics.integrations: ${diagnostics["integrations"]}")
        appendLine("diagnostics.addons: ${diagnostics["addons"]}")
        appendLine("diagnostics.performance: ${diagnostics["performance"]}")
        appendLine("diagnostics.focus: ${diagnostics["focus"]}")
        appendLine("diagnostics.playback: ${diagnostics["playback"]}")
        appendLine("logs.attached: ${logs.size}")
    }
    return BugReportBundleBuilder.build(
        issueType = issueType,
        userDescription = userDescription,
        pastedLogs = logs.takeLast(80).joinToString(separator = "\n"),
        diagnosticsBundle = compactDiagnostics,
        nowEpochMs = now,
    )
}

private fun List<ProviderHealthEntry>.findDebrid(token: String): ProviderHealthEntry? {
    val normalized = token.lowercase()
    return firstOrNull {
        it.category == ProviderHealthCategory.DEBRID &&
            (it.providerKey.lowercase().contains(normalized) || it.label.lowercase().contains(normalized))
    }
}

private fun providerStatus(entry: ProviderHealthEntry?): JsonObject = buildJsonObject {
    copyProviderStatus(entry)
}

private fun aggregateProviderStatus(
    entries: List<ProviderHealthEntry>,
    category: ProviderHealthCategory,
): JsonObject {
    val rows = entries.filter { it.category == category }
    val worst = rows.maxByOrNull { providerStatusRank(it.status) }
    return buildJsonObject {
        copyProviderStatus(worst)
        put("entryCount", rows.size)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.copyProviderStatus(entry: ProviderHealthEntry?) {
    put("status", entry?.status?.name ?: ProviderHealthStatus.UNCONFIGURED.name)
    putNullable("lastCheckedEpochMs", entry?.lastCheckedAt)
    putNullable("message", entry?.message?.let(DiagnosticsRedactor::redact))
    putNullable("nextAction", entry?.nextAction?.let(DiagnosticsRedactor::redact))
}

private fun providerStatusRank(status: ProviderHealthStatus): Int = when (status) {
    ProviderHealthStatus.RED -> 5
    ProviderHealthStatus.YELLOW -> 4
    ProviderHealthStatus.UNKNOWN -> 3
    ProviderHealthStatus.GREEN -> 2
    ProviderHealthStatus.UNCONFIGURED -> 1
}

private fun installerPackage(context: Context): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}

private fun bytesToMb(bytes: Long): Long = (bytes / (1024L * 1024L)).coerceAtLeast(0L)

private fun androidDistributionChannel(): String = when {
    BuildConfig.FLAVOR_store.equals("amazon", ignoreCase = true) -> "amazon"
    BuildConfig.FLAVOR_store.equals("google", ignoreCase = true) -> "google_play"
    BuildConfig.FLAVOR_store.isBlank() -> "unknown"
    else -> "direct"
}

private fun safe(value: String): String = DiagnosticsRedactor.redact(value).take(2_000)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let { JsonPrimitive(safe(it)) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Boolean?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) {
    put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: JsonElement?) {
    put(key, value ?: JsonNull)
}

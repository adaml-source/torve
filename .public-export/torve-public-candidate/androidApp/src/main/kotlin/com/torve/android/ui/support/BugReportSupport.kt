package com.torve.android.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.domain.diagnostics.BugReportBundleBuilder
import com.torve.domain.diagnostics.DiagnosticsBundleBuilder
import com.torve.presentation.legal.LegalUrls
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import java.util.Locale

internal const val TORVE_SUPPORT_EMAIL: String = LegalUrls.SUPPORT_EMAIL

internal fun androidBugReportPlatformLabel(): String =
    if (BuildConfig.FLAVOR_formFactor.equals("tv", ignoreCase = true)) {
        when {
            BuildConfig.FLAVOR_store.equals("amazon", ignoreCase = true) -> "Fire TV"
            BuildConfig.FLAVOR_store.equals("google", ignoreCase = true) -> "Google TV"
            else -> "Android TV"
        }
    } else {
        "Android Mobile"
    }

internal fun androidBugReportAppVersion(context: Context): String {
    return androidBugReportVersionParts(context).appVersion
}

internal fun buildAndroidBugReport(
    context: Context,
    settingsState: SettingsUiState,
    providerEntries: List<ProviderHealthEntry>,
    transferSnapshot: TransferDiagnosticsSnapshot?,
    issueType: String,
    userDescription: String,
    pastedLogs: String,
    includeDiagnostics: Boolean,
): String {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "unknown"
    @Suppress("DEPRECATION")
    val versionCode = packageInfo?.longVersionCode?.toString() ?: "unknown"
    val diagnostics = if (includeDiagnostics) {
        DiagnosticsBundleBuilder.build(
            app = DiagnosticsBundleBuilder.AppInfo(
                versionName = versionName,
                versionCode = versionCode,
                storeFlavor = BuildConfig.FLAVOR_store.ifBlank { "unknown" },
                activeEngineId = "ExoPlayer",
            ),
            device = DiagnosticsBundleBuilder.DeviceInfo(
                platform = "Android",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersion = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
                locale = Locale.getDefault().toLanguageTag(),
            ),
            account = null,
            providerEntries = providerEntries,
            transfer = transferSnapshot,
            nowEpochMs = System.currentTimeMillis(),
        )
    } else {
        null
    }
    return BugReportBundleBuilder.build(
        issueType = issueType,
        userDescription = userDescription,
        pastedLogs = pastedLogs,
        diagnosticsBundle = diagnostics,
        nowEpochMs = System.currentTimeMillis(),
    )
}

internal fun copyBugReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Torve bug report", report))
    Toast.makeText(context, context.getString(R.string.bug_report_copied), Toast.LENGTH_SHORT).show()
}

internal fun shareBugReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.bug_report_email_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.bug_report_share)))
}

internal fun emailBugReport(context: Context, report: String) {
    val mailto = android.net.Uri.fromParts("mailto", TORVE_SUPPORT_EMAIL, null)
    val emailIntent = Intent(Intent.ACTION_SENDTO, mailto).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(TORVE_SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.bug_report_email_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching {
        context.startActivity(Intent.createChooser(emailIntent, context.getString(R.string.bug_report_send_email)))
    }.onFailure {
        shareBugReport(context, report)
    }
}

package com.torve.android.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.torve.android.BuildConfig
import com.torve.domain.security.ClientIntegrityAttestation
import com.torve.domain.security.ClientTrustSignal
import com.torve.domain.security.ClientTrustSignalProvider
import java.security.MessageDigest
import java.security.SecureRandom

class AndroidClientTrustSignalProvider(
    private val context: Context,
    private val integrityTokenProvider: ClientIntegrityTokenProvider,
) : ClientTrustSignalProvider {

    override suspend fun currentSignal(includeIntegrityToken: Boolean): ClientTrustSignal {
        val flavor = BuildConfig.FLAVOR
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val flavorLower = flavor.lowercase()
        return ClientTrustSignal(
            platform = when {
                flavorLower.contains("amazon") && isTv -> "amazon_tv"
                flavorLower.contains("amazon") -> "android"
                isTv -> "android_tv"
                else -> "android"
            },
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            flavor = flavor,
            distributionChannel = when {
                flavorLower.contains("amazon") && isTv -> "amazon_sideload"
                flavorLower.contains("amazon") -> "amazon_appstore"
                flavorLower.contains("google") -> "google_play"
                else -> flavor.ifBlank { null }
            },
            packageName = context.packageName,
            installerPackage = installerPackageName(),
            signingCertificateSha256 = signingCertificateSha256(),
            isDebuggable = isDebuggable(),
            isEmulator = isLikelyEmulator(),
            hasKnownHookingIndicators = hasKnownHookingIndicators(),
            hasKnownRootIndicators = hasKnownRootIndicators(),
            integrityProvider = integrityTokenProvider.providerName,
        )
    }

    override suspend fun currentIntegrityAttestation(): ClientIntegrityAttestation? {
        val nonce = randomNonce()
        val token = integrityTokenProvider.requestIntegrityToken(nonce)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return ClientIntegrityAttestation(
            integrityProvider = integrityTokenProvider.providerName,
            integrityToken = token,
            nonce = nonce,
            generatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Suppress("DEPRECATION")
    private fun installerPackageName(): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256(): String? {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
            } else {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures
            }.orEmpty()
            val first = signatures.firstOrNull() ?: return@runCatching null
            MessageDigest.getInstance("SHA-256")
                .digest(first.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }.getOrNull()
    }

    private fun isLikelyEmulator(): Boolean {
        val checks = listOf(
            Build.FINGERPRINT,
            Build.MODEL,
            Build.MANUFACTURER,
            Build.BRAND,
            Build.DEVICE,
            Build.PRODUCT,
            Build.HARDWARE,
        ).joinToString("|").lowercase()
        return listOf("generic", "emulator", "sdk", "goldfish", "ranchu", "genymotion")
            .any { it in checks }
    }

    private fun hasKnownHookingIndicators(): Boolean {
        val packageNames = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.saurik.substrate",
            "re.frida.server",
        )
        return packageNames.any { isPackageInstalled(it) }
    }

    private fun hasKnownRootIndicators(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/bin/.ext/.su",
        )
        return suPaths.any { path -> java.io.File(path).exists() } ||
            listOf(
                "com.topjohnwu.magisk",
                "eu.chainfire.supersu",
                "com.noshufou.android.su",
            ).any { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun randomNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

package com.torve.android.update

import com.torve.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVerificationTest {
    private val update = AvailableAppUpdate(
        version = "1.2.6",
        downloadUrl = "https://torve.app/downloads/android/torve.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 100L,
        versionCode = 20_122L,
    )
    private val installed = ApkIdentity(
        packageName = "com.torve.app.amazon",
        versionName = "1.2.5",
        versionCode = 20_121L,
        signerDigests = setOf("production-cert"),
    )

    @Test
    fun validUpgradeRequiresMatchingPackageVersionCodeVersionNameAndSigner() {
        assertNull(validateApkIdentity(validDownloaded(), installed, update))
    }

    @Test
    fun certificateMismatchFailsClosed() {
        assertEquals(
            UpdateFailureReason.SIGNATURE_MISMATCH,
            validateApkIdentity(
                validDownloaded().copy(signerDigests = setOf("unexpected-cert")),
                installed,
                update,
            ),
        )
    }

    @Test
    fun missingArchiveCertificateFailsClosed() {
        assertEquals(
            UpdateFailureReason.SIGNATURE_MISMATCH,
            validateApkIdentity(validDownloaded().copy(signerDigests = emptySet()), installed, update),
        )
    }

    @Test
    fun signingHistoryAllowsSupportedKeyLineageIntersection() {
        assertNull(
            validateApkIdentity(
                validDownloaded().copy(signerDigests = setOf("next-cert", "production-cert")),
                installed.copy(signerDigests = setOf("production-cert", "old-cert")),
                update,
            ),
        )
    }

    @Test
    fun packageMismatchHasItsOwnTypedFailure() {
        assertEquals(
            UpdateFailureReason.PACKAGE_MISMATCH,
            validateApkIdentity(
                validDownloaded().copy(packageName = "com.example.not.torve"),
                installed,
                update,
            ),
        )
    }

    @Test
    fun versionNameMismatchHasItsOwnTypedFailure() {
        assertEquals(
            UpdateFailureReason.VERSION_MISMATCH,
            validateApkIdentity(validDownloaded().copy(versionName = "1.2.7"), installed, update),
        )
    }

    @Test
    fun metadataVersionCodeMustEqualDownloadedApk() {
        assertEquals(
            UpdateFailureReason.VERSION_MISMATCH,
            validateApkIdentity(validDownloaded().copy(versionCode = 20_123L), installed, update),
        )
    }

    @Test
    fun downloadedVersionCodeMustBeNewerThanInstalled() {
        val sameBuild = update.copy(versionCode = installed.versionCode)
        assertEquals(
            UpdateFailureReason.VERSION_MISMATCH,
            validateApkIdentity(
                validDownloaded().copy(versionCode = installed.versionCode),
                installed,
                sameBuild,
            ),
        )
    }

    @Test
    fun apkZipMagicAcceptsApkAndRejectsHtmlJsonEmptyAndTruncatedBodies() {
        assertTrue(hasApkZipMagic(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertEquals(false, hasApkZipMagic("<html".encodeToByteArray()))
        assertEquals(false, hasApkZipMagic("{\"error\"".encodeToByteArray()))
        assertEquals(false, hasApkZipMagic(byteArrayOf()))
        assertEquals(false, hasApkZipMagic(byteArrayOf(0x50, 0x4b), count = 2))
    }

    @Test
    fun everySecurityFailureUsesASeparateUserFacingMessage() {
        val reasons = listOf(
            UpdateFailureReason.INVALID_CONTENT,
            UpdateFailureReason.DOWNLOAD_INCOMPLETE,
            UpdateFailureReason.INVALID_APK,
            UpdateFailureReason.HASH_MISMATCH,
            UpdateFailureReason.SIGNATURE_MISMATCH,
            UpdateFailureReason.PACKAGE_MISMATCH,
            UpdateFailureReason.VERSION_MISMATCH,
            UpdateFailureReason.FILE_PROVIDER_ERROR,
            UpdateFailureReason.INSTALLER_UNAVAILABLE,
        )
        assertEquals(reasons.size, reasons.map { it.failureMessage() }.toSet().size)
        assertEquals(R.string.app_update_hash_mismatch, UpdateFailureReason.HASH_MISMATCH.failureMessage())
        assertEquals(
            R.string.app_update_signature_mismatch,
            UpdateFailureReason.SIGNATURE_MISMATCH.failureMessage(),
        )
    }

    @Test
    fun updaterStateModelContainsEveryRequiredTerminalAndVerificationPhase() {
        assertEquals(
            setOf(
                "IDLE",
                "CHECKING",
                "UPDATE_AVAILABLE",
                "DOWNLOADING",
                "VERIFYING_INTEGRITY",
                "VERIFYING_PACKAGE",
                "VERIFYING_SIGNATURE",
                "READY_TO_INSTALL",
                "LAUNCHING_INSTALLER",
                "INSTALLER_LAUNCHED",
                "UP_TO_DATE",
                "FAILED",
            ),
            UpdaterPhase.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun debugDiagnosticsExposePrefixesAndTypedReasonWithoutUrlSecrets() {
        val diagnostics = UpdateDiagnostics(
            requestedUrl = redactUpdateUrl(update.downloadUrl + "?token=secret"),
            expectedBytes = 100,
            actualBytes = 50,
            expectedSha256 = "a".repeat(64),
            actualSha256 = "b".repeat(64),
            failureReason = UpdateFailureReason.HASH_MISMATCH,
        )
        val text = diagnostics.debugSummary(UpdaterPhase.FAILED)
        assertTrue(text.contains("state=FAILED"))
        assertTrue(text.contains("failure=HASH_MISMATCH"))
        assertTrue(!diagnostics.requestedUrl.contains("secret"))
    }

    private fun validDownloaded() = ApkIdentity(
        packageName = installed.packageName,
        versionName = update.version,
        versionCode = update.versionCode,
        signerDigests = installed.signerDigests,
    )
}

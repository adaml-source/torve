package com.torve.data.addon

import kotlin.test.*

class ExtensionUpgradePolicyTest {
    @Test fun androidUpgradePreservesLocalInstallsWithoutAllowingAccountRestores() {
        val previousRestore = ExtensionRuntimePolicy.restorePandaFromAccount
        val previousLocal = ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls
        try {
            ExtensionRuntimePolicy.restorePandaFromAccount = false
            ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls = true
            val url = "https://panda.torve.app/u/configured/manifest.json"
            assertTrue(permitsLocalExtension("com.torve.panda", url, "app"))
            assertTrue(permitsLocalExtension("com.torve.panda", url, DEVICE_MANIFEST_INSTALL))
            assertFalse(permitsLocalExtension("com.torve.panda", url, "sync"))
            assertFalse(permitsLocalExtension("com.torve.panda", url, "web"))
            ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls = false
            assertFalse(permitsLocalExtension("com.torve.panda", url, "app"))
        } finally {
            ExtensionRuntimePolicy.restorePandaFromAccount = previousRestore
            ExtensionRuntimePolicy.preserveLegacyLocalAppInstalls = previousLocal
        }
    }
}

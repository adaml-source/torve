package com.torve.desktop.security

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DesktopSecureSecretStoreTest {

    @Test
    fun `windows dpapi cipher round-trips on Windows hosts`() {
        if (!System.getProperty("os.name", "").contains("win", ignoreCase = true)) return

        val cipher = WindowsDpapiSecretCipher()
        val protected = cipher.protect("dpapi-secret".toByteArray(StandardCharsets.UTF_8))
        val restored = cipher.unprotect(protected)

        assertEquals("dpapi-secret", String(restored, StandardCharsets.UTF_8))
    }

    @Test
    fun `secure store encrypts values at rest and round-trips secure storage strings`() = runBlocking {
        val dir = tempDir()
        val secureFile = File(dir, SECURE_DESKTOP_SECRET_FILE_NAME)
        val store = secureStore(dir)

        store.putString("refresh_token", "refresh-secret-123")

        assertEquals("refresh-secret-123", store.getString("refresh_token"))
        val raw = secureFile.readText()
        assertTrue(raw.contains("dpapi-v1"))
        assertFalse(raw.contains("refresh-secret-123"))
    }

    @Test
    fun `secure store supports integration subkeys modes and clearAllSecrets`() = runBlocking {
        val dir = tempDir()
        val store = secureStore(dir)

        store.put(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN, "panda-secret", subKey = "cfg-1")
        store.putString("refresh_token", "refresh-secret")
        store.setStorageMode(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN, IntegrationStorageMode.ACCOUNT)

        assertEquals("panda-secret", store.get(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN, subKey = "cfg-1"))
        assertEquals(listOf("cfg-1"), store.getSubKeys(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN))
        assertEquals(IntegrationStorageMode.ACCOUNT, store.getStorageMode(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN))

        store.clearAllSecrets()

        assertNull(store.get(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN, subKey = "cfg-1"))
        assertEquals(IntegrationStorageMode.DEVICE_ONLY, store.getStorageMode(IntegrationSecretKey.PANDA_MANAGEMENT_TOKEN))
        assertEquals("refresh-secret", store.getString("refresh_token"))
    }

    @Test
    fun `secure store migrates plaintext fallback file and removes raw secrets`() = runBlocking {
        val dir = tempDir()
        val insecureFile = File(dir, INSECURE_DESKTOP_SECRET_FILE_NAME)
        Properties().apply {
            setProperty("refresh_token", "old-refresh-secret")
            setProperty(IntegrationSecretKey.OMDB_API_KEY.name, "omdb-secret")
            setProperty("${IntegrationSecretKey.OMDB_API_KEY.name}_mode", IntegrationStorageMode.ACCOUNT.name)
        }.store(insecureFile.outputStream(), "legacy")

        val store = secureStore(dir)

        assertEquals("old-refresh-secret", store.getString("refresh_token"))
        assertEquals("omdb-secret", store.get(IntegrationSecretKey.OMDB_API_KEY))
        assertEquals(IntegrationStorageMode.ACCOUNT, store.getStorageMode(IntegrationSecretKey.OMDB_API_KEY))
        assertFalse(insecureFile.exists() && insecureFile.length() > 0L)

        val raw = File(dir, SECURE_DESKTOP_SECRET_FILE_NAME).readText()
        assertFalse(raw.contains("old-refresh-secret"))
        assertFalse(raw.contains("omdb-secret"))
    }

    @Test
    fun `windows factory refuses insecure fallback unless explicitly allowed`() {
        val dir = tempDir()
        assertFailsWith<IllegalStateException> {
            createDesktopSecretStore(
                platform = "windows",
                allowInsecureFallback = false,
                secureFactory = { error("dpapi unavailable") },
                insecureFactory = { InsecureDesktopFileSecretStore(File(dir, INSECURE_DESKTOP_SECRET_FILE_NAME)) },
            )
        }

        val fallback = createDesktopSecretStore(
            platform = "windows",
            allowInsecureFallback = true,
            secureFactory = { error("dpapi unavailable") },
            insecureFactory = { InsecureDesktopFileSecretStore(File(dir, INSECURE_DESKTOP_SECRET_FILE_NAME)) },
        )

        assertIs<InsecureDesktopFileSecretStore>(fallback)
    }

    private fun secureStore(dir: File): DesktopSecureSecretStore =
        DesktopSecureSecretStore(
            cipher = XorCipher,
            storeFile = File(dir, SECURE_DESKTOP_SECRET_FILE_NAME),
            insecureStoreFile = File(dir, INSECURE_DESKTOP_SECRET_FILE_NAME),
        )

    private fun tempDir(): File =
        Files.createTempDirectory("torve-desktop-secrets-").toFile().apply {
            deleteOnExit()
        }

    private object XorCipher : DesktopSecretCipher {
        override fun protect(plaintext: ByteArray): ByteArray =
            plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun unprotect(ciphertext: ByteArray): ByteArray = protect(ciphertext)
    }
}

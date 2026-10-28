package com.torve.data.addon

import kotlin.test.*

class ExtensionLinkDecoderTest {
    private fun decode(text: String) = ExtensionLinkDecoder.decode(text.encodeToByteArray(), "https://provider.example/manifest.json")
    @Test fun manifestSupportsNamedAndObjectResources() {
        val d = decode("""{"id":"test","name":"Test","version":"1","resources":["stream",{"name":"subtitles"}]}""")
        assertEquals(listOf("Playback", "Subtitles"), d.entries.single().capabilities)
        assertFalse(d.isCollection)
    }
    @Test fun collectionResolvesRelativeLinksAndDeduplicates() {
        val d = decode("""{"schemaVersion":1,"extensions":[{"manifestUrl":"/a/manifest.json"},{"manifestUrl":"https://provider.example/a/manifest.json"}]}""")
        assertEquals("https://provider.example/a/manifest.json", d.entries.single().manifestUrl)
        assertTrue(d.isCollection)
    }
    @Test fun collectionRequiresSupportedSchemaAndNonemptyEntries() {
        assertFails { decode("""{"schemaVersion":2,"extensions":[{"manifestUrl":"/a/manifest.json"}]}""") }
        assertFails { decode("""{"schemaVersion":1,"extensions":[]}""") }
    }
    @Test fun publicHttpAndEmbeddedCredentialsAreRejected() {
        for (url in listOf("provider.example/manifest.json", "http://public.example/manifest.json", "https://user:pass@public.example/manifest.json", "https://public.example/manifest.json#secret")) {
            assertFails { ExtensionLinkDecoder.url(url) }
        }
    }
    @Test fun localHttpAndStremioLinksAreAccepted() {
        assertEquals("https://provider.example/manifest.json", ExtensionLinkDecoder.url("stremio://provider.example/manifest.json"))
        assertEquals("http://192.168.178.1/manifest.json", ExtensionLinkDecoder.url("http://192.168.178.1/manifest.json"))
    }
    @Test fun executableAndDirectoryManifestsAreRejected() {
        assertFails { decode("""{"id":"x","name":"X","version":"1","entryPointV3":"x.js","resources":["stream"]}""") }
        assertFails { decode("""{"id":"x","name":"X","version":"1","resources":["addon_catalog"]}""") }
    }
    @Test fun configurationPlaceholderIsPreviewedButRequiresSetup() {
        val d = decode("""{"id":"x","name":"X","version":"1","behaviorHints":{"configurable":true,"configurationRequired":true}}""")
        assertTrue(d.entries.single().requiresConfiguration)
        assertEquals("https://provider.example/configure", d.entries.single().configurationUrl)
    }
    @Test fun oversizeDocumentsAreRejected() {
        assertFails { ExtensionLinkDecoder.decode(ByteArray(ExtensionLinkDecoder.MAX_BYTES + 1), "https://provider.example/manifest.json") }
    }
}

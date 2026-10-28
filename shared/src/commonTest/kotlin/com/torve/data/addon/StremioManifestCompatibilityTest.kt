package com.torve.data.addon

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StremioManifestCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun acceptsStringAndObjectResourceForms() {
        val manifest = json.decodeFromString<StremioManifest>(
            """{"id":"org.example","name":"Example","version":"1.0.0","types":["movie","series"],"resources":["stream",{"name":"subtitles","types":["movie"],"idPrefixes":["tt"]}]}""",
        )

        assertEquals(listOf("stream", "subtitles"), manifest.resources.map { it.name })
        assertEquals(listOf("movie"), manifest.resources[1].types)
        assertTrue(StremioManifestCompatibility.validate(manifest).isCompatible)
    }

    @Test
    fun rejectsMissingIdentityAndMalformedCatalogs() {
        val report = StremioManifestCompatibility.validate(
            StremioManifest(
                resources = listOf(StremioManifestResource("stream")),
                catalogs = listOf(StremioCatalog(type = "", id = "")),
            ),
        )

        assertFalse(report.isCompatible)
        assertTrue(report.issues.any { it.field == "id" })
        assertTrue(report.issues.any { it.field == "catalogs[0].type" })
        assertTrue(report.issues.any { it.field == "catalogs[0].id" })
    }

    @Test
    fun unknownResourcesRemainForwardCompatibleWarnings() {
        val report = StremioManifestCompatibility.validate(
            StremioManifest(
                id = "org.future",
                name = "Future",
                version = "2.0.0",
                resources = listOf(StremioManifestResource("recommendations")),
            ),
        )

        assertTrue(report.isCompatible)
        assertEquals(ManifestCompatibilitySeverity.WARNING, report.issues.single().severity)
    }
}

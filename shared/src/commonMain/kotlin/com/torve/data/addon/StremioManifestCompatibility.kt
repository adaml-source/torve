package com.torve.data.addon

enum class ManifestCompatibilitySeverity { ERROR, WARNING }

data class ManifestCompatibilityIssue(
    val field: String,
    val message: String,
    val severity: ManifestCompatibilitySeverity,
)

data class ManifestCompatibilityReport(
    val issues: List<ManifestCompatibilityIssue>,
) {
    val isCompatible: Boolean
        get() = issues.none { it.severity == ManifestCompatibilitySeverity.ERROR }
}

/**
 * Torve's public Stremio add-on manifest contract. Unknown resource names are
 * warnings so newer protocol extensions remain installable.
 */
object StremioManifestCompatibility {
    private val knownResources = setOf("catalog", "meta", "stream", "subtitles")

    fun validate(manifest: StremioManifest): ManifestCompatibilityReport {
        val issues = buildList {
            required("id", manifest.id)
            required("name", manifest.name)
            required("version", manifest.version)

            manifest.resources.forEachIndexed { index, resource ->
                if (resource.name.isBlank()) {
                    add(error("resources[$index].name", "Resource name must not be blank."))
                } else if (resource.name.lowercase() !in knownResources) {
                    add(
                        warning(
                            "resources[$index].name",
                            "Unknown resource '${resource.name}' will be preserved for forward compatibility.",
                        ),
                    )
                }
            }
            manifest.catalogs.forEachIndexed { index, catalog ->
                if (catalog.type.isBlank()) {
                    add(error("catalogs[$index].type", "Catalog type must not be blank."))
                }
                if (catalog.id.isBlank()) {
                    add(error("catalogs[$index].id", "Catalog id must not be blank."))
                }
                val duplicateExtras = catalog.extra.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
                if (duplicateExtras.isNotEmpty()) {
                    add(
                        warning(
                            "catalogs[$index].extra",
                            "Duplicate extras: ${duplicateExtras.joinToString()}.",
                        ),
                    )
                }
            }
        }
        return ManifestCompatibilityReport(issues)
    }

    private fun MutableList<ManifestCompatibilityIssue>.required(field: String, value: String) {
        if (value.isBlank()) add(error(field, "$field is required."))
    }

    private fun error(field: String, message: String) =
        ManifestCompatibilityIssue(field, message, ManifestCompatibilitySeverity.ERROR)

    private fun warning(field: String, message: String) =
        ManifestCompatibilityIssue(field, message, ManifestCompatibilitySeverity.WARNING)
}

package com.torve.data.addon

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.serialization.json.*

data class ExtensionLinkEntry(
    val manifestUrl: String,
    val name: String,
    val summary: String = "",
    val capabilities: List<String> = emptyList(),
    val configurationUrl: String? = null,
    val requiresConfiguration: Boolean = false,
)

data class ExtensionLinkDocument(
    val name: String,
    val summary: String,
    val entries: List<ExtensionLinkEntry>,
    val isCollection: Boolean,
)

/** Matches Apple's HTTP/JSON preview contract; previews never install anything. */
object ExtensionLinkDecoder {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val supported = setOf("catalog", "meta", "stream", "subtitles")

    fun url(text: String, relativeTo: String? = null): String {
        val raw = text.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        require(relativeTo != null || raw.startsWith("https://", true) || raw.startsWith("http://", true)) {
            "Enter a complete HTTPS JSON or manifest URL."
        }
        val parsed = runCatching {
            if (relativeTo != null) URLBuilder(relativeTo).takeFrom(raw).build() else Url(raw)
        }.getOrElse { error("Enter a valid JSON or manifest URL.") }
        require(parsed.host.isNotBlank() && parsed.user == null && parsed.password == null && parsed.fragment.isEmpty()) {
            "Use a URL without a username, password or fragment."
        }
        require(parsed.protocol.name == "https" || (parsed.protocol.name == "http" && localHost(parsed.host))) {
            "Use HTTPS. HTTP is supported only for a server on your local network."
        }
        return parsed.toString()
    }

    private fun localHost(host: String): Boolean {
        if (host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts.take(2) == listOf(192, 168) || (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun manifest(text: String, relativeTo: String? = null): String {
        val value = url(text, relativeTo)
        require(Url(value).encodedPath.endsWith("/manifest.json")) { "An extension URL must end with /manifest.json." }
        return value
    }

    fun decode(bytes: ByteArray, documentUrl: String): ExtensionLinkDocument {
        require(bytes.size <= MAX_BYTES) { "This JSON document is too large." }
        val address = url(documentUrl)
        val root = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { error("This link did not return a valid JSON manifest or source collection.") }
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
        fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
        fun capabilities(names: List<String>) = names.mapNotNull {
            when (it.lowercase()) { "catalog" -> "Catalogue"; "meta" -> "Metadata"; "stream" -> "Playback"; "subtitles" -> "Subtitles"; else -> null }
        }.distinct()
        if ("extensions" in root) {
            val values = root["extensions"] as? JsonArray
            require((root["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1 && values != null && values.size in 1..50) {
                "A source collection needs schemaVersion 1 and between 1 and 50 extensions."
            }
            val entries = values.map { value ->
                val item = value as? JsonObject ?: error("Each collection entry must describe an extension.")
                val link = manifest(item.text("manifestUrl") ?: error("Every entry needs a manifestUrl."), address)
                ExtensionLinkEntry(link, item.text("name") ?: Url(link).host, item.text("description").orEmpty(),
                    capabilities(item.array("capabilities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }),
                    item.text("configurationUrl")?.let { url(it, address) })
            }.distinctBy { it.manifestUrl }
            return ExtensionLinkDocument(root.text("name") ?: "Source collection", root.text("description").orEmpty(), entries, true)
        }
        require("entryPointV3" !in root && "entryPointV4" !in root) { "Torve accepts HTTP/JSON extensions, not executable plug-ins." }
        val name = root.text("name")
        require(root.text("id") != null && name != null && root.text("version") != null) { "A manifest needs an id, name and version." }
        val resources = root.array("resources").mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("name")
        }.map { it.lowercase() }
        val hints = root["behaviorHints"] as? JsonObject ?: JsonObject(emptyMap())
        val needsSetup = hints.flag("configurationRequired") || root.array("config").any { (it as? JsonObject)?.text("key") == "configure" }
        require(resources.any { it in supported } || (hints.flag("configurable") && needsSetup)) {
            "This manifest does not declare catalogue, metadata, playback or subtitle resources."
        }
        val configLink = root.text("configurationUrl") ?: hints.text("configurationUrl") ?: root.array("links").mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("name")?.contains("configure", true) == true }?.text("url")
        val configuration = configLink?.let { url(it, address) }
            ?: if (hints.flag("configurable")) url("configure", address) else null
        return ExtensionLinkDocument(name, root.text("description").orEmpty(), listOf(ExtensionLinkEntry(manifest(address), name,
            root.text("description").orEmpty(), capabilities(resources), configuration, needsSetup)), false)
    }
}

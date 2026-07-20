package com.torve.data.addon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stremio manifests can have resources as either strings ("stream") or
 * objects ({ name: "stream", types: [...], idPrefixes: [...] }).
 */
object StremioManifestResourceSerializer : KSerializer<StremioManifestResource> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("StremioManifestResource")

    override fun deserialize(decoder: Decoder): StremioManifestResource {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> StremioManifestResource(name = element.content)
            is JsonObject -> {
                val name = element["name"]?.jsonPrimitive?.content ?: ""
                val types = (element["types"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                val idPrefixes = (element["idPrefixes"] as? JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                StremioManifestResource(name = name, types = types, idPrefixes = idPrefixes)
            }
            else -> StremioManifestResource(name = "")
        }
    }

    override fun serialize(encoder: Encoder, value: StremioManifestResource) {
        encoder.encodeString(value.name)
    }
}

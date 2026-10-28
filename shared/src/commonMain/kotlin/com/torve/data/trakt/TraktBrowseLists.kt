package com.torve.data.trakt

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TraktListOwner(val username: String = "", val name: String? = null, val ids: TraktIds = TraktIds())
@Serializable
data class TraktBrowseList(val name: String = "", val description: String? = null, val ids: TraktIds = TraktIds(),
    @SerialName("item_count") val itemCount: Int = 0, val likes: Int = 0, val user: TraktListOwner? = null, val owned: Boolean = false)
@Serializable
data class TraktBrowseListEnvelope(val list: TraktBrowseList? = null, @SerialName("like_count") val likeCount: Int = 0)
@Serializable
data class TraktListImages(val poster: List<String> = emptyList(), val fanart: List<String> = emptyList())
@Serializable
data class TraktListMedia(val title: String = "", val year: Int? = null, val ids: TraktIds = TraktIds(), val images: TraktListImages? = null)
@Serializable
data class TraktBrowseListEntry(val type: String = "", val movie: TraktListMedia? = null, val show: TraktListMedia? = null)

data class TraktListAddress(val user: String? = null, val slug: String) {
    val path: String get() = user?.let { "/users/$it/lists/$slug" } ?: "/lists/$slug"
}
object TraktListLinks {
    private val segment = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")
    fun parse(value: String): TraktListAddress? {
        val url = runCatching { Url(value.trim()) }.getOrNull() ?: return null
        if (url.protocol.name !in setOf("https", "http") || url.host.lowercase() !in setOf("trakt.tv", "www.trakt.tv", "app.trakt.tv")) return null
        if (!url.user.isNullOrBlank() || !url.password.isNullOrBlank()) return null
        val parts = url.encodedPath.trim('/').split('/')
        return when {
            parts.size == 2 && parts[0] == "lists" && parts[1].toIntOrNull()?.let { it > 0 } == true -> TraktListAddress(slug = parts[1])
            parts.size == 4 && parts[0] == "users" && parts[2] == "lists" && segment.matches(parts[1]) && segment.matches(parts[3]) -> TraktListAddress(parts[1], parts[3])
            else -> null
        }
    }
}

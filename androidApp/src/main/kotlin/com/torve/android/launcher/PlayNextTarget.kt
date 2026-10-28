package com.torve.android.launcher
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
internal data class PlayNextTarget(val type: String, val id: Int, val season: Int?, val episode: Int?)
internal fun launcherOwner(person: String) = MessageDigest.getInstance("SHA-256").digest(person.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
internal fun readPlayNextTarget(url: String, person: String): PlayNextTarget? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host != "torve.app" || uri.path != "/app/watch" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
    val pairs = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
    if (pairs.map { it.first }.distinct().size != pairs.size) return null
    val values = pairs.toMap()
    if (values["owner"] != launcherOwner(person)) return null
    val type = values["type"]?.takeIf { it in setOf("movie", "tv") } ?: return null
    val id = values["id"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val season = values["season"]?.toIntOrNull()?.takeIf { it > 0 }
    val episode = values["episode"]?.toIntOrNull()?.takeIf { it > 0 }
    if (type == "tv" && (season == null || episode == null) && values["origin"] != "search") return null
    PlayNextTarget(type, id, season, episode)
}.getOrNull()

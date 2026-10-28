package com.torve.android.player

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.SourceLanguages
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists only normalized languages and hashes, scoped to the current account/profile and file. */
internal class SourceTrackCache(private val file: File, fallbackFile: File? = null,
    private val now: () -> Long = System::currentTimeMillis) {
    @Serializable data class Entry(val audio: List<String>, val subtitles: List<String>, val checkedAt: Long)
    private val entries = runCatching {
        val stored = if (file.exists()) file else fallbackFile ?: file
        if (stored.length() in 1..1_048_576) Json.decodeFromString<Map<String, Entry>>(stored.readText()).toMutableMap()
        else mutableMapOf()
    }.getOrDefault(mutableMapOf())
    private val aliases = mutableMapOf<String, String>()
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()

    init {
        if (!file.exists() && entries.isNotEmpty()) runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(entries))
        }
    }

    companion object {
        const val LIFETIME_MS = 7 * 24 * 60 * 60 * 1000L
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun urlKey(url: String, owner: String) = hash("$owner\nurl\n$url")
        fun key(stream: ParsedStream, owner: String): String? =
            if (!stream.infoHash.isNullOrBlank() && stream.fileIdx != null)
                hash("$owner\nfile\n${stream.infoHash?.lowercase()}\n${stream.fileIdx}")
            else stream.directUrl?.takeIf { it.isNotBlank() }?.let { urlKey(it, owner) }
    }

    @Synchronized fun entry(key: String?): Entry? = entries[key]?.takeIf { now() - it.checkedAt in 0 until LIFETIME_MS }
    @Synchronized fun applying(stream: ParsedStream, owner: String): ParsedStream = entry(key(stream, owner))?.let {
        SourceLanguages.withVerifiedTracks(stream, it.audio, it.subtitles)
    } ?: stream

    @Synchronized fun associate(stream: ParsedStream, url: String, owner: String) {
        val key = key(stream, owner) ?: return
        if (aliases.size >= 256) aliases.clear()
        aliases[urlKey(url, owner)] = key
    }

    @Synchronized fun recordPlayback(url: String, owner: String, audio: List<String>, subtitles: List<String>) {
        val key = urlKey(url, owner)
        // Some player notifications expose tracks before their language tags.
        // Keep already-verified audio instead of replacing it with an empty update.
        if (audio.none { SourceLanguages.normalize(it) != null } &&
            listOfNotNull(key, aliases[key]).any { entry(it)?.audio?.isNotEmpty() == true }) return
        save(listOfNotNull(key, aliases[key]), audio, subtitles)
    }

    @Synchronized fun save(keys: List<String>, audio: List<String>, subtitles: List<String>) {
        val entry = Entry(audio.mapNotNull(SourceLanguages::normalize).distinct().sorted(),
            subtitles.mapNotNull(SourceLanguages::normalize).distinct().sorted(), now())
        if (file.exists() && keys.all { entry(it)?.let { old -> old.audio == entry.audio && old.subtitles == entry.subtitles } == true }) return
        keys.forEach { entries[it] = entry }
        entries.entries.removeAll { now() - it.value.checkedAt !in 0 until LIFETIME_MS }
        entries.entries.sortedByDescending { it.value.checkedAt }.drop(500).forEach { entries.remove(it.key) }
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(Json.encodeToString(entries))
            if (!temporary.renameTo(file)) { file.writeText(temporary.readText()); temporary.delete() }
        }
        mutableRevision.value += 1
    }
}

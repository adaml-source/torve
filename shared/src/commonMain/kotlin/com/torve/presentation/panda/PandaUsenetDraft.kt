package com.torve.presentation.panda

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.panda.PandaIndexerSecret

/** A refresh may finish after typing or even after saving. Neither may undo a newer edit. */
internal class PandaUsenetDraft {
    data class Read(val owner: String, val revision: Long)
    private var owner: String? = null
    private var revision = 0L
    private var savedRevision = 0L

    fun read(owner: String): Read {
        if (this.owner != owner) {
            this.owner = owner
            revision++
            savedRevision = revision
        }
        return Read(owner, revision)
    }

    fun edited(owner: String) { read(owner); revision++ }
    fun discarded(owner: String) { read(owner); revision++; savedRevision = revision }
    fun hasEdits(owner: String): Boolean = this.owner == owner && revision != savedRevision
    fun saved(read: Read, currentOwner: String) {
        if (read.owner == currentOwner && owner == currentOwner) savedRevision = maxOf(savedRevision, read.revision)
    }

    fun merge(current: PandaSetupUiState, refreshed: PandaSetupUiState, read: Read, currentOwner: String): PandaSetupUiState {
        if (read.owner != currentOwner) return current
        if (!hasEdits(currentOwner) && revision == read.revision) return refreshed
        return refreshed.copy(
            enableUsenet = current.enableUsenet, usenetProvider = current.usenetProvider,
            usenetHost = current.usenetHost, usenetPort = current.usenetPort,
            usenetUsername = current.usenetUsername, usenetPassword = current.usenetPassword,
            usenetSSL = current.usenetSSL, usenetConnections = current.usenetConnections,
            nzbIndexers = current.nzbIndexers, nzbIndexer = current.nzbIndexer,
            nzbIndexerUrl = current.nzbIndexerUrl, nzbIndexerApiKey = current.nzbIndexerApiKey,
            downloadClient = current.downloadClient, downloadClientUrl = current.downloadClientUrl,
            downloadClientUsername = current.downloadClientUsername,
            downloadClientPassword = current.downloadClientPassword,
            downloadClientApiKey = current.downloadClientApiKey, easynewsPreferNzb = current.easynewsPreferNzb,
        )
    }
}

/** Never report success after silently dropping an incomplete or unreadable indexer. */
internal fun pandaIndexersForSave(rows: List<NzbIndexerRow>): List<NzbIndexerRow> =
    rows.mapIndexedNotNull { index, row ->
        if (row.type == "none") return@mapIndexedNotNull null
        val clean = row.copy(url = row.url.trim(), apiKey = row.apiKey.trim())
        require(clean.apiKey.isNotEmpty() && clean.apiKey != "[redacted]") {
            "Enter the API key for NZB indexer #${index + 1}. Your changes have not been saved."
        }
        if (clean.type == "custom") {
            require(Regex("(?i)^https?://[^\\s/]+(?:/[^\\s]*)?$").matches(clean.url)) {
                "Enter a complete http:// or https:// URL for NZB indexer #${index + 1}."
            }
        }
        clean
    }

internal fun pandaIndexersForUpdate(
    rows: List<NzbIndexerRow>,
    savedRows: List<NzbIndexerRow>?,
    secrets: List<PandaIndexerSecret>,
): List<NzbIndexerRow>? {
    val restored = rows.map { row ->
        if (row.apiKey.isNotBlank() && row.apiKey != "[redacted]") row
        else row.copy(apiKey = secrets.firstOrNull { it.type == row.type && it.url == row.url }?.apiKey.orEmpty())
    }
    // An unchanged list can retain server-only keys. A changed list must be sent
    // completely, or fail visibly; omitting it would discard a newly added row.
    if (rows == savedRows && restored.any { it.type != "none" && (it.apiKey.isBlank() || it.apiKey == "[redacted]") }) return null
    return pandaIndexersForSave(restored)
}

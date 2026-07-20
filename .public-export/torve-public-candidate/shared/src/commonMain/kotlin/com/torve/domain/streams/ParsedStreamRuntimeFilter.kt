package com.torve.domain.streams

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.DEFAULT_STREAM_GROUPS
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.serialization.json.Json

data class ParsedStreamRuntimeFilterResult(
    val streams: List<ParsedStream>,
    val filterResult: StreamFilterResult<ParsedStream>,
)

class ParsedStreamRuntimeFilter(
    private val preferencesRepository: PreferencesRepository?,
    @Suppress("unused") private val subscriptionRepository: SubscriptionRepository?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun apply(streams: List<ParsedStream>): ParsedStreamRuntimeFilterResult {
        if (streams.isEmpty()) {
            return ParsedStreamRuntimeFilterResult(
                streams = emptyList(),
                filterResult = StreamFilterResult(
                    visible = emptyList(),
                    excludedCount = 0,
                    groupMatches = emptyMap(),
                    invalidPatterns = emptyList(),
                    matchedGroups = emptyMap(),
                ),
            )
        }

        val customRulesEnabled = true
        val rules = loadRules()

        val filterResult = StreamFilterEngine.apply(
            streams = streams,
            regexPatterns = rules.regexPatterns,
            streamGroups = rules.streamGroups,
            customRulesEnabled = customRulesEnabled,
            textOf = ParsedStream::streamFilterText,
        )
        val ordered = StreamFilterEngine.orderByGroupPriorityWithinPrimaryBuckets(
            streams = filterResult.visible,
            matchedGroups = filterResult.matchedGroups,
            primaryKeyOf = { it.streamFilterPrimaryKey() },
        )

        return ParsedStreamRuntimeFilterResult(
            streams = ordered,
            filterResult = filterResult.copy(visible = ordered),
        )
    }

    private suspend fun loadRules(): RuntimeStreamRules {
        val repository = preferencesRepository ?: return RuntimeStreamRules()
        val regexPatterns = repository.getString(StreamFilterPreferenceKeys.REGEX_PATTERNS)
            ?.let { raw -> runCatching { json.decodeFromString<List<RegexPattern>>(raw) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val streamGroups = repository.getString(StreamFilterPreferenceKeys.STREAM_GROUPS)
            ?.let { raw -> runCatching { json.decodeFromString<List<StreamGroup>>(raw) }.getOrDefault(DEFAULT_STREAM_GROUPS) }
            ?: DEFAULT_STREAM_GROUPS
        return RuntimeStreamRules(
            regexPatterns = regexPatterns,
            streamGroups = streamGroups,
        )
    }

    private data class RuntimeStreamRules(
        val regexPatterns: List<RegexPattern> = emptyList(),
        val streamGroups: List<StreamGroup> = emptyList(),
    )

    private data class ParsedStreamFilterPrimaryKey(
        val score: Int,
        val hasRecentSuccess: Boolean,
        val lastSuccessfulResolveAt: Long,
        val recentSuccessCount: Int,
        val instantPlayback: Boolean,
        val accelerationScore: Double?,
        val accelerationSourceKey: String?,
    )

    private fun ParsedStream.streamFilterPrimaryKey(): ParsedStreamFilterPrimaryKey =
        ParsedStreamFilterPrimaryKey(
            score = score,
            hasRecentSuccess = recentSuccessCount > 0,
            lastSuccessfulResolveAt = lastSuccessfulResolveAt ?: 0L,
            recentSuccessCount = recentSuccessCount,
            instantPlayback = isCached || directUrl != null,
            accelerationScore = accelerationScore,
            accelerationSourceKey = accelerationSourceKey,
        )
}

fun ParsedStream.streamFilterText(): String {
    val stream = this
    return buildList {
        add(stream.title)
        add(stream.addonName)
        add(stream.quality)
        stream.source?.takeIf { it.isNotBlank() }?.let(::add)
        stream.size?.takeIf { it.isNotBlank() }?.let(::add)
        stream.codec?.takeIf { it.isNotBlank() }?.let(::add)
        stream.hdr?.takeIf { it.isNotBlank() }?.let(::add)
        stream.audioCodec?.takeIf { it.isNotBlank() }?.let(::add)
        if (stream.languages.isNotEmpty()) add(stream.languages.joinToString(" "))
        if (stream.isCached) add("cached debrid cloud")
    }.joinToString(separator = " ")
}

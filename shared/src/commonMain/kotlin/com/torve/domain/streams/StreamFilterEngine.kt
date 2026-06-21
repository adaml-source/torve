package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup

object StreamFilterPreferenceKeys {
    const val REGEX_PATTERNS = "regex_patterns"
    const val STREAM_GROUPS = "stream_groups"
}

data class StreamFilterResult<T>(
    val visible: List<T>,
    val excludedCount: Int,
    val groupMatches: Map<String, List<T>>,
    val invalidPatterns: List<String>,
    val matchedGroups: Map<T, StreamGroup>,
)

object StreamFilterEngine {

    fun <T> apply(
        streams: List<T>,
        regexPatterns: List<RegexPattern>,
        streamGroups: List<StreamGroup>,
        customRulesEnabled: Boolean,
        textOf: (T) -> String,
    ): StreamFilterResult<T> {
        if (streams.isEmpty() || !customRulesEnabled) {
            return StreamFilterResult(
                visible = streams,
                excludedCount = 0,
                groupMatches = emptyMap(),
                invalidPatterns = emptyList(),
                matchedGroups = emptyMap(),
            )
        }

        val invalid = mutableListOf<String>()
        val exclusions = regexPatterns
            .filter { it.enabled && it.pattern.isNotBlank() }
            .mapNotNull { pattern ->
                compileRule(
                    label = pattern.label.ifBlank { "unnamed regex" },
                    pattern = pattern.pattern,
                    invalid = invalid,
                )
            }
        val groups = streamGroups
            .mapIndexed { index, group -> index to group }
            .filter { (_, group) -> group.enabled && group.matchPattern.isNotBlank() }
            .mapNotNull { (index, group) ->
                compileRule(
                    label = group.name.ifBlank { "unnamed group" },
                    pattern = group.matchPattern,
                    invalid = invalid,
                )?.let { compiled -> CompiledGroup(group = group, regex = compiled.regex, originalIndex = index) }
            }
            .sortedWith(compareBy<CompiledGroup> { it.group.priority }.thenBy { it.originalIndex })

        var excludedCount = 0
        val visible = mutableListOf<T>()
        for (stream in streams) {
            val text = textOf(stream)
            if (exclusions.any { it.regex.containsMatchIn(text) }) {
                excludedCount += 1
            } else {
                visible += stream
            }
        }

        val groupMatches = linkedMapOf<String, MutableList<T>>()
        val matchedGroups = linkedMapOf<T, StreamGroup>()
        for (stream in visible) {
            val text = textOf(stream)
            val match = groups.firstOrNull { it.regex.containsMatchIn(text) } ?: continue
            matchedGroups[stream] = match.group
            groupMatches.getOrPut(match.group.name) { mutableListOf() } += stream
        }

        return StreamFilterResult(
            visible = visible,
            excludedCount = excludedCount,
            groupMatches = groupMatches.mapValues { it.value.toList() },
            invalidPatterns = invalid.distinct(),
            matchedGroups = matchedGroups,
        )
    }

    fun <T, K> orderByGroupPriorityWithinPrimaryBuckets(
        streams: List<T>,
        matchedGroups: Map<T, StreamGroup>,
        primaryKeyOf: (T) -> K,
    ): List<T> {
        if (streams.size < 2 || matchedGroups.isEmpty()) return streams

        val ordered = mutableListOf<T>()
        var start = 0
        while (start < streams.size) {
            val key = primaryKeyOf(streams[start])
            var end = start + 1
            while (end < streams.size && primaryKeyOf(streams[end]) == key) {
                end += 1
            }

            val bucket = streams.subList(start, end)
            val sortedBucket = bucket
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<T>> { matchedGroups[it.value]?.priority ?: Int.MAX_VALUE }
                        .thenBy { it.index },
                )
                .map { it.value }
            ordered += sortedBucket
            start = end
        }
        return ordered
    }

    private data class CompiledRule(
        val label: String,
        val regex: Regex,
    )

    private data class CompiledGroup(
        val group: StreamGroup,
        val regex: Regex,
        val originalIndex: Int,
    )

    private fun compileRule(
        label: String,
        pattern: String,
        invalid: MutableList<String>,
    ): CompiledRule? {
        return runCatching { Regex(pattern) }
            .fold(
                onSuccess = { CompiledRule(label = label, regex = it) },
                onFailure = {
                    invalid += label
                    null
                },
            )
    }
}

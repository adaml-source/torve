package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class StreamRulesImportResult<T>(
    val items: List<T>,
    val disabledOnImport: Int,
)

object StreamRulesJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun exportRegexPatterns(patterns: List<RegexPattern>): String =
        json.encodeToString(
            StreamRulesFile(
                type = "torve_regex_patterns",
                regexPatterns = patterns.map { it.toFileItem() },
            ),
        )

    fun exportStreamGroups(groups: List<StreamGroup>): String =
        json.encodeToString(
            StreamRulesFile(
                type = "torve_stream_groups",
                streamGroups = groups.map { it.toFileItem() },
            ),
        )

    fun importRegexPatterns(rawJson: String): StreamRulesImportResult<RegexPattern> {
        val patterns = decodeRegexPatternItems(rawJson)
        val sanitized = patterns.map { item ->
            val requested = RegexPattern(
                label = item.label.ifBlank { item.name },
                pattern = item.pattern,
                enabled = item.enabled,
            )
            requested to StreamRulePatternValidator.sanitize(requested)
        }
        return StreamRulesImportResult(
            items = sanitized.map { it.second },
            disabledOnImport = sanitized.count { (requested, result) -> requested.enabled && !result.enabled },
        )
    }

    fun importStreamGroups(rawJson: String): StreamRulesImportResult<StreamGroup> {
        val groups = decodeStreamGroupItems(rawJson)
        val sanitized = groups.map { item ->
            val requested = StreamGroup(
                name = item.name.ifBlank { item.label },
                matchPattern = item.matchPattern.ifBlank { item.pattern },
                priority = item.priority,
                enabled = item.enabled,
            )
            requested to StreamRulePatternValidator.sanitize(requested)
        }
        return StreamRulesImportResult(
            items = sanitized.map { it.second },
            disabledOnImport = sanitized.count { (requested, result) -> requested.enabled && !result.enabled },
        )
    }

    private fun decodeRegexPatternItems(rawJson: String): List<RegexPatternFileItem> {
        runCatching {
            val file = json.decodeFromString<StreamRulesFile>(rawJson)
            val items = file.regexPatterns + file.regexPatternsSnakeCase
            if (items.isNotEmpty()) return items
        }
        runCatching {
            val prefs = json.decodeFromString<PreferencesFile>(rawJson)
            val encodedRules = prefs.preferences.firstOrNull { it.key == StreamFilterPreferenceKeys.REGEX_PATTERNS }?.value
            if (!encodedRules.isNullOrBlank()) return decodeRegexPatternItems(encodedRules)
        }
        return json.decodeFromString<List<RegexPatternFileItem>>(rawJson)
    }

    private fun decodeStreamGroupItems(rawJson: String): List<StreamGroupFileItem> {
        runCatching {
            val file = json.decodeFromString<StreamRulesFile>(rawJson)
            val items = file.streamGroups + file.streamGroupsSnakeCase
            if (items.isNotEmpty()) return items
        }
        runCatching {
            val prefs = json.decodeFromString<PreferencesFile>(rawJson)
            val encodedRules = prefs.preferences.firstOrNull { it.key == StreamFilterPreferenceKeys.STREAM_GROUPS }?.value
            if (!encodedRules.isNullOrBlank()) return decodeStreamGroupItems(encodedRules)
        }
        return json.decodeFromString<List<StreamGroupFileItem>>(rawJson)
    }

    private fun RegexPattern.toFileItem(): RegexPatternFileItem =
        RegexPatternFileItem(label = label, pattern = pattern, enabled = enabled)

    private fun StreamGroup.toFileItem(): StreamGroupFileItem =
        StreamGroupFileItem(name = name, matchPattern = matchPattern, priority = priority, enabled = enabled)

    @Serializable
    private data class StreamRulesFile(
        val type: String = "torve_stream_rules",
        val version: Int = 1,
        val regexPatterns: List<RegexPatternFileItem> = emptyList(),
        @SerialName("regex_patterns")
        val regexPatternsSnakeCase: List<RegexPatternFileItem> = emptyList(),
        val streamGroups: List<StreamGroupFileItem> = emptyList(),
        @SerialName("stream_groups")
        val streamGroupsSnakeCase: List<StreamGroupFileItem> = emptyList(),
    )

    @Serializable
    private data class PreferencesFile(
        val preferences: List<PreferenceFileItem> = emptyList(),
    )

    @Serializable
    private data class PreferenceFileItem(
        val key: String,
        val value: String,
    )

    @Serializable
    private data class RegexPatternFileItem(
        val label: String = "",
        val name: String = "",
        val pattern: String = "",
        val enabled: Boolean = true,
    )

    @Serializable
    private data class StreamGroupFileItem(
        val name: String = "",
        val label: String = "",
        val matchPattern: String = "",
        val pattern: String = "",
        val priority: Int = 99,
        val enabled: Boolean = true,
    )
}

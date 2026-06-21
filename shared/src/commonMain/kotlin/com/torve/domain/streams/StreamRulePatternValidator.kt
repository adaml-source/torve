package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup

object StreamRulePatternValidator {
    fun isValid(pattern: String): Boolean {
        if (pattern.isBlank()) return true
        return runCatching { Regex(pattern) }.isSuccess
    }

    fun canEnable(pattern: String): Boolean =
        pattern.isNotBlank() && isValid(pattern)

    fun regexErrorMessage(pattern: String): String? =
        if (isValid(pattern)) null else "This regex is invalid. Please check the pattern."

    fun groupErrorMessage(pattern: String): String? =
        if (isValid(pattern)) null else "This group pattern is invalid. Please check the regex."

    fun sanitize(pattern: RegexPattern): RegexPattern =
        if (pattern.enabled && !canEnable(pattern.pattern)) pattern.copy(enabled = false) else pattern

    fun sanitize(group: StreamGroup): StreamGroup =
        if (group.enabled && !canEnable(group.matchPattern)) group.copy(enabled = false) else group
}

data class StreamRuntimeFilterFeedback(
    val hiddenCount: Int = 0,
)

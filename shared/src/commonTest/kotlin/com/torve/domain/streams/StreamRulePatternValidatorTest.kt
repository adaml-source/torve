package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRulePatternValidatorTest {

    @Test
    fun invalidRegexGetsFriendlyValidationMessage() {
        val message = StreamRulePatternValidator.regexErrorMessage("[")

        assertEquals("This regex is invalid. Please check the pattern.", message)
        assertFalse(message.orEmpty().contains("PatternSyntaxException"))
        assertFalse(message.orEmpty().contains("Unclosed"))
    }

    @Test
    fun invalidStreamGroupPatternGetsFriendlyValidationMessage() {
        val message = StreamRulePatternValidator.groupErrorMessage("(")

        assertEquals("This group pattern is invalid. Please check the regex.", message)
        assertFalse(message.orEmpty().contains("PatternSyntaxException"))
    }

    @Test
    fun validPatternsHaveNoValidationMessage() {
        assertNull(StreamRulePatternValidator.regexErrorMessage("(?i)HDCAM"))
        assertNull(StreamRulePatternValidator.groupErrorMessage("(?i)(2160p|DV)"))
    }

    @Test
    fun invalidEnabledRulesAreSavedDisabledSoTheyCannotRun() {
        val regex = StreamRulePatternValidator.sanitize(
            RegexPattern(label = "Broken", pattern = "[", enabled = true),
        )
        val group = StreamRulePatternValidator.sanitize(
            StreamGroup(name = "Broken", matchPattern = "(", priority = 0, enabled = true),
        )

        assertFalse(regex.enabled)
        assertEquals("[", regex.pattern)
        assertFalse(group.enabled)
        assertEquals("(", group.matchPattern)
    }

    @Test
    fun validEnabledRulesStayEnabled() {
        assertTrue(StreamRulePatternValidator.sanitize(RegexPattern(pattern = "(?i)cam")).enabled)
        assertTrue(StreamRulePatternValidator.sanitize(StreamGroup("4K", "(?i)2160p", 0)).enabled)
    }

    @Test
    fun blankPatternsAreEditableButCannotBeEnabled() {
        assertNull(StreamRulePatternValidator.regexErrorMessage(""))
        assertFalse(StreamRulePatternValidator.canEnable(""))
        assertFalse(StreamRulePatternValidator.sanitize(RegexPattern(pattern = "", enabled = true)).enabled)
        assertFalse(StreamRulePatternValidator.sanitize(StreamGroup("Empty", "", 99, enabled = true)).enabled)
    }
}

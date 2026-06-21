package com.torve.domain.streams

import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamRulesJsonTest {

    @Test
    fun exportsAndImportsRegexPatterns() {
        val json = StreamRulesJson.exportRegexPatterns(
            listOf(RegexPattern(label = "No CAM", pattern = "(?i)cam", enabled = true)),
        )

        val result = StreamRulesJson.importRegexPatterns(json)

        assertEquals(1, result.items.size)
        assertEquals("No CAM", result.items.single().label)
        assertEquals("(?i)cam", result.items.single().pattern)
        assertTrue(result.items.single().enabled)
    }

    @Test
    fun importsRawRegexArrayAndDisablesInvalidEnabledRules() {
        val result = StreamRulesJson.importRegexPatterns(
            """
            [
              {"label":"Broken","pattern":"[","enabled":true},
              {"label":"Editable blank","pattern":"","enabled":true}
            ]
            """.trimIndent(),
        )

        assertEquals(2, result.items.size)
        assertEquals(2, result.disabledOnImport)
        assertFalse(result.items[0].enabled)
        assertFalse(result.items[1].enabled)
    }

    @Test
    fun importsRegexPatternsFromFullBackupPreferenceShape() {
        val encodedRules = """[{"label":"No TS","pattern":"(?i)ts","enabled":true}]"""
            .replace("\"", "\\\"")
        val result = StreamRulesJson.importRegexPatterns(
            """
            {
              "preferences": [
                {"key":"regex_patterns","value":"$encodedRules"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("No TS"), result.items.map { it.label })
    }

    @Test
    fun exportsAndImportsStreamGroups() {
        val json = StreamRulesJson.exportStreamGroups(
            listOf(StreamGroup(name = "4K", matchPattern = "(?i)2160p", priority = 0, enabled = true)),
        )

        val result = StreamRulesJson.importStreamGroups(json)

        assertEquals(1, result.items.size)
        assertEquals("4K", result.items.single().name)
        assertEquals("(?i)2160p", result.items.single().matchPattern)
        assertEquals(0, result.items.single().priority)
        assertTrue(result.items.single().enabled)
    }

    @Test
    fun importsRawStreamGroupArrayAndDisablesBlankEnabledRules() {
        val result = StreamRulesJson.importStreamGroups(
            """
            [
              {"name":"Broken","matchPattern":"(","priority":1,"enabled":true},
              {"name":"Blank","matchPattern":"","priority":2,"enabled":true}
            ]
            """.trimIndent(),
        )

        assertEquals(2, result.items.size)
        assertEquals(2, result.disabledOnImport)
        assertFalse(result.items[0].enabled)
        assertFalse(result.items[1].enabled)
    }
}

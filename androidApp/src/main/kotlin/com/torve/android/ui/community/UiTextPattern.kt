package com.torve.android.ui.community

/** A complete app-owned sentence, never a word replacement inside provider/user content. */
internal class UiTextPattern(englishText: String, val resource: Int) {
    val english = englishText.replace("%%", "%")
    private val slots = Regex("%(?:(\\d+)\\$)?(?:0?\\d+)?([sdf])").findAll(english).toList()
    private val anchor = english.split(Regex("%(?:(\\d+)\\$)?(?:0?\\d+)?[sdf]"))
        .maxByOrNull { it.length }.orEmpty()
    private val pattern = Regex(buildString {
        var end = 0
        slots.forEach { slot ->
            append(Regex.escape(english.substring(end, slot.range.first)))
            append("(.*?)")
            end = slot.range.last + 1
        }
        append(Regex.escape(english.substring(end)))
    }, RegexOption.DOT_MATCHES_ALL)

    fun arguments(value: String): Array<Any>? {
        if (!value.contains(anchor)) return null
        val match = pattern.matchEntire(value) ?: return null
        val result = arrayOfNulls<Any>(slots.size)
        slots.forEachIndexed { index, slot ->
            val position = slot.groupValues[1].toIntOrNull()?.minus(1) ?: index
            if (position !in result.indices) return null
            val captured = match.groupValues[index + 1]
            result[position] = when (slot.groupValues[2]) {
                "d" -> captured.toIntOrNull() ?: return null
                "f" -> captured.toDoubleOrNull() ?: return null
                else -> captured
            }
        }
        if (result.any { it == null }) return null
        return result.requireNoNulls()
    }
}

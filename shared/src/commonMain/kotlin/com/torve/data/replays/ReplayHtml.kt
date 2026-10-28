package com.torve.data.replays

/** Small bounded HTML reader. Script contents are data; no JavaScript runs. */
internal object ReplayHtml {
    class Node(val tag: String, val attrs: Map<String, String> = emptyMap(), private val checkpoint: () -> Unit = {}) {
        val children = mutableListOf<Node>()
        var raw = ""
        private var classes: Set<String>? = null
        operator fun get(name: String) = attrs[name].orEmpty()
        fun hasClass(name: String): Boolean {
            checkpoint()
            val value = get("class")
            if (value.isEmpty()) return false
            return name in (classes ?: whitespace.split(value).toSet().also { classes = it })
        }
        fun all(): Sequence<Node> = sequence {
            val pending = ArrayDeque<Node>(); pending.addLast(this@Node)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (visited++ and 63 == 0) checkpoint()
                val node = pending.removeLast(); yield(node)
                for (i in node.children.lastIndex downTo 0) pending.addLast(node.children[i])
            }
        }
        fun text(): String {
            val result = StringBuilder()
            val pending = ArrayDeque<Node>(); pending.addLast(this)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (visited++ and 63 == 0) checkpoint()
                val node = pending.removeLast()
                if (node.tag in rawTags) continue
                if (node.raw.isNotEmpty()) { if (result.isNotEmpty()) result.append(' '); result.append(node.raw) }
                for (i in node.children.lastIndex downTo 0) pending.addLast(node.children[i])
            }
            return whitespace.replace(unescape(result.toString()), " ").trim()
        }
    }
    private val tagPattern = Regex("<(/?)([A-Za-z][A-Za-z0-9:_-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>")
    private val attribute = Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val whitespace = Regex("\\s+")
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);")
    private val rawTags = setOf("script", "style")
    private val void = setOf("img", "meta", "link", "input", "br", "hr", "source", "area", "embed", "wbr", "track")
    fun parse(html: String, checkpoint: () -> Unit = {}): Node {
        require(html.length <= 2_000_000) { "The website page is too large." }
        val root = Node("document", checkpoint = checkpoint); val stack = mutableListOf(root)
        var offset = 0; var nodes = 0
        while (offset < html.length && nodes < 30_000) {
            if (nodes and 63 == 0) checkpoint()
            val match = tagPattern.find(html, offset) ?: break
            stack.last().raw += html.substring(offset, match.range.first).take(32_768)
            offset = match.range.last + 1
            val tag = match.groupValues[2].lowercase()
            if (match.groupValues[1] == "/") {
                val index = stack.indexOfLast { it.tag == tag }
                if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                continue
            }
            val attrs = attribute.findAll(match.groupValues[3]).take(80).associate { a ->
                a.groupValues[1].lowercase() to unescape(a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value.orEmpty())
            }
            val node = Node(tag, attrs, checkpoint); stack.last().children += node; nodes++
            if (tag in rawTags) {
                val end = html.indexOf("</$tag", offset, ignoreCase = true)
                node.raw = html.substring(offset, if (end < 0) html.length else end)
                offset = if (end < 0) html.length else (html.indexOf('>', end).takeIf { it >= 0 }?.plus(1) ?: html.length)
            } else if (tag !in void && !match.groupValues[3].trimEnd().endsWith('/') && stack.size < 64) stack += node
        }
        return root
    }
    fun unescape(value: String): String = if ('&' !in value) value else entity.replace(value) {
        when (val name = it.groupValues[1]) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
            else -> {
                val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                when { code == null || code !in 1..0x10ffff -> it.value
                    code <= 0xffff -> code.toChar().toString()
                    else -> { val n = code - 0x10000; "${(0xd800 + (n shr 10)).toChar()}${(0xdc00 + (n and 1023)).toChar()}" }
                }
            }
        }
    }
}

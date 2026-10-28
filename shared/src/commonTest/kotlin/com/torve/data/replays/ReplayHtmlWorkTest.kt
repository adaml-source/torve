package com.torve.data.replays

import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ReplayHtmlWorkTest {
    @Test fun cancellationStopsParsingBeforeBuildingTheWholeTree() {
        val html = "<article><p class='replay-card'>Replay &amp; score</p></article>".repeat(5000)
        var checks = 0
        assertFailsWith<CancellationException> { ReplayHtml.parse(html) {
            if (++checks == 2) throw CancellationException("Replaced refresh")
        } }
        assertEquals(2, checks)
    }
    @Test fun cancellationAlsoStopsCatalogueTraversalAfterParsing() {
        var cancelled = false
        val tree = ReplayHtml.parse("<article><p>Title</p></article>".repeat(1000)) {
            if (cancelled) throw CancellationException("Profile changed")
        }
        cancelled = true
        assertFailsWith<CancellationException> { tree.all().toList() }
        assertFailsWith<CancellationException> { tree.text() }
    }
    @Test fun textIsDecodedOnceAndScriptContentsRemainExcluded() {
        val tree = ReplayHtml.parse("<h2>Paris <b>vs Valencia &amp;amp; Basket</b><script>Hidden result</script><style>Hidden CSS</style></h2>")
        val heading = tree.all().first { it.tag == "h2" }
        assertEquals("Paris vs Valencia &amp; Basket", heading.text())
        assertTrue(tree.all().any { it.tag == "script" && it.raw == "Hidden result" })
    }
    @Test fun classesAcceptWhitespaceAndTraversalKeepsDocumentOrder() {
        val tree = ReplayHtml.parse("<article class=' first\tsecond\nthird '><b>One</b><i>Two</i></article>")
        val article = tree.all().first { it.tag == "article" }
        assertTrue(article.hasClass("first")); assertTrue(article.hasClass("second")); assertTrue(article.hasClass("third"))
        assertFalse(article.hasClass("fir"))
        assertEquals(listOf("document", "article", "b", "i"), tree.all().map { it.tag }.toList())
    }
}

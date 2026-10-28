package com.torve.desktop.files

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class WebDavBrowserTest {
    private val root = URI("https://nas.test/videos/")
    private fun listing(vararg hrefs: String) = """<d:multistatus xmlns:d="DAV:">${hrefs.joinToString("") { href ->
        "<d:response><d:href>$href</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat></d:response>"
    }}</d:multistatus>""".toByteArray()
    @Test fun unicodeAndPlusNamesRemainReadable() {
        assertEquals(listOf("A+B.mkv", "Bayern München.mp4"), WebDavListingParser.parse(listing("/videos/Bayern%20M%C3%BCnchen.mp4", "/videos/A+B.mkv"), root, root).map { it.name })
    }
    @Test fun listingsCannotEscapeTheConfiguredFolder() {
        assertTrue(WebDavListingParser.parse(listing("https://other.test/videos/a.mp4", "/private/a.mp4", "/videos/%2e%2e/a.mp4",
            "/videos/a%2fb.mp4", "/videos/sub/nested.mp4", "/videos/a.mp4?token=x", "/videos/a.txt"), root, root).isEmpty())
    }
    @Test fun unsafeXmlOversizedResponsesAndHtmlAreRejected() {
        listOf("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///secret'>]><x/>".toByteArray(), "<html/>".toByteArray(), ByteArray(WebDavListingParser.MAX_BYTES + 1)).forEach { bytes ->
            assertFails { WebDavListingParser.parse(bytes, root, root) }
        }
    }
    @Test fun folderRequestsAreReadOnlyAndNeverFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0; var redirected = false
        var method: String? = null; var depth: String? = null; var authorization: String? = null
        server.createContext("/videos/") { exchange ->
            requests++; method = exchange.requestMethod; depth = exchange.requestHeaders.getFirst("Depth"); authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.set("Location", "/other/"); exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/other/") { exchange -> redirected = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val config = WebDavConnection("http://127.0.0.1:${server.address.port}/videos/", "fixture", "password")
            val error = assertFailsWith<WebDavException> { WebDavBrowser().list(config) }
            assertEquals(WebDavFailure.ADDRESS, error.reason); assertEquals("PROPFIND", method); assertEquals("1", depth)
            assertEquals(config.headers()["Authorization"], authorization); assertEquals(1, requests); assertFalse(redirected)
        } finally { server.stop(0) }
    }
}

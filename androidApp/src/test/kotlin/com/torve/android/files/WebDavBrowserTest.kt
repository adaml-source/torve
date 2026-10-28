package com.torve.android.files

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class WebDavBrowserTest {
    private val root = URI("https://nas.test/videos/")
    private fun listing(vararg hrefs: String) = """<d:multistatus xmlns:d="DAV:">${hrefs.joinToString("") { href ->
        "<d:response><d:href>$href</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat></d:response>"
    }}</d:multistatus>""".toByteArray()
    @Test fun unicodeAndPlusNamesRemainReadable() {
        val names = WebDavListingParser.parse(listing("/videos/Bayern%20M%C3%BCnchen.mp4", "/videos/A+B.mkv"), root, root).map { it.name }
        assertEquals(listOf("A+B.mkv", "Bayern München.mp4"), names)
    }
    @Test fun aListingCannotEscapeTheRootOrBorrowCredentials() {
        assertTrue(WebDavListingParser.parse(listing("https://other.test/videos/a.mp4", "/private/a.mp4", "/videos/%2e%2e/a.mp4",
            "/videos/a%2fb.mp4", "/videos/sub/nested.mp4", "/videos/a.mp4?token=x", "/videos/a.txt"), root, root).isEmpty())
    }
    @Test fun unsafeXmlAndHtmlCannotBecomeAFolderListing() {
        for (bytes in listOf("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///secret'>]><x/>".toByteArray(), "<html/>".toByteArray(), ByteArray(WebDavListingParser.MAX_BYTES + 1))) {
            assertThrows(Exception::class.java) { WebDavListingParser.parse(bytes, root, root) }
        }
    }
    @Test fun authenticationCheckUsesReadOnlyDepthOneAndDoesNotFollowRedirects() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++; assertEquals("PROPFIND", request.method.value); assertEquals("1", request.headers["Depth"])
            assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://other.test/files/"))
        }) { followRedirects = false }
        WebDavBrowser(client).use { browser ->
            try { browser.list(WebDavConnection(root.toString(), "person", "secret")); fail("Expected address error") }
            catch (error: WebDavException) { assertEquals(WebDavFailure.ADDRESS, error.reason) }
        }
        assertEquals(1, requests)
    }
    @Test fun fileAuthenticationHandoffIsExactOneShotAndProfileBound() {
        PendingAuthenticatedPlayback.stage("https://nas.test/a.mp4", "parent", mapOf("Authorization" to "secret"), 100)
        assertNull(PendingAuthenticatedPlayback.consume("https://nas.test/a.mp4", "child", 101))
        assertNull(PendingAuthenticatedPlayback.consume("https://nas.test/a.mp4", "parent", 102))
        PendingAuthenticatedPlayback.stage("https://nas.test/a.mp4", "parent", mapOf("Authorization" to "secret"), 100)
        assertNull(PendingAuthenticatedPlayback.consume("https://nas.test/a.mp4?extra", "parent", 101))
        PendingAuthenticatedPlayback.stage("https://nas.test/a.mp4", "parent", mapOf("Authorization" to "secret"), 100)
        assertNull(PendingAuthenticatedPlayback.consume("https://nas.test/a.mp4", "parent", 30_100))
    }
}

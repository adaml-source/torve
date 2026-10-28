package com.torve.desktop.files

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
@Serializable
data class WebDavConnection(val baseUrl: String, val username: String = "", val password: String = "") {
    override fun toString() = "WebDavConnection(credentials=redacted)"
    fun root(): URI {
        val uri = URI(baseUrl.trim()).normalize()
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "Use a full HTTP or HTTPS folder address" }
        require(!username.contains(':') && !username.contains('\r') && !username.contains('\n'))
        return URI(uri.toASCIIString().trimEnd('/') + "/")
    }
    fun headers(): Map<String, String> = if (username.isEmpty() && password.isEmpty()) emptyMap() else mapOf(
        "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8)))
}

data class WebDavEntry(val url: String, val name: String, val folder: Boolean, val size: Long? = null) {
    val mediaId: String get() = "webdav:" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

enum class WebDavFailure { AUTHENTICATION, ADDRESS, NETWORK }
class WebDavException(val reason: WebDavFailure) : Exception("WebDAV ${reason.name.lowercase()} failure")

internal fun sameWebDavOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
    (a.port.takeIf { it >= 0 } ?: if (a.scheme == "https") 443 else 80) ==
    (b.port.takeIf { it >= 0 } ?: if (b.scheme == "https") 443 else 80)

/** Accept only descendants of the configured root, including percent-encoded Unicode names. */
internal fun scopedWebDavUri(root: URI, folder: URI, href: String): URI? = runCatching {
    val uri = folder.resolve(URI(href.trim().replace(" ", "%20"))).normalize()
    if (!sameWebDavOrigin(root, uri) || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val parts = uri.rawPath.split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    if (parts.any { it == "." || it == ".." || it.contains('/') || it.contains('\\') || it.any { c -> c.code < 32 } }) return null
    if (!uri.path.startsWith(root.path)) return null
    URI(uri.toASCIIString())
}.getOrNull()

object WebDavListingParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val videos = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v")
    fun parse(bytes: ByteArray, root: URI, folder: URI): List<WebDavEntry> {
        require(bytes.size <= MAX_BYTES)
        val source = bytes.toString(Charsets.UTF_8)
        require(!source.contains('\u0000'))
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }
        val doc = builder.parse(ByteArrayInputStream(bytes))
        require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:")
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        require(responses.length <= 3000)
        return (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index) as Element
            val href = response.child("href")?.textContent ?: return@mapNotNull null
            val uri = scopedWebDavUri(root, folder, href) ?: return@mapNotNull null
            if (uri.path.trimEnd('/') == folder.path.trimEnd('/')) return@mapNotNull null
            // A Depth:1 server cannot smuggle nested descendants into this folder.
            if (uri.path.trimEnd('/').substringBeforeLast('/') + "/" != folder.path) return@mapNotNull null
            val props = response.children("propstat").firstOrNull {
                Regex("HTTP/\\S+ 2\\d\\d(?: .*|$)").matches(it.child("status")?.textContent?.trim().orEmpty())
            }?.child("prop") ?: return@mapNotNull null
            val isFolder = props.child("resourcetype")?.child("collection") != null
            if (!isFolder && uri.path.substringAfterLast('.').lowercase() !in videos) return@mapNotNull null
            val name = props.child("displayname")?.textContent?.trim()?.takeIf { it.isNotBlank() }
                ?: URLDecoder.decode(uri.rawPath.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            WebDavEntry(if (isFolder) uri.toASCIIString().trimEnd('/') + "/" else uri.toASCIIString(), name, isFolder,
                props.child("getcontentlength")?.textContent?.toLongOrNull()?.takeIf { it >= 0 })
        }.distinctBy { it.url }.sortedWith(compareByDescending<WebDavEntry> { it.folder }.thenBy { it.name.lowercase() })
    }
    private fun Element.children(name: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { it.namespaceURI == "DAV:" && it.localName == name }
    private fun Element.child(name: String) = children(name).firstOrNull()
}

class WebDavBrowser {
    private val client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .connectTimeout(java.time.Duration.ofSeconds(10)).build()

    suspend fun list(connection: WebDavConnection, folder: String = connection.root().toASCIIString()): List<WebDavEntry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val root = connection.root()
            val uri = scopedWebDavUri(root, root, folder) ?: throw WebDavException(WebDavFailure.ADDRESS)
            val request = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(30))
                .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", java.net.http.HttpRequest.BodyPublishers.ofString(
                    "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"))
            connection.headers().forEach { (key, value) -> request.header(key, value) }
            val response = try { client.send(request.build(), boundedListingBody()) }
                catch (_: Exception) { throw WebDavException(WebDavFailure.NETWORK) }
                if (response.statusCode() in setOf(401, 403)) throw WebDavException(WebDavFailure.AUTHENTICATION)
                if (response.statusCode() != 207) throw WebDavException(WebDavFailure.ADDRESS)
                val bytes = response.body()
                try { WebDavListingParser.parse(bytes, root, uri) }
                catch (_: Exception) { throw WebDavException(WebDavFailure.ADDRESS) }
        }

    private fun boundedListingBody() = java.net.http.HttpResponse.BodyHandler<ByteArray> {
        object : java.net.http.HttpResponse.BodySubscriber<ByteArray> {
            private val delegate = java.net.http.HttpResponse.BodySubscribers.ofByteArray()
            private var subscription: java.util.concurrent.Flow.Subscription? = null
            private var bytes = 0L
            override fun getBody(): java.util.concurrent.CompletionStage<ByteArray> = delegate.body
            override fun onSubscribe(value: java.util.concurrent.Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
            override fun onNext(items: List<java.nio.ByteBuffer>) {
                bytes += items.sumOf { it.remaining().toLong() }
                if (bytes > WebDavListingParser.MAX_BYTES) {
                    subscription?.cancel(); delegate.onError(IllegalArgumentException("Folder listing exceeded size limit"))
                } else delegate.onNext(items)
            }
            override fun onError(error: Throwable) = delegate.onError(error)
            override fun onComplete() = delegate.onComplete()
        }
    }
}

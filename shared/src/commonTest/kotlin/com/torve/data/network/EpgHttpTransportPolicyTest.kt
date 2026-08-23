package com.torve.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpgHttpTransportPolicyTest {
    @Test
    fun standardXmltvRedirectsReachBinaryMimePayload() = runTest {
        val redirectStatuses = listOf(
            HttpStatusCode.MovedPermanently,
            HttpStatusCode.Found,
            HttpStatusCode.SeeOther,
            HttpStatusCode.TemporaryRedirect,
            HttpStatusCode.PermanentRedirect,
        )

        redirectStatuses.forEach { redirectStatus ->
            var requests = 0
            val client = HttpClient(
                MockEngine { request ->
                    requests++
                    if (request.url.encodedPath == "/xml/source") {
                        respond(
                            content = "",
                            status = redirectStatus,
                            headers = headersOf(HttpHeaders.Location, "https://cdn.example.test/guide"),
                        )
                    } else {
                        respond(
                            content = "<tv><channel id=\"one\"/></tv>",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                        )
                    }
                },
            ) {
                configureEpgStreamingClient()
            }

            try {
                val response = client.get("https://provider.example.test/xml/source")
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().startsWith("<tv>"))
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun redirectLoopIsBounded() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://provider.example.test/loop"),
                )
            },
        ) {
            configureEpgStreamingClient()
        }

        try {
            val result = runCatching { client.get("https://provider.example.test/loop") }
            assertTrue(result.isFailure)
            assertTrue(requests in 2..25, "redirect loop should stop at the client's bounded send limit")
        } finally {
            client.close()
        }
    }
}

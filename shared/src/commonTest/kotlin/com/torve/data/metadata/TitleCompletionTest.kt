package com.torve.data.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TitleCompletionTest {
    // Ktor's engine uses a real dispatcher. Keep the production two-second
    // deadline on that clock rather than racing virtual-time advancement.
    private fun completionTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default, block)
    }
    @Test fun prefixCompletionsResolveCataloguePostersAndReuseIdentifierCache() = completionTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            val body = if (request.url.host == "v3.sg.media-imdb.com") {
                assertNull(request.url.parameters["api_key"], "Never send the catalogue credential to the completion host")
                assertTrue(request.url.encodedPath.startsWith("/suggestion/titles/x/"), "Use title-only completions so person names cannot crowd out matching shows")
                """{"d":[{"id":"nm99","qid":"person"},{"id":"tt2015381","qid":"movie","l":"Untrusted completion title"}]}"""
            } else """{"movie_results":[{"id":118340,"title":"Guardians of the Galaxy","adult":false,"poster_path":"/guardians.jpg"}]}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(client,apiKeyProvider={"fixture"},resolvedHostProvider={null}))
            for (q in listOf("gua", "guardian")) {
                val result = repo.titleSuggestions(q).last().single()
                assertEquals("Guardians of the Galaxy",result.title)
                assertEquals(false,result.adult)
                assertTrue(result.posterUrl!!.endsWith("/guardians.jpg"))
            }
            repo.titleSuggestions("guardian").last()
            assertEquals(3,urls.size,"Two prefix requests and one shared catalogue lookup; repeated query is cached")
        } finally { client.close() }
    }

    @Test fun completionRequestsRespectDisabledCatalogueAndIgnorePeople() = completionTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("{}") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(client,requestAllowed={false}))
            assertFailsWith<TmdbExtensionDisabledException> { repo.titleSuggestions("gua").last() }
            assertEquals(0,calls)
            assertNull(TitleCompletion("nm1","person").mediaType)
            assertEquals("tv",TitleCompletion("tt1","tvMiniSeries").mediaType)
        } finally { client.close() }
    }

    @Test fun aSlowCompletionDoesNotHoldBackAlreadyResolvedPosters() = completionTest {
        val gate = CompletableDeferred<Unit>(); val first = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.host == "v3.sg.media-imdb.com")
                """{"d":[{"id":"tt1","qid":"movie"},{"id":"tt2","qid":"movie"}]}"""
            else {
                if (request.url.encodedPath.endsWith("tt2")) gate.await()
                """{"movie_results":[{"id":1,"title":"Guardians","adult":false}]}"""
            }
            respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = MetadataRepositoryImpl(TmdbApiClient(client,resolvedHostProvider={null}))
            val job = launch { repo.titleSuggestions("gua").collect { if (it.isNotEmpty()) first.complete(Unit) } }
            first.await(); assertFalse(gate.isCompleted)
            gate.complete(Unit); job.join()
        } finally { client.close() }
    }
}

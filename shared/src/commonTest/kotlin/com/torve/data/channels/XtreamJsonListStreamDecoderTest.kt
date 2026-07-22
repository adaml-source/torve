package com.torve.data.channels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XtreamJsonListStreamDecoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesArrayIncrementallyAcrossEveryByteBoundary() {
        val body = """[
            {"id":1,"name":"Braces { stay } inside strings","tags":["a","b"]},
            null,
            {"id":2,"name":"escaped \" quote","tags":[]}
        ]""".trimIndent().encodeToByteArray()
        val decoder = decoder(maxBytes = body.size)

        body.forEach { byte -> decoder.consume(byteArrayOf(byte)) }

        assertEquals(
            listOf(
                TestRow(1, "Braces { stay } inside strings", listOf("a", "b")),
                TestRow(2, "escaped \" quote", emptyList()),
            ),
            decoder.finish(),
        )
    }

    @Test
    fun retainsSmallWrappedObjectCompatibility() {
        val body = """{"data":[{"id":7,"name":"wrapped"}]}""".encodeToByteArray()
        val decoder = decoder(maxBytes = body.size)

        decoder.consume(body)

        assertEquals(listOf(TestRow(7, "wrapped")), decoder.finish())
    }

    @Test
    fun rejectsResponsePastConfiguredLimitBeforeDecoding() {
        val decoder = decoder(maxBytes = 8)

        assertFailsWith<XtreamResponseTooLargeException> {
            decoder.consume("[{}]     ".encodeToByteArray())
        }
    }

    private fun decoder(maxBytes: Int) = XtreamJsonListStreamDecoder(
        json = json,
        deserializer = TestRow.serializer(),
        maxBytes = maxBytes,
    )

    @Serializable
    private data class TestRow(
        val id: Int,
        val name: String,
        val tags: List<String> = emptyList(),
    )
}

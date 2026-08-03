package com.torve.data.debrid

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorBoxResponseContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create torrent reads current torrent id field`() {
        val response = json.decodeFromString<TbResponse<TbTorrentData>>(
            """{"success":true,"detail":"Torrent Added Successfully","data":{"hash":"abc","torrent_id":42,"auth_id":"owner"}}""",
        )

        assertTrue(response.success)
        assertEquals(42L, response.data?.id)
    }

    @Test
    fun `torrent list reads current readiness and file fields`() {
        val response = json.decodeFromString<TbResponse<TbTorrentInfoData>>(
            """{"success":true,"data":{"id":42,"download_state":"cached","download_finished":true,"download_present":true,"cached":true,"files":[{"id":7,"name":"movie.mkv","size":1234,"short_name":"movie.mkv"}]}}""",
        )

        val torrent = response.data!!
        assertTrue(torrent.downloadFinished)
        assertTrue(torrent.downloadPresent)
        assertTrue(torrent.cached)
        assertEquals(7L, torrent.files.single().id)
    }

    @Test
    fun `request download reads url directly from data`() {
        val response = json.decodeFromString<TbResponse<String>>(
            """{"success":true,"detail":"Torrent download requested successfully.","data":"https://cdn.example/movie.mkv"}""",
        )

        assertEquals("https://cdn.example/movie.mkv", response.data)
    }
}

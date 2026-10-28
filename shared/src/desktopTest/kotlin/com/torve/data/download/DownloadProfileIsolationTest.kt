package com.torve.data.download

import com.torve.data.PersonalDeviceFixture
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DownloadProfileIsolationTest {
    private fun client() = HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
    @Test fun primaryProfileKeepsItsDownloadsAndAnotherProfileCannotReadOrCompleteThem() = runTest {
        PersonalDeviceFixture(client()).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, "PG", 2)
            val repo = DownloadRepositoryImpl(device.database, device.ids)
            repo.enqueueDownload(Download("download-one", "123", MediaType.MOVIE, "Film", streamUrl = "https://files.test/film.mp4"))
            assertEquals(1, repo.getAllDownloads().size)
            device.select("kids")
            assertNull(repo.getDownload("download-one")); assertTrue(repo.getAllDownloads().isEmpty())
            repo.markCompleted("download-one", "/unrelated.mp4")
            device.select("default")
            assertEquals(DownloadStatus.PENDING, repo.getDownload("download-one")?.status)
            assertNull(repo.getDownload("download-one")?.filePath)
        }
    }
    @Test fun pendingTransferCannotWriteProgressToTheNextProfile() = runTest {
        PersonalDeviceFixture(client()).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, "PG", 2)
            val repo = DownloadRepositoryImpl(device.database, device.ids)
            repo.enqueueDownload(Download("same-id", "123", MediaType.MOVIE, "Primary", streamUrl = "https://files.test/a.mp4"))
            device.select("kids")
            repo.enqueueDownload(Download("same-id", "456", MediaType.MOVIE, "Child", streamUrl = "https://files.test/b.mp4"))
            device.select("default")
            assertFailsWith<CancellationException> { device.ids.withPersonalDataScope {
                device.select("kids"); repo.updateProgress("same-id", 500, DownloadStatus.DOWNLOADING)
            } }
            assertEquals(0, repo.getDownload("same-id")?.downloadedBytes)
        }
    }
}

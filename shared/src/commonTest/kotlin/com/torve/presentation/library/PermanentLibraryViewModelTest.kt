package com.torve.presentation.library

import com.torve.data.integrations.AutomationAdminClient
import com.torve.data.integrations.AutomationConnectionResult
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationMediaKind
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.MediaLifecycleEntry
import com.torve.domain.integrations.MediaLifecycleRequest
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.integrations.MediaLifecycleStatus
import com.torve.domain.model.MediaType
import com.torve.domain.telemetry.AcquisitionRuntimeTelemetry
import com.torve.domain.telemetry.TelemetryEmitter
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermanentLibraryViewModelTest {
    @Test
    fun retryAndCancelUseTheExactQueueItemWithoutExposingCredentials() = runTest {
        AcquisitionRuntimeTelemetry.clearForTest()
        val instance = AutomationInstance(
            id = "radarr-main",
            serviceType = AutomationServiceType.RADARR,
            name = "Movies",
            serverUrl = "http://radarr.local",
        )
        val repository = MemoryAutomationRepository(instance, "super-secret-key")
        val admin = RecordingAutomationAdminClient()
        val telemetry = RecordingTelemetryEmitter()
        val viewModel = PermanentLibraryViewModel(
            lifecycleService = EmptyLifecycleService,
            instanceRepository = repository,
            adminClient = admin,
            telemetry = telemetry,
            scope = this,
        )

        runCurrent()
        val item = viewModel.state.value.items.single()
        assertTrue(item.canRetry)
        assertTrue(item.canCancel)

        viewModel.retryAcquisition(item.stableId)
        runCurrent()
        assertEquals(listOf(7L), admin.retriedQueueIds)
        assertTrue(viewModel.state.value.actionMessage.orEmpty().startsWith("Retry requested"))
        assertFalse(viewModel.state.value.actionMessage.orEmpty().contains("super-secret-key"))

        viewModel.cancelAcquisition(item.stableId)
        runCurrent()
        assertEquals(listOf(7L), admin.cancelledQueueIds)
        assertTrue(viewModel.state.value.actionMessage.orEmpty().startsWith("Download cancelled"))
        assertFalse(viewModel.state.value.actionMessage.orEmpty().contains("super-secret-key"))

        val health = AcquisitionRuntimeTelemetry.snapshot()
        assertEquals(3, health.refreshSuccesses)
        assertEquals(1, health.retryRequested)
        assertEquals(1, health.retrySucceeded)
        assertEquals(1, health.cancelRequested)
        assertEquals(1, health.cancelSucceeded)
        assertEquals(0, health.retryFailed)
        assertEquals(0, health.cancelFailed)

        val serializedEvents = telemetry.events.joinToString("|") { (event, attributes) ->
            "$event:${attributes.entries.joinToString()}"
        }
        assertTrue(serializedEvents.contains("acquisition_snapshot_refreshed"))
        assertTrue(serializedEvents.contains("acquisition_action"))
        assertFalse(serializedEvents.contains("Backrooms"))
        assertFalse(serializedEvents.contains("super-secret-key"))
        assertFalse(serializedEvents.contains("radarr.local"))
    }
}

private class RecordingTelemetryEmitter : TelemetryEmitter {
    val events = mutableListOf<Pair<String, Map<String, String>>>()

    override fun emit(event: String, attributes: Map<String, String>) {
        events += event to attributes
    }
}

private object EmptyLifecycleService : MediaLifecycleService {
    override suspend fun isConfigured(): Boolean = true
    override suspend fun testConnection(serverUrl: String, apiKey: String): Boolean = true
    override suspend fun getStatus(
        tmdbId: Int,
        mediaType: MediaType,
        seasons: List<Int>,
        is4k: Boolean,
    ): MediaLifecycleStatus = MediaLifecycleStatus(tmdbId, mediaType, MediaLifecycleState.NOT_REQUESTED)

    override suspend fun request(request: MediaLifecycleRequest): MediaLifecycleStatus =
        MediaLifecycleStatus(request.tmdbId, request.mediaType, MediaLifecycleState.PENDING_APPROVAL)

    override suspend fun retry(requestId: Int): MediaLifecycleStatus =
        MediaLifecycleStatus(0, MediaType.MOVIE, MediaLifecycleState.PROCESSING, requestId)

    override suspend fun listRecent(limit: Int): List<MediaLifecycleEntry> = emptyList()
}

private class MemoryAutomationRepository(
    private val instance: AutomationInstance,
    private val secret: String,
) : AutomationInstanceRepository {
    override suspend fun list(): List<AutomationInstance> = listOf(instance)
    override suspend fun save(instance: AutomationInstance, apiKey: String?) = Unit
    override suspend fun remove(instanceId: String) = Unit
    override suspend fun apiKey(instance: AutomationInstance): String = secret
}

private class RecordingAutomationAdminClient : AutomationAdminClient {
    val retriedQueueIds = mutableListOf<Long>()
    val cancelledQueueIds = mutableListOf<Long>()

    override suspend fun testConnection(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationConnectionResult = AutomationConnectionResult.Connected("test")

    override suspend fun listLibrary(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationLibraryItem>> = AutomationAdminResult.Success(
        listOf(
            AutomationLibraryItem(
                id = 42,
                kind = AutomationMediaKind.MOVIE,
                title = "Backrooms",
                externalId = 1234,
                monitored = true,
            ),
        ),
    )

    override suspend fun queue(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationAdminResult<List<AutomationQueueItem>> = AutomationAdminResult.Success(
        listOf(
            AutomationQueueItem(
                id = 7,
                title = "Backrooms",
                status = "warning",
                mediaId = 42,
                mediaKind = AutomationMediaKind.MOVIE,
                errorMessage = "Download client is unavailable",
            ),
        ),
    )

    override suspend fun retryQueueItem(
        instance: AutomationInstance,
        apiKey: String,
        queueId: Long,
    ): AutomationAdminResult<Unit> {
        retriedQueueIds += queueId
        return AutomationAdminResult.Success(Unit)
    }

    override suspend fun removeQueueItem(
        instance: AutomationInstance,
        apiKey: String,
        queueId: Long,
        removal: AutomationQueueRemoval,
    ): AutomationAdminResult<Unit> {
        cancelledQueueIds += queueId
        return AutomationAdminResult.Success(Unit)
    }
}

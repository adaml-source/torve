package com.torve.android.security

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException

class GooglePlayIntegrityTokenProvider(
    private val context: Context,
) : ClientIntegrityTokenProvider {
    override val providerName: String = "google_play_integrity"

    override suspend fun requestIntegrityToken(requestHash: String, cloudProjectNumber: Long): String? {
        if (requestHash.isBlank() || cloudProjectNumber <= 0) return null
        return try {
            val provider = IntegrityManagerFactory.createStandard(context)
                .prepareIntegrityToken(StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber).build()).awaitOrNull() ?: return null
            provider.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash).build()).awaitOrNull()?.token()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener {
            if (continuation.isActive) continuation.resume(null)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.resume(null)
        }
    }

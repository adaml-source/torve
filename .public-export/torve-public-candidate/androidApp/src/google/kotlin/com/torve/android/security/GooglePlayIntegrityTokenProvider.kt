package com.torve.android.security

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class GooglePlayIntegrityTokenProvider(
    private val context: Context,
) : ClientIntegrityTokenProvider {
    override val providerName: String = "google_play_integrity"

    override suspend fun requestIntegrityToken(nonce: String): String? {
        if (nonce.isBlank()) return null
        return runCatching {
            IntegrityManagerFactory.create(context)
                .requestIntegrityToken(
                    IntegrityTokenRequest.builder()
                        .setNonce(nonce)
                        .build(),
                )
                .awaitOrNull()
                ?.token()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
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

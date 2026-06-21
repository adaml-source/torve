package com.torve.android.security

interface ClientIntegrityTokenProvider {
    val providerName: String
    suspend fun requestIntegrityToken(nonce: String): String?
}

object NoOpClientIntegrityTokenProvider : ClientIntegrityTokenProvider {
    override val providerName: String = "none"

    override suspend fun requestIntegrityToken(nonce: String): String? = null
}

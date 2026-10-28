package com.torve.android.security

interface ClientIntegrityTokenProvider {
    val providerName: String
    suspend fun requestIntegrityToken(requestHash: String, cloudProjectNumber: Long = 0): String?
}

object NoOpClientIntegrityTokenProvider : ClientIntegrityTokenProvider {
    override val providerName: String = "none"

    override suspend fun requestIntegrityToken(requestHash: String, cloudProjectNumber: Long): String? = null
}

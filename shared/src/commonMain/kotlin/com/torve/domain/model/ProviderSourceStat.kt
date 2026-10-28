package com.torve.domain.model

/** Per lookup, without configured URLs, credentials, or an active network ping. */
data class ProviderSourceStat(val provider: String, val found: Int, val cached: Int,
    val responseMs: Long, val failed: Boolean = false, val fromCache: Boolean = false)

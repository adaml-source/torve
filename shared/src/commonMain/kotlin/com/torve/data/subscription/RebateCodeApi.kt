package com.torve.data.subscription

import io.ktor.client.HttpClient

/**
 * Deprecated compatibility surface for historical rebate/promo codes.
 *
 * Rebate, promo, founder, beta, lifetime, admin, community, donor, supporter,
 * and sponsor status must never unlock product features.
 */
class RebateCodeApi(@Suppress("unused") private val httpClient: HttpClient) {

    companion object {
        /** Rebate redemption is disabled in the free-software access model. */
        const val ENABLED = false
    }

    @Deprecated("Rebate codes no longer grant access; retained for platform compatibility.")
    suspend fun redeemCode(code: String, deviceId: String): RebateResult {
        return RebateResult.Error(message = "Rebate codes are deprecated and do not unlock features")
    }
}

sealed class RebateResult {
    data class Success(val type: String) : RebateResult()
    data class Error(val message: String) : RebateResult()
}

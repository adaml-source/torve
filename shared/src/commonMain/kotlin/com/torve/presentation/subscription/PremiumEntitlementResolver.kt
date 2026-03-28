package com.torve.presentation.subscription

import com.torve.domain.model.SubscriptionTier
import kotlinx.datetime.Instant

data class PremiumEntitlementRecord(
    val key: String,
    val status: String,
    val sourceStore: String,
    val endsAt: String? = null,
)

data class ResolvedPremiumEntitlement(
    val tier: SubscriptionTier,
    val hasEntitlement: Boolean,
    val expiresAtEpochMs: Long? = null,
    val sourceStore: String? = null,
)

fun resolvePremiumEntitlement(
    records: List<PremiumEntitlementRecord>,
    nowEpochMs: Long,
): ResolvedPremiumEntitlement {
    val activeRecords = records.filter { it.status.equals("active", ignoreCase = true) }
    val lifetime = activeRecords.firstOrNull { it.key == "torve_pro_lifetime" }
    if (lifetime != null) {
        return ResolvedPremiumEntitlement(
            tier = SubscriptionTier.LIFETIME,
            hasEntitlement = true,
            sourceStore = lifetime.sourceStore,
        )
    }

    val monthly = activeRecords
        .filter { it.key == "torve_pro_monthly" }
        .mapNotNull { record ->
            val endsAtEpochMs = record.endsAt?.let(::parseIsoToEpochMillis)
            if (endsAtEpochMs != null && endsAtEpochMs <= nowEpochMs) {
                null
            } else {
                record to endsAtEpochMs
            }
        }
        .maxByOrNull { (_, endsAtEpochMs) -> endsAtEpochMs ?: Long.MAX_VALUE }

    if (monthly != null) {
        return ResolvedPremiumEntitlement(
            tier = SubscriptionTier.MONTHLY,
            hasEntitlement = true,
            expiresAtEpochMs = monthly.second,
            sourceStore = monthly.first.sourceStore,
        )
    }

    return ResolvedPremiumEntitlement(
        tier = SubscriptionTier.FREE,
        hasEntitlement = false,
    )
}

private fun parseIsoToEpochMillis(value: String): Long? {
    return runCatching { Instant.parse(value).toEpochMilliseconds() }
        .getOrNull()
}

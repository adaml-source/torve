package com.torve.domain.subscription

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

    // Fallback for active entitlement records we don't recognise (admin
    // grants, future product ids, rebate codes the client hasn't been
    // taught about, etc.). Without this branch the UI falls through to
    // the bare "Premium active" catch-all and never surfaces the
    // ends_at date the backend did provide. Defaulting to MONTHLY when
    // an expiry exists / LIFETIME when it doesn't keeps the displayed
    // expiry honest while still admitting the user to premium.
    val unrecognised = activeRecords
        .mapNotNull { record ->
            val endsAtEpochMs = record.endsAt?.let(::parseIsoToEpochMillis)
            if (endsAtEpochMs != null && endsAtEpochMs <= nowEpochMs) null
            else record to endsAtEpochMs
        }
        .maxByOrNull { (_, endsAtEpochMs) -> endsAtEpochMs ?: Long.MAX_VALUE }
    if (unrecognised != null) {
        val (record, endsAtEpochMs) = unrecognised
        return ResolvedPremiumEntitlement(
            tier = if (endsAtEpochMs == null) SubscriptionTier.LIFETIME else SubscriptionTier.MONTHLY,
            hasEntitlement = true,
            expiresAtEpochMs = endsAtEpochMs,
            sourceStore = record.sourceStore,
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

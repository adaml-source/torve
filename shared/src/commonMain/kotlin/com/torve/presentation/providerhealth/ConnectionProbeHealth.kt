package com.torve.presentation.providerhealth

import com.torve.domain.integrations.ConnectionProbeResult
import com.torve.domain.providerhealth.ProviderHealthFailure

internal val ConnectionProbeResult.healthFailure: ProviderHealthFailure? get() = when (this) {
    ConnectionProbeResult.AUTH_REJECTED -> ProviderHealthFailure.AUTH_REJECTED
    ConnectionProbeResult.UNREACHABLE -> ProviderHealthFailure.UNREACHABLE
    ConnectionProbeResult.SETUP_INCOMPLETE -> ProviderHealthFailure.SETUP_INCOMPLETE
    else -> null
}

internal fun ConnectionProbeResult.failureMessage(provider: String): String = when (this) {
    ConnectionProbeResult.AUTH_REJECTED -> "$provider rejected the credentials. Update them in its connection settings."
    ConnectionProbeResult.UNREACHABLE -> "$provider is temporarily unavailable. Check your network or server, then try again."
    ConnectionProbeResult.SETUP_INCOMPLETE -> "Check the $provider server URL and finish its connection setup."
    else -> "Could not verify $provider. Check its server connection or API key."
}

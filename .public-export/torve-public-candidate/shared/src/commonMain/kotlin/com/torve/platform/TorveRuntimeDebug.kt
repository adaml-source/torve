package com.torve.platform

import com.torve.domain.diagnostics.DiagnosticsRedactor

object TorveRuntimeDebug {
    var verboseLoggingEnabled: Boolean = false
}

internal inline fun torveVerboseLog(message: () -> String) {
    if (TorveRuntimeDebug.verboseLoggingEnabled) {
        println(DiagnosticsRedactor.redact(message()))
    }
}

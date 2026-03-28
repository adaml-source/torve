package com.torve.platform

object TorveRuntimeDebug {
    @Volatile
    var verboseLoggingEnabled: Boolean = false
}

internal inline fun torveVerboseLog(message: () -> String) {
    if (TorveRuntimeDebug.verboseLoggingEnabled) {
        println(message())
    }
}

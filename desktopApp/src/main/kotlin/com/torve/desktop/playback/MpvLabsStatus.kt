package com.torve.desktop.playback

import com.torve.desktop.mpv.MpvRuntimeLocator

/**
 * Pure helper that turns MPV runtime discovery + the saved-preference
 * value into the strings + flags the Settings UI renders. Kept off
 * the Compose surface so the copy can be unit-tested without a UI
 * harness.
 */
object MpvLabsStatus {

    enum class State { AVAILABLE, UNAVAILABLE }

    data class Snapshot(
        val state: State,
        /**
         * Selectable engines in the Playback Engine selector. VLC is
         * always selectable; MPV is selectable only when libmpv was
         * discovered.
         */
        val selectableModes: List<DesktopPlayerMode>,
        /** What the engine selector should show as the active radio. */
        val effectiveMode: DesktopPlayerMode,
        /**
         * True iff the saved preference was MPV but libmpv is missing
         * - Main.kt rewrites the saved pref to VLC silently; this lets
         * Settings explain WHY the rail says VLC.
         */
        val wasResetFromMpv: Boolean,
        val title: String,
        val stateLabel: String,
        val description: String,
        val resetNotice: String?,
        val attemptedPaths: List<String>,
        val diagnosticMessage: String,
    )

    /**
     * Compute a fresh snapshot from a discovery result and the saved
     * preference. Caller is responsible for re-running discover() when
     * the user clicks "Re-check".
     */
    fun compute(
        discovery: MpvRuntimeLocator.DiscoveryResult,
        savedMode: DesktopPlayerMode,
    ): Snapshot {
        val state = if (discovery.found) State.AVAILABLE else State.UNAVAILABLE
        val selectable = if (discovery.found) {
            DesktopPlayerMode.entries.toList()
        } else {
            listOf(DesktopPlayerMode.VLC)
        }
        val effective = if (savedMode == DesktopPlayerMode.MPV && !discovery.found) {
            DesktopPlayerMode.VLC
        } else {
            savedMode
        }
        val wasReset = savedMode == DesktopPlayerMode.MPV && !discovery.found
        val stateLabel = when (state) {
            State.AVAILABLE -> "Available"
            State.UNAVAILABLE -> "Unavailable on this device"
        }
        // Premium copy - neutral + actionable. Avoids "missing" /
        // "failed" / "warning" wording the playback surface used to
        // surface to all users.
        val description = when (state) {
            State.AVAILABLE ->
                "MPV Labs is available. Selectable in the engine list above. " +
                    "Experimental - VLC remains recommended for the public beta."
            State.UNAVAILABLE ->
                "VLC is active and recommended. MPV Labs requires libmpv to be " +
                    "installed or staged with Torve."
        }
        val resetNotice = if (wasReset) {
            "VLC is active because MPV Labs is not available on this device."
        } else {
            null
        }
        return Snapshot(
            state = state,
            selectableModes = selectable,
            effectiveMode = effective,
            wasResetFromMpv = wasReset,
            title = "MPV Labs",
            stateLabel = stateLabel,
            description = description,
            resetNotice = resetNotice,
            attemptedPaths = discovery.attemptedPaths,
            diagnosticMessage = discovery.diagnosticMessage,
        )
    }

    /**
     * Builds the body of the in-app setup guide. Pure-string helper so
     * tests can assert that the actionable copy is present.
     */
    fun setupGuideBody(snapshot: Snapshot): String {
        return buildString {
            append("MPV Labs is optional. Torve ships VLC as the default desktop engine - ")
            append("VLC is the recommended choice for the public beta and works without ")
            append("any extra setup.")
            append("\n\n")
            append("If you want to try MPV Labs, place libmpv where Torve can find it. ")
            append("On launch, Torve searches these locations in order:")
            append("\n")
            if (snapshot.attemptedPaths.isEmpty()) {
                append("  • (no paths recorded - re-check after restarting)\n")
            } else {
                snapshot.attemptedPaths.forEach { p ->
                    append("  • ").append(p).append('\n')
                }
            }
            append("\n")
            append("You can also set ")
            append("`torve.desktop.mpv.path` (JVM property) ")
            append("or ")
            append("`TORVE_MPV_PATH` (env var) ")
            append("to point at an existing libmpv install.")
            append("\n\n")
            append("Last discovery: ")
            append(snapshot.diagnosticMessage)
        }
    }
}

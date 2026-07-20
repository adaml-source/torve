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
     * One-line "engine status row" copy for the Settings header that
     * Prompt 18 specifies. The default-engine case is intentionally
     * upbeat ("Default player ready") so a normal user sees a quiet,
     * positive line — not a "fallback" framing. The advanced-engine
     * case is informational, never alarming, even when libmpv is
     * absent.
     *
     * Pure-string helper so tests can assert the wording without
     * spinning up Compose.
     */
    fun engineStatusRow(snapshot: Snapshot): String {
        // The default engine is whatever the user is actually about to
        // play with — that's `effectiveMode`. VLC is the silent
        // default. Render it explicitly as "ready" rather than naming
        // the engine, because most users don't know or care which
        // engine name is which.
        val defaultLine = when (snapshot.effectiveMode) {
            DesktopPlayerMode.VLC -> "Default player ready."
            DesktopPlayerMode.MPV -> "Default player ready (Advanced MPV active)."
        }
        // The advanced-engine availability suffix. Hidden entirely
        // when MPV is the active engine — there's no "Advanced
        // unavailable" tail to add when Advanced is what they're
        // already on.
        val advancedSuffix = when {
            snapshot.effectiveMode == DesktopPlayerMode.MPV -> ""
            snapshot.state == State.AVAILABLE -> " Advanced MPV available."
            else -> " Advanced MPV unavailable on this device."
        }
        return defaultLine + advancedSuffix
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

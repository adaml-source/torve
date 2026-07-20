package com.torve.desktop.cast

import com.torve.desktop.vlc.VlcPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.renderer.RendererDiscoverer
import uk.co.caprica.vlcj.player.renderer.RendererDiscovererEventListener
import uk.co.caprica.vlcj.player.renderer.RendererItem

/**
 * Represents a castable device discovered by VLC.
 */
data class DesktopCastDevice(
    /** Stable identity - VLC doesn't expose IDs, so we key off (type, name). */
    val id: String,
    val name: String,
    val type: String,
    val iconUri: String?,
    internal val item: RendererItem,
)

data class DesktopCastState(
    /** Underlying VLC discoverers we started (name). Empty if runtime isn't ready. */
    val activeDiscoverers: List<String> = emptyList(),
    val devices: List<DesktopCastDevice> = emptyList(),
    val connectedDeviceId: String? = null,
    val error: String? = null,
)

/**
 * Manages Chromecast / DLNA / AirPlay device discovery and active-renderer
 * selection via VLC's libvlc_renderer_discoverer_* APIs.
 *
 * On most VLC builds the relevant discoverer is `microdns` (mDNS-based -
 * covers Chromecast, AirPlay, some DLNA renderers). We opportunistically
 * start every discoverer VLC exposes so behaviour degrades gracefully on
 * custom builds.
 */
class DesktopCastController(
    private val engine: VlcPlaybackEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DesktopCastState())
    val state: StateFlow<DesktopCastState> = _state.asStateFlow()

    private val discoverers = mutableListOf<RendererDiscoverer>()
    private var started = false

    /**
     * Enumerate and start every available renderer discoverer. Safe to call
     * multiple times - subsequent calls are no-ops once discovery is running.
     */
    fun startDiscovery() {
        if (started) return
        val descriptions = engine.rendererDescriptions()
        if (descriptions.isEmpty()) {
            _state.update { it.copy(error = "VLC runtime not ready yet.") }
            return
        }
        val startedNames = mutableListOf<String>()
        descriptions.forEach { desc ->
            val discoverer = engine.createDiscoverer(desc.name()) ?: return@forEach
            discoverer.events().addRendererDiscovererEventListener(
                object : RendererDiscovererEventListener {
                    override fun rendererDiscovererItemAdded(
                        source: RendererDiscoverer,
                        itemAdded: RendererItem,
                    ) {
                        val device = DesktopCastDevice(
                            id = deviceKey(itemAdded),
                            name = itemAdded.name() ?: "Device",
                            type = itemAdded.type() ?: "unknown",
                            iconUri = itemAdded.iconUri()?.takeIf { it.isNotBlank() },
                            item = itemAdded,
                        )
                        _state.update { current ->
                            val existing = current.devices.filter { it.id != device.id }
                            current.copy(devices = existing + device)
                        }
                    }

                    override fun rendererDiscovererItemDeleted(
                        source: RendererDiscoverer,
                        itemDeleted: RendererItem,
                    ) {
                        val key = deviceKey(itemDeleted)
                        _state.update { current ->
                            current.copy(
                                devices = current.devices.filter { it.id != key },
                                connectedDeviceId = current.connectedDeviceId?.takeIf { it != key },
                            )
                        }
                    }
                },
            )
            if (discoverer.start()) {
                discoverers += discoverer
                startedNames += desc.name()
            } else {
                discoverer.release()
            }
        }
        started = startedNames.isNotEmpty()
        _state.update {
            it.copy(
                activeDiscoverers = startedNames,
                error = if (startedNames.isEmpty()) "No cast protocols available in this VLC build." else null,
            )
        }
    }

    /**
     * Route the currently-playing media to [deviceId]. Returns false if the
     * device is no longer known or if there's no active playback session.
     */
    fun castTo(deviceId: String) {
        val device = _state.value.devices.firstOrNull { it.id == deviceId } ?: return
        scope.launch {
            val session = engine.session
            if (session == null) {
                _state.update { it.copy(error = "Start playback before casting.") }
                return@launch
            }
            val ok = runCatching { session.setRenderer(device.item) }.getOrDefault(false)
            _state.update {
                it.copy(
                    connectedDeviceId = if (ok) deviceId else it.connectedDeviceId,
                    error = if (ok) null else "Failed to connect to ${device.name}.",
                )
            }
        }
    }

    /** Stop casting - resume local playback. */
    fun disconnect() {
        scope.launch {
            runCatching { engine.session?.setRenderer(null) }
            _state.update { it.copy(connectedDeviceId = null, error = null) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Release every running discoverer + held RendererItems. Call during
     * shutdown / sign-out. After `shutdown()` a new `startDiscovery()` will
     * work again.
     */
    fun shutdown() {
        discoverers.forEach { d ->
            runCatching { d.stop() }
            runCatching { d.release() }
        }
        discoverers.clear()
        _state.value.devices.forEach { runCatching { it.item.release() } }
        _state.update { DesktopCastState() }
        started = false
    }

    private fun deviceKey(item: RendererItem): String {
        val name = item.name() ?: "device"
        val type = item.type() ?: "unknown"
        return "$type::$name"
    }
}

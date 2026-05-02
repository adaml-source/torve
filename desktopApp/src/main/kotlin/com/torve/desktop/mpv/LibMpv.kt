package com.torve.desktop.mpv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * JNA bindings for a minimal subset of libmpv's C API.
 *
 * We only bind the functions Torve actually drives - playback control,
 * options, properties, and the event pump. Render-API (mpv's
 * GL/Direct3D embedded rendering) is intentionally omitted from this
 * Stage 1 binding; that's Stage 3 work because it requires a Compose
 * Skia / GLFW interop layer.
 *
 * The library name is `mpv-2` on Windows / Linux (libmpv-2.dll /
 * libmpv.so.2) and `mpv` on macOS (libmpv.2.dylib). JNA's `Native.load`
 * goes through the OS's normal library-search rules.
 *
 * Loading is intentionally lazy + try-catching at the resolver layer
 * because libmpv may not be installed; the engine surfaces a clean
 * "not found" runtime probe when it isn't.
 */
/**
 * Mirror of libmpv's `struct mpv_event` (client.h). Field order and
 * widths are stable ABI; we only read [event_id] and [error] in normal
 * flow - the others are present so the layout matches what mpv writes.
 */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
internal open class MpvEvent : Structure {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

/**
 * Mirror of `struct mpv_event_end_file`. `data` on an END_FILE event
 * points at one of these. The fields after `error` were added later
 * in the libmpv ABI but are read-only and won't break older builds.
 */
@Structure.FieldOrder(
    "reason",
    "error",
    "playlist_entry_id",
    "playlist_insert_id",
    "playlist_insert_num_entries",
)
internal open class MpvEventEndFile : Structure {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

/**
 * Mirror of `struct mpv_event_property`. `data` on a PROPERTY_CHANGE
 * event points here.
 */
@Structure.FieldOrder("name", "format", "data")
internal open class MpvEventProperty : Structure {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

/**
 * Mirror of `struct mpv_event_log_message`. `data` on a LOG_MESSAGE
 * event points here. `text` already includes a trailing newline.
 */
@Structure.FieldOrder("prefix", "level", "text", "log_level")
internal open class MpvEventLogMessage : Structure {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    @JvmField var log_level: Int = 0

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): String?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent?
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_client_api_version(): Int

    companion object {
        /** Format constants from libmpv's `client.h`. */
        const val FORMAT_NONE: Int = 0
        const val FORMAT_STRING: Int = 1
        const val FORMAT_FLAG: Int = 3
        const val FORMAT_INT64: Int = 4
        const val FORMAT_DOUBLE: Int = 5

        /** Subset of `mpv_event_id`s we care about. Values are stable ABI. */
        const val EVENT_NONE: Int = 0
        const val EVENT_SHUTDOWN: Int = 1
        const val EVENT_LOG_MESSAGE: Int = 2
        const val EVENT_START_FILE: Int = 6
        const val EVENT_END_FILE: Int = 7
        const val EVENT_FILE_LOADED: Int = 8
        const val EVENT_PLAYBACK_RESTART: Int = 21
        const val EVENT_PROPERTY_CHANGE: Int = 22

        /** `mpv_end_file_reason` enum values, stable ABI. */
        const val END_FILE_REASON_EOF: Int = 0
        const val END_FILE_REASON_STOP: Int = 2
        const val END_FILE_REASON_QUIT: Int = 3
        const val END_FILE_REASON_ERROR: Int = 4
        const val END_FILE_REASON_REDIRECT: Int = 5

        /**
         * Latest result from [loadOrNull] - exposed so the Settings UI can
         * show a "libmpv detected at <path>" status row without redoing the
         * (cheap but synchronous) probe.
         */
        @Volatile
        var lastDiscovery: MpvRuntimeLocator.DiscoveryResult? = null
            private set

        /**
         * Try the bundled / configured directories first (via
         * [MpvRuntimeLocator]) so the OS search path is only consulted as a
         * last resort. Each OS spells the library differently - try every
         * common variant.
         */
        fun loadOrNull(): LibMpv? {
            val discovery = MpvRuntimeLocator.discover()
            lastDiscovery = discovery
            if (discovery.found) MpvRuntimeLocator.apply(discovery)
            val candidates = listOf("mpv-2", "mpv", "libmpv-2", "libmpv.2")
            for (name in candidates) {
                val loaded = runCatching { Native.load(name, LibMpv::class.java) }
                    .onSuccess { return@loadOrNull it }
                    .onFailure { /* try next */ }
                @Suppress("UNUSED_VARIABLE")
                val _ignore = loaded
            }
            return null
        }
    }
}

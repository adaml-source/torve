package com.torve.android.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.torve.android.sync.model.TorveRemoteCommand
import java.lang.ref.WeakReference

data class TorveRemoteTransport(val playPause: () -> Unit, val stop: () -> Unit, val seek: (Long) -> Unit)

/** Dispatch only to Torve's own foreground window; this never injects OS/global input. */
object TorveRemoteInput {
    private data class Surface(val owner: Any, val view: WeakReference<View>, val activity: WeakReference<ComponentActivity>?,
        val onBack: (() -> Unit)?)
    private val surfaces = mutableListOf<Surface>()
    private var transport: Pair<Any, TorveRemoteTransport>? = null
    @Synchronized fun register(owner: Any, view: View, activity: ComponentActivity?, onBack: (() -> Unit)? = null) {
        surfaces.removeAll { it.owner === owner || it.view.get() == null }
        surfaces += Surface(owner, WeakReference(view), activity?.let(::WeakReference), onBack)
    }
    @Synchronized fun unregister(owner: Any) { surfaces.removeAll { it.owner === owner || it.view.get() == null } }
    @Synchronized fun hasForegroundSurface(): Boolean = surfaces.any { it.view.get()?.let { view -> view.isAttachedToWindow && view.hasWindowFocus() } == true }
    fun registerTransport(owner: Any, value: TorveRemoteTransport) { transport = owner to value }
    fun unregisterTransport(owner: Any) { if (transport?.first === owner) transport = null }
    fun dispatch(command: TorveRemoteCommand, seekMs: Long = 10_000L): Boolean {
        val surface = synchronized(this) { surfaces.lastOrNull { it.view.get()?.let { view -> view.isAttachedToWindow && view.hasWindowFocus() } == true } } ?: return false
        val target = surface.view.get() ?: return false
        if (command == TorveRemoteCommand.BACK) {
            surface.onBack?.let { it(); return true }
            surface.activity?.get()?.takeIf { target.rootView === it.window.decorView }
                ?.let { it.onBackPressedDispatcher.onBackPressed(); return true }
        }
        val controls = transport?.second
        when (command) {
            TorveRemoteCommand.PLAY_PAUSE -> if (controls != null) { controls.playPause(); return true }
            TorveRemoteCommand.STOP -> if (controls != null) { controls.stop(); return true }
            TorveRemoteCommand.SEEK_BACK, TorveRemoteCommand.SEEK_FORWARD -> if (controls != null) {
                controls.seek(seekMs.coerceIn(1_000, 60_000) * if (command == TorveRemoteCommand.SEEK_BACK) -1 else 1); return true
            }
            else -> Unit
        }
        val key = when (command) {
            TorveRemoteCommand.UP -> KeyEvent.KEYCODE_DPAD_UP
            TorveRemoteCommand.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            TorveRemoteCommand.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            TorveRemoteCommand.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            TorveRemoteCommand.SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
            TorveRemoteCommand.BACK -> KeyEvent.KEYCODE_BACK
            TorveRemoteCommand.MENU -> KeyEvent.KEYCODE_MENU
            TorveRemoteCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return false
        }
        val at = SystemClock.uptimeMillis()
        val activity = surface.activity?.get()
        val dispatch: (KeyEvent) -> Boolean = if (activity != null && target.rootView === activity.window.decorView) activity::dispatchKeyEvent else target.rootView::dispatchKeyEvent
        val down = dispatch(KeyEvent(at, at, KeyEvent.ACTION_DOWN, key, 0))
        val up = dispatch(KeyEvent(at, at, KeyEvent.ACTION_UP, key, 0))
        return down || up
    }
}

@Composable
fun TorveRemoteInputSurface(onBack: (() -> Unit)? = null) {
    val view = LocalView.current; val context = LocalContext.current
    val currentBack by rememberUpdatedState(onBack)
    val owner = remember { Any() }
    DisposableEffect(view) {
        TorveRemoteInput.register(owner, view, context.componentActivity(), if (onBack == null) null else { { currentBack?.invoke() } })
        onDispose { TorveRemoteInput.unregister(owner) }
    }
}

@Composable
fun TorveRemoteTransportSurface(value: TorveRemoteTransport) {
    val owner = remember { Any() }
    SideEffect { TorveRemoteInput.registerTransport(owner, value) }
    DisposableEffect(Unit) { onDispose { TorveRemoteInput.unregisterTransport(owner) } }
}

private fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}

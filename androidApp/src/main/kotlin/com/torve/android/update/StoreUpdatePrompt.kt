package com.torve.android.update

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.torve.android.R

@Composable
internal fun StoreUpdatePrompt(controller: StoreUpdateController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    if (!state.showPrompt || WindowInsets.ime.getBottom(LocalDensity.current) > 0) return
    val ready = state.phase == StoreUpdatePhase.DOWNLOADED
    if (!ready && state.phase != StoreUpdatePhase.AVAILABLE) return
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(stringResource(if (ready) R.string.play_update_ready else R.string.play_update_available), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (ready) R.string.play_update_ready_body else R.string.play_update_available_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::dismissPrompt) { Text(stringResource(R.string.play_update_later)) }
                TextButton(onClick = if (ready) controller::completeUpdate else controller::beginUpdate) {
                    Text(stringResource(if (ready) R.string.play_update_restart else R.string.play_update_action))
                }
            }
        }
    }
}

@Composable
internal fun StoreUpdateSettings(controller: StoreUpdateController) {
    val state by controller.state.collectAsState()
    if (!state.enabled) return
    val context = LocalContext.current
    val notificationManager = remember(context) { NotificationManagerCompat.from(context) }
    var notificationsEnabled by remember { mutableStateOf(notificationManager.areNotificationsEnabled()) }
    val reminderPrefs = remember(context) { context.getSharedPreferences("google_play_update_reminders", android.content.Context.MODE_PRIVATE) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, notificationManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = notificationManager.areNotificationsEnabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // WorkManager also retries on the next scheduled check, even if the app is closed.
        notificationsEnabled = notificationManager.areNotificationsEnabled()
        controller.checkNow()
    }
    val status = when (state.phase) {
        StoreUpdatePhase.CHECKING -> R.string.play_update_checking
        StoreUpdatePhase.UP_TO_DATE -> R.string.play_update_current
        StoreUpdatePhase.AVAILABLE -> R.string.play_update_available
        StoreUpdatePhase.DOWNLOADING -> R.string.play_update_downloading
        StoreUpdatePhase.DOWNLOADED -> R.string.play_update_ready
        StoreUpdatePhase.INSTALLING -> R.string.play_update_installing
        StoreUpdatePhase.CHECK_FAILED -> R.string.play_update_check_failed
        StoreUpdatePhase.IDLE -> R.string.play_update_check_description
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.play_update_settings), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.phase == StoreUpdatePhase.DOWNLOADING && state.totalBytes > 0) {
            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        val action = when (state.phase) {
            StoreUpdatePhase.AVAILABLE -> R.string.play_update_action
            StoreUpdatePhase.DOWNLOADED -> R.string.play_update_restart
            else -> R.string.play_update_check
        }
        TextButton(onClick = {
            when (state.phase) {
                StoreUpdatePhase.AVAILABLE -> controller.beginUpdate()
                StoreUpdatePhase.DOWNLOADED -> controller.completeUpdate()
                else -> controller.checkNow()
            }
        }, enabled = state.phase !in setOf(StoreUpdatePhase.CHECKING, StoreUpdatePhase.DOWNLOADING, StoreUpdatePhase.INSTALLING)) { Text(stringResource(action)) }
        if (!notificationsEnabled) {
            Text(stringResource(R.string.play_update_notifications_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                val activity = context as? Activity
                val asked = reminderPrefs.getBoolean("notification_permission_asked", false)
                val canAsk = !asked || (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canAsk) {
                    reminderPrefs.edit().putBoolean("notification_permission_asked", true).apply()
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }
            }) { Text(stringResource(R.string.play_update_enable_notifications)) }
        }
    }
}

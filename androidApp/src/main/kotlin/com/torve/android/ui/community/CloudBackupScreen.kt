package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.torve.android.background.CloudBackupScheduling
import com.torve.android.ui.product.ProductPage
import com.torve.data.auth.AuthClient
import com.torve.data.profile.CloudBackupFailure
import com.torve.data.profile.CloudLibraryBackup
import com.torve.data.profile.CloudLibraryBackups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun CloudBackupScreen(onBack: () -> Unit, onSignIn: () -> Unit, isTv: Boolean = false,
    client: CloudLibraryBackups = koinInject(), auth: AuthClient = koinInject()) {
    val user by auth.authUserFlow.collectAsState()
    val context = LocalContext.current
    val owner = user?.id
    key(owner) {
        var versions by remember { mutableStateOf<List<CloudLibraryBackup>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var automatic by remember { mutableStateOf(user?.id?.let { CloudBackupScheduling.automatic(context, it) } ?: false) }
        val scope = rememberCoroutineScope()
        val entry = remember { FocusRequester() }
        fun action(block: suspend () -> Unit) {
            if (busy) return
            busy = true; message = null
            scope.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: CloudBackupFailure) { message = failure.message }
                catch (failure: Exception) {
                    message = if (failure.message.orEmpty().contains("BACKUP_PROFILE_PIN_UNAVAILABLE"))
                        "A protected profile’s PIN could not be recovered. Your library was not changed."
                    else "Could not finish the backup operation. Your existing data has been kept. Please try again."
                } finally { busy = false }
            }
        }
        LaunchedEffect(user?.id) {
            if (user != null) action { versions = client.list() }
        }
        LaunchedEffect(isTv, user?.id) { if (isTv) { withFrameNanos { }; entry.requestFocus() } }
        ProductPage(localizedUiText("Cloud backup"), onBack = onBack) {
            CloudBackupContent(versions, busy, message, user != null, user?.isVerified == true, automatic,
                onSignIn = onSignIn, onRefresh = { action { versions = client.list() } },
                onCreate = { action {
                    client.create(CloudBackupScheduling.platform(context))
                    owner?.takeIf { auth.authUserFlow.value?.id == it }?.let { CloudBackupScheduling.recordSuccess(context, it) }
                    versions = client.list(); message = "Backup saved."
                } },
                onAutomatic = { enabled -> user?.id?.let { CloudBackupScheduling.setAutomatic(context, it, enabled); automatic = enabled } },
                onRestore = { version -> action {
                    client.restore(version.id, CloudBackupScheduling.platform(context))
                    owner?.takeIf { auth.authUserFlow.value?.id == it }?.let { CloudBackupScheduling.recordSuccess(context, it) }
                    versions = client.list(); message = "Library and preferences restored. Your connections were kept."
                } }, entry = if (isTv) entry else null)
        }
    }
}

@Composable
internal fun CloudBackupContent(versions: List<CloudLibraryBackup>, busy: Boolean, message: String?,
    signedIn: Boolean, verified: Boolean, automatic: Boolean,
    onSignIn: () -> Unit, onRefresh: () -> Unit, onCreate: () -> Unit, onAutomatic: (Boolean) -> Unit,
    onRestore: (CloudLibraryBackup) -> Unit, entry: FocusRequester? = null) {
    var selected by remember { mutableStateOf<CloudLibraryBackup?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(localizedUiText("Save versions of your library and preferences. Connections and credentials are kept separately."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!signedIn) item {
            Button(onClick = onSignIn, modifier = Modifier.heightIn(min = 56.dp).then(entry?.let { Modifier.focusRequester(it) } ?: Modifier)) { Text(localizedUiText("Sign in")) }
            Text(localizedUiText("Sign in to save and restore your account backups."))
        } else {
            item {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 56.dp).then(entry?.let { Modifier.focusRequester(it) } ?: Modifier)) { Text(localizedUiText("Refresh")) }
                if (!verified) Text(localizedUiText("Confirm your account email before using cloud backup."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onCreate, enabled = verified && !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(localizedUiText("Back up now")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localizedUiText("Automatic daily backup"), Modifier.weight(1f))
                    Switch(automatic, onCheckedChange = onAutomatic, enabled = verified && !busy, modifier = Modifier.semantics { contentDescription = "Automatic daily backup" })
                }
            }
            item { Text(localizedUiText("Saved versions"), style = MaterialTheme.typography.titleLarge) }
            if (versions.isEmpty() && !busy) item { Text(localizedUiText("No cloud backups yet."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(versions, key = { it.id }) { version ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(remember(version.createdAt) { runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(version.createdAt)) }.getOrDefault(version.createdAt) }, style = MaterialTheme.typography.titleMedium)
                        Text(localizedUiText("${version.counts["profile"] ?: 0} profiles · ${version.counts["watchlist"] ?: 0} saved · ${version.counts["history"] ?: 0} watched · ${version.counts["progress"] ?: 0} in progress"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { selected = version }, enabled = verified && !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(localizedUiText("Restore this version")) }
                    }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { item { Text(localizedUiText(it)) } }
        item { Spacer(Modifier.height(64.dp)) }
    }
    selected?.let { version -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(localizedUiText("Restore this backup?")) },
        text = { Text(localizedUiText("A safety backup is saved before restoring. Your connections will be kept.")) },
        confirmButton = { TextButton(onClick = { selected = null; onRestore(version) }) { Text(localizedUiText("Restore")) } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text(localizedUiText("Cancel")) } }) }
}

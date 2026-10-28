package com.torve.desktop.ui.v2.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.desktop.files.*
import com.torve.desktop.ui.components.*
import com.torve.desktop.ui.l10n.ds
import com.torve.domain.integrations.*
import com.torve.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform

@Composable
fun DesktopFilesBrowser(onPlay: (WebDavEntry, WebDavConnection) -> Unit) {
    val scope = rememberCoroutineScope()
    val secrets = remember { KoinPlatform.getKoin().get<IntegrationSecretStore>() }
    val ids = remember { KoinPlatform.getKoin().get<UserIdProvider>() }
    val profiles = remember { KoinPlatform.getKoin().get<ProfileRepository>() }
    val owner = remember { ids.currentDataUserId() }
    val browser = remember { WebDavBrowser() }
    var connection by remember { mutableStateOf<WebDavConnection?>(null) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<WebDavEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var restricted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    suspend fun load(config: WebDavConnection, destination: String, save: Boolean = false) {
        if (ids.currentDataUserId() != owner) return
        loading = true; error = null
        try {
            ids.withPersonalDataScope {
                if (profiles.getActiveProfile()?.maxContentRating != null) { restricted = true; return@withPersonalDataScope }
                val next = browser.list(config, destination)
                if (owner != ids.currentDataUserId()) return@withPersonalDataScope
                if (save) {
                    secrets.setStorageMode(IntegrationSecretKey.WEBDAV_CONNECTION, IntegrationStorageMode.DEVICE_ONLY)
                    secrets.put(IntegrationSecretKey.WEBDAV_CONNECTION, json.encodeToString(config))
                }
                connection = config; folder = destination; entries = next; password = ""
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = when ((failure as? WebDavException)?.reason) {
                WebDavFailure.AUTHENTICATION -> "The server rejected these credentials. Check your username and password."
                WebDavFailure.NETWORK -> "Could not reach this server. Check your connection and try again."
                else -> "Use a valid WebDAV folder address. Redirected or invalid folder listings are not accepted."
            }
        } finally { loading = false }
    }
    LaunchedEffect(owner) {
        try {
            ids.withPersonalDataScope {
                restricted = profiles.getActiveProfile()?.maxContentRating != null
                if (!restricted) secrets.get(IntegrationSecretKey.WEBDAV_CONNECTION)?.let { saved ->
                    val config = json.decodeFromString<WebDavConnection>(saved)
                    url = config.baseUrl; username = config.username
                    load(config, config.root().toASCIIString())
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load the saved WebDAV connection." }
        finally { loading = false }
    }
    TorveSectionCard(ds("Network files"), supportingText = ds("Browse and play files from your WebDAV server. Credentials stay on this device, within this profile.")) {
        if (restricted) { Text(ds("Network files are unavailable in profiles with a content-rating limit.")); return@TorveSectionCard }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
        if (connection == null) {
            TorveTextField(url, { url = it }, ds("WebDAV folder URL"), enabled = !loading)
            TorveTextField(username, { username = it }, ds("Username"), enabled = !loading)
            TorveTextField(password, { password = it }, ds("Password"), enabled = !loading, visualTransformation = PasswordVisualTransformation())
            TorvePrimaryButton(ds("Connect"), {
                scope.launch {
                    try { val config = WebDavConnection(url, username, password); load(config, config.root().toASCIIString(), save = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Use a valid WebDAV folder address."; loading = false }
                }
            }, enabled = !loading && url.isNotBlank())
        } else {
            val config = connection!!
            Text(java.net.URI(folder).path, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveGhostButton(ds("Parent folder"), { scope.launch { load(config, java.net.URI(folder).resolve("../").toASCIIString()) } }, enabled = !loading && folder != config.root().toASCIIString())
                TorveGhostButton(ds("Refresh"), { scope.launch { load(config, folder) } }, enabled = !loading)
                TorveGhostButton(ds("Disconnect"), { scope.launch { ids.withPersonalDataScope {
                    if (owner == ids.currentDataUserId()) { secrets.remove(IntegrationSecretKey.WEBDAV_CONNECTION); connection = null; entries = emptyList(); password = "" }
                } } }, enabled = !loading)
            }
            if (entries.isEmpty() && !loading) Text(ds("No video files or folders found."))
            entries.forEach { entry ->
                TorveListRow(entry.name, if (entry.folder) ds("Folder") else entry.size?.let { "${it / (1024 * 1024)} MB" }.orEmpty(),
                    onClick = if (loading) null else { {
                        if (entry.folder) scope.launch { load(config, entry.url) }
                        else scope.launch { ids.withPersonalDataScope {
                            if (owner == ids.currentDataUserId() && profiles.getActiveProfile()?.maxContentRating == null && scopedWebDavUri(config.root(), config.root(), entry.url) != null) onPlay(entry, config)
                        } }
                    } })
            }
        }
    }
}

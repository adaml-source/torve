package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.device.DeviceFormFactor
import com.torve.android.ui.theme.Obsidian
import com.torve.presentation.settings.SettingsViewModel
import com.torve.data.auth.withPersonalDataScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.net.URL
import java.security.MessageDigest

data class PersonalAppearance(val preset: String = "standard", val image: String? = null, val owner: String = "") {
    val active get() = preset != "standard"
}
val LocalPersonalAppearance = staticCompositionLocalOf { PersonalAppearance() }

private val presets = linkedMapOf("standard" to "Default", "none" to "None · Plain dark background", "gold" to "Golden glow", "ocean" to "Ocean glow",
    "orchid" to "Orchid glow", "forest" to "Forest glow", "ember" to "Ember glow", "image" to "Your image")

@Composable
fun PersonalAppearanceProvider(settings: SettingsViewModel = koinInject(), content: @Composable () -> Unit) {
    val state by settings.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.personalDataUserId, state.tmdbExtensionsLoaded) {
        if (state.tmdbExtensionsLoaded) {
            com.torve.android.background.CloudBackupScheduling.refresh(context)
            val koin = org.koin.mp.KoinPlatform.getKoin()
            koin.get<com.torve.data.auth.UserIdProvider>().withPersonalDataScope {
                com.torve.presentation.apple.AppleNativeFeatures(koin)
                    .initializeAppearance(com.torve.presentation.settings.ThemeMode.DARK)
            }
        }
    }

    val owner = remember(state.personalDataUserId) {
        MessageDigest.getInstance("SHA-256").digest((state.personalDataUserId ?: "guest").toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val prefs = remember { context.getSharedPreferences("personal_appearance_v1", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val appearance = remember(owner, revision) {
        PersonalAppearance(prefs.getString("$owner.preset", "standard") ?: "standard", prefs.getString("$owner.image", null), owner)
    }
    CompositionLocalProvider(LocalPersonalAppearance provides appearance, LocalArtworkScope provides "$owner:${state.appLanguage.code}") {
        MaterialTheme(colorScheme = if (appearance.active) MaterialTheme.colorScheme.copy(background = Color.Transparent) else MaterialTheme.colorScheme) {
            PersonalBackground(content)
        }
    }
}

@Composable
fun PersonalBackground(content: @Composable () -> Unit) {
    val appearance = LocalPersonalAppearance.current
    val base = if (appearance.preset == "none") Obsidian else MaterialTheme.colorScheme.background.takeUnless { it == Color.Transparent }
        ?: MaterialTheme.colorScheme.surface
    val tint = when (appearance.preset) {
        "gold" -> Color(0xFFDA991F); "ocean" -> Color(0xFF0D6BD9); "orchid" -> Color(0xFF9E38BF)
        "forest" -> Color(0xFF0A9159); "ember" -> Color(0xFFCC3826); else -> Color.Transparent
    }
    Box(Modifier.fillMaxSize().background(base)) {
        if (appearance.preset == "image" && appearance.image != null) {
            AsyncImage(appearance.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(base.copy(alpha = .72f)))
        } else if (appearance.active && appearance.preset != "none") {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint.copy(alpha = .30f), base, tint.copy(alpha = .10f)))))
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

@Composable
fun AppearanceControls(onBack: () -> Unit, settings: SettingsViewModel = koinInject()) {
    val state by settings.state.collectAsState()
    val appearance = LocalPersonalAppearance.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("personal_appearance_v1", 0) }
    val scope = rememberCoroutineScope()
    val backEntry = communityEntryFocus()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun importImage(read: () -> ByteArray) {
        busy = true; error = null
        try {
            val path = withContext(Dispatchers.IO) {
                val data = read()
                require(data.size <= 20 * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: kotlin.error("Invalid image")
                val file = File(context.noBackupFilesDir, "backgrounds/${appearance.owner}-${System.nanoTime()}.jpg")
                file.parentFile?.mkdirs()
                try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                finally { bitmap.recycle() }
                file.absolutePath
            }
            // The captured owner keeps an in-flight import in its original profile.
            val previous = prefs.getString("${appearance.owner}.image", null)
            prefs.edit().putString("${appearance.owner}.image", path).putString("${appearance.owner}.preset", "image").apply()
            withContext(Dispatchers.IO) { previous?.let { File(it).takeIf { it.parentFile?.canonicalFile == File(context.noBackupFilesDir, "backgrounds").canonicalFile }?.delete() } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "This image could not be imported. Choose an image under 20 MB." }
        finally { busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importImage { context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded() } }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CommunityAction(localizedUiText("Back"), onBack, backEntry)
        Text(localizedUiText("Appearance & discovery"), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(localizedUiText("Show remaining episodes"), Modifier.weight(1f))
            Switch(state.showRemainingEpisodes, settings::setShowRemainingEpisodes)
        }
        Text(localizedUiText("Number of aired episodes left to watch on Up Next and started shows."))
        Text(localizedUiText("Rail spacing · ${state.railSpacingDp?.let { "$it dp" } ?: "Default"}"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CommunityAction(localizedUiText("Less space"), { settings.setRailSpacingDp(((state.railSpacingDp ?: 16) - 4).coerceAtLeast(0)) })
            CommunityAction(localizedUiText("More space"), { settings.setRailSpacingDp(((state.railSpacingDp ?: 16) + 4).coerceAtMost(64)) })
        }
        Slider((state.railSpacingDp ?: 16).toFloat(), { settings.setRailSpacingDp((it / 4).toInt() * 4) }, valueRange = 0f..64f, steps = 15)
        CommunityAction(localizedUiText("Use default spacing"), settings::resetRailSpacing)
        Text(localizedUiText("Adjusts content rows on Home, Movies and TV Shows. Changes save automatically."))
        Text(localizedUiText("App background"), style = MaterialTheme.typography.titleMedium)
        Text(localizedUiText(if (DeviceFormFactor.isTv(context)) "Choose movie/show artwork, a plain dark background, a colour glow, or your own image." else "Choose a background for your profile."))
        presets.forEach { (key, label) ->
            if (key != "image" || appearance.image != null) CommunityAction(
                localizedUiText((if (appearance.preset == key) "✓ " else "") + (if (key == "standard" && DeviceFormFactor.isTv(context)) "Movie / show artwork" else label)),
                { prefs.edit().putString("${appearance.owner}.preset", key).apply() }, enabled = !busy)
        }
        if (!DeviceFormFactor.isTv(context)) CommunityAction(localizedUiText("Choose a photo"), { picker.launch("image/*") }, enabled = !busy)
        OutlinedTextField(address, { address = it }, singleLine = true, label = { Text(localizedUiText("Direct image address (HTTPS)")) }, modifier = Modifier.fillMaxWidth())
        CommunityAction(localizedUiText("Use image from address"), {
            scope.launch { importImage {
                val url = URL(address.trim()); require(url.protocol == "https")
                val connection = url.openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                require(connection.contentLengthLong <= 20 * 1024 * 1024)
                connection.getInputStream().use { it.readBytesBounded() }
            } }
        }, enabled = !busy && address.startsWith("https://"))
        if (appearance.image != null) CommunityAction(localizedUiText("Remove image"), {
            val old = appearance.image
            prefs.edit().remove("${appearance.owner}.image").putString("${appearance.owner}.preset", "standard").apply()
            scope.launch(Dispatchers.IO) { File(old).takeIf { it.parentFile?.canonicalFile == File(context.noBackupFilesDir, "backgrounds").canonicalFile }?.delete() }
        }, enabled = !busy)
        if (busy) LinearProgressIndicator()
        error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
    }
}

private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val size = read(buffer); if (size < 0) break; require(out.size() + size <= 20 * 1024 * 1024); out.write(buffer, 0, size) }
    return out.toByteArray()
}

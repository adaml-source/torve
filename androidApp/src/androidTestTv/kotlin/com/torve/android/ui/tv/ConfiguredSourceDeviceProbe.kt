package com.torve.android.ui.tv

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.torve.android.TvMainActivity
import com.torve.domain.model.canResolveStreams
import com.torve.domain.repository.AddonRepository
import com.torve.domain.diagnostics.DiagnosticsRedactor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicit, read-only device diagnostic. Never exports configured URLs or credentials. */
class ConfiguredSourceDeviceProbe {
    @Test fun compareConfiguredProviderRequestFormats(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("torveProviderProbe") == "true")
        val activity = ActivityScenario.launch(TvMainActivity::class.java)
        val graph = GlobalContext.get()
        val rows = mutableListOf<JsonObject>()
        val report = File(instrumentation.targetContext.getExternalFilesDir(null), "source-provider-observation.json")
        fun save() { report.writeText(JsonObject(mapOf("results" to JsonArray(rows))).toString()) }
        val addons = withTimeout(30_000) {
            var configured = graph.get<AddonRepository>().getInstalledAddons().filter { it.canResolveStreams() }
            while (configured.isEmpty()) {
                delay(300)
                configured = graph.get<AddonRepository>().getInstalledAddons().filter { it.canResolveStreams() }
            }
            configured.take(6)
        }
        val settings = graph.get<com.torve.presentation.settings.SettingsViewModel>()
        settings.awaitPlaybackSettings()
        val accounts = settings.getDebridAccounts()
        println("SOURCE_PROBE connectedDebrid=${accounts.keys.joinToString()} cachedOnly=${settings.buildStreamPreferences().cachedOnly}")
        val hashes = mutableSetOf<String>()
        val hashesByTitle = mutableMapOf<String, MutableSet<String>>()
        println("SOURCE_PROBE configuredStreamProviders=${addons.size}")
        val http = graph.get<HttpClient>().config {
            install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 8_000; socketTimeoutMillis = 20_000 }
        }
        val permits = Semaphore(2)
        try { withTimeout(150_000) { coroutineScope {
            addons.flatMap { addon -> listOf("tt4034228", "tt3553976").flatMap { id -> listOf("incremental").map { format ->
                async { permits.withPermit {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val observation = buildJsonObject {
                        put("provider", DiagnosticsRedactor.redact(addon.manifest.name).take(60))
                        put("titleId", id); put("requestFormat", format)
                        try {
                            val response = http.get(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/') + "/stream/movie/$id.json") {
                                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                                header(HttpHeaders.Accept, if (format == "previous") "application/json,text/plain;q=0.9,*/*;q=0.8" else "application/x-ndjson,application/json;q=0.9")
                            }
                            put("status", response.status.value)
                            put("contentType", response.headers[HttpHeaders.ContentType].orEmpty())
                            val body = response.bodyAsText()
                            put("bytes", body.length)
                            val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                            val streams = parsed?.get("streams") as? JsonArray ?: JsonArray(emptyList())
                            streams.mapNotNull { (it as? JsonObject)?.get("infoHash")?.jsonPrimitive?.contentOrNull }.forEach {
                                hashes += it; hashesByTitle.getOrPut(id) { mutableSetOf() } += it
                            }
                            put("streams", streams.size)
                            put("infoHashStreams", streams.count { (it as? JsonObject)?.get("infoHash") != null })
                            put("staticProviderVideos", streams.count { row ->
                                val value = (row as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                                value?.let { runCatching { Url(it).encodedPath.startsWith("/videos/") }.getOrDefault(false) } == true
                            })
                            put("providerMessages", JsonArray(streams.mapNotNull {
                                val title = (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
                                title?.takeIf { value -> Regex("try again|error|unavailable|invalid|denied|limit|expired", RegexOption.IGNORE_CASE).containsMatchIn(value) }
                                    ?.let { value -> JsonPrimitive(DiagnosticsRedactor.redact(value).take(160)) }
                            }.take(5)))
                            put("videoHosts", JsonArray(streams.mapNotNull {
                                val url = (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                                url?.let { value -> runCatching { JsonPrimitive(Url(value).host) }.getOrNull() }
                            }.distinct().take(8)))
                            put("jsonObject", parsed != null)
                            put("nonPlayableSignals", JsonArray(streams.mapNotNull { row ->
                                val entry = row as? JsonObject ?: return@mapNotNull null
                                if (listOf("url", "infoHash", "info_hash", "magnet").any { it in entry }) return@mapNotNull null
                                val display = listOf("name", "title", "description").mapNotNull { entry[it]?.jsonPrimitive?.contentOrNull }.joinToString(" ").lowercase()
                                buildJsonObject {
                                    put("fields", JsonArray(entry.keys.map(::JsonPrimitive)))
                                    put("signals", JsonArray(listOf("torbox", "real-debrid", "alldebrid", "premiumize", "api key", "unauthoriz", "no streams", "not found", "not configured", "timeout", "timed out", "limit", "unavailable", "failed", "error", "disabled", "invalid").filter { it in display }.map(::JsonPrimitive)))
                                    put("externalHost", entry["externalUrl"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Url(it).host }.getOrNull() }.orEmpty())
                                }
                            }.take(6)))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { put("errorType", error.javaClass.simpleName) }
                        put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    }
                    rows += observation; save()
                    println("SOURCE_PROBE $observation")
                } }
            } } }.awaitAll()
        } }
            for ((provider, key) in accounts) {
                val cached = withTimeoutOrNull(8_000) { graph.get<com.torve.data.debrid.DebridClient>().checkCache(provider, key, hashes.take(40)) }
                val row = buildJsonObject {
                    put("debridProvider", provider.name); put("hashesChecked", hashes.take(40).size)
                    put("cached", cached?.values?.count { it } ?: 0); put("responded", cached != null)
                }
                rows += row; save(); println("SOURCE_PROBE $row")
            }
            val late = settings.getDebridAccounts()
            println("SOURCE_PROBE lateConnectedDebrid=${late.keys.joinToString()} hasOwner=${settings.state.value.personalDataUserId != null}")
            val store = graph.get<com.torve.domain.integrations.IntegrationSecretStore>()
            val presence = listOf(
                com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
                com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID,
                com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
                com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
            ).associate { it.name to !store.get(it).isNullOrBlank() }
            println("SOURCE_PROBE storedCredentialPresence=$presence")
            val activation = graph.get<com.torve.domain.repository.PreferencesRepository>().let {
                it.getString("panda_debrid_activation_v1")
            }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            println("SOURCE_PROBE activation=$activation configured=${settings.state.value.configuredDebridProviders.keys.joinToString()} optionalLoaded=${settings.state.value.optionalAppFeaturesLoaded}")
            val torboxKey = store.get(com.torve.domain.integrations.IntegrationSecretKey.DEBRID_API_KEY_TORBOX)
            if (!torboxKey.isNullOrBlank()) {
                for ((title, titleHashes) in hashesByTitle) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val cached = withTimeoutOrNull(8_000) {
                        com.torve.data.debrid.DebridClient(http, Json { ignoreUnknownKeys = true }).checkCache(
                            com.torve.domain.model.DebridServiceType.TORBOX, torboxKey, titleHashes.toList())
                    }
                    val row = buildJsonObject {
                        put("providerOnlyCache", true); put("titleId", title); put("hashesChecked", titleHashes.size)
                        put("cached", cached?.values?.count { it } ?: 0); put("responded", cached != null)
                        put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    }
                    rows += row; save(); println("SOURCE_PROBE $row")
                }
                val verified = withTimeoutOrNull(10_000) {
                    graph.get<com.torve.data.debrid.DebridClient>().verifyApiKey(com.torve.domain.model.DebridServiceType.TORBOX, torboxKey)
                }
                val row = buildJsonObject {
                    put("credentialVerified", verified?.success == true)
                    put("premiumAccount", verified?.user?.premium)
                    put("accountExpiresAt", verified?.user?.expiresAt)
                    put("accountResult", if (verified == null) "timeout" else "responded")
                    val configured = addons.firstNotNullOfOrNull { addon ->
                        val path = runCatching { Url(addon.manifestUrl).encodedPath.decodeURLPart() }.getOrNull().orEmpty()
                        Regex("(?:^|[|/])(?:tb|torbox)=([^|/]+)").find(path)?.groupValues?.get(1)
                    }
                    put("configuredTorBoxKeyPresent", configured != null)
                    put("configuredKeyMatchesStored", configured?.let { it == torboxKey })
                }
                rows += row; save(); println("SOURCE_PROBE $row")
            }
            if (!torboxKey.isNullOrBlank()) for (format in listOf("default", "object")) {
                val response = http.get("https://api.torbox.app/v1/api/torrents/checkcached") {
                    bearerAuth(torboxKey); parameter("hash", hashes.take(40).joinToString(",")); parameter("list_files", false)
                    if (format == "object") parameter("format", "object")
                }
                val payload = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                val data = payload?.get("data")
                val row = buildJsonObject {
                    put("directCacheFormat", format); put("status", response.status.value)
                    put("success", payload?.get("success")?.jsonPrimitive?.booleanOrNull)
                    put("errorCode", payload?.get("error")?.jsonPrimitive?.contentOrNull)
                    put("dataKind", data?.javaClass?.simpleName.orEmpty())
                    put("entries", when (data) { is JsonObject -> data.size; is JsonArray -> data.size; else -> 0 })
                    put("entryKind", when (data) { is JsonObject -> data.values.firstOrNull()?.javaClass?.simpleName; is JsonArray -> data.firstOrNull()?.javaClass?.simpleName; else -> null }.orEmpty())
                }
                rows += row; save(); println("SOURCE_PROBE $row")
            }
        } finally { http.close(); save(); activity.close() }
        Unit
    }
}

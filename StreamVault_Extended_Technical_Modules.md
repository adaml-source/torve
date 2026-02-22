# StreamVault — Extended Technical Modules
## IPTV Engine, Recommendation System, Project Configuration & Platform Details

---

# TABLE OF CONTENTS

1. M3U / IPTV Engine — Full Implementation
2. Recommendation Engine — On-Device Intelligence
3. Xcode Project Configuration (iOS/tvOS/macOS)
4. Android Project Configuration (Phone/TV/Fire TV)
5. KMP Gradle Build Configuration
6. Networking & Caching Layer
7. Cloud Sync Architecture
8. Trakt Scrobbler — Full Implementation
9. Setup Wizard — UX Flow & Implementation
10. Error Handling & Resilience Patterns
11. Security — API Key Storage & Token Management
12. Accessibility & Localization
13. Performance Budgets & Optimization Targets
14. Testing Strategy

---

# 1. M3U / IPTV ENGINE — FULL IMPLEMENTATION

## 1.1 M3U Parser

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/iptv/M3uParser.kt

class M3uParser {

    /**
     * Parse M3U playlist into structured channels.
     *
     * Format:
     * #EXTM3U url-tvg="http://epg.url/guide.xml" refresh="3600"
     * #EXTINF:-1 tvg-id="CNN.us" tvg-name="CNN" tvg-logo="http://logo.png"
     *   group-title="News" tvg-language="English" tvg-country="US",CNN HD
     * http://stream.url/live/cnn/index.m3u8
     */
    fun parse(content: String): M3uPlaylist {
        val lines = content.lines().map { it.trim() }
        val channels = mutableListOf<IptvChannel>()
        var playlistEpgUrl: String? = null
        var playlistRefresh: Int? = null

        var i = 0

        // Parse header
        if (lines.firstOrNull()?.startsWith("#EXTM3U") == true) {
            val header = lines[0]
            playlistEpgUrl = extractAttr(header, "url-tvg")
                ?: extractAttr(header, "x-tvg-url")
            playlistRefresh = extractAttr(header, "refresh")?.toIntOrNull()
            i = 1
        }

        // Parse channels
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
                val builder = parseExtInf(line)

                // Collect extra directives before URL
                i++
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith("#"))) {
                    when {
                        lines[i].startsWith("#EXTVLCOPT:") ->
                            builder.vlcOptions.add(lines[i].removePrefix("#EXTVLCOPT:"))
                        lines[i].startsWith("#KODIPROP:") -> {
                            val parts = lines[i].removePrefix("#KODIPROP:").split("=", limit = 2)
                            if (parts.size == 2) builder.kodiProps[parts[0]] = parts[1]
                        }
                    }
                    i++
                }

                // Next non-empty non-comment line = stream URL
                if (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#")) {
                    builder.url = lines[i]
                    channels.add(builder.build())
                }
            }
            i++
        }

        return M3uPlaylist(
            epgUrl = playlistEpgUrl,
            refreshSeconds = playlistRefresh,
            channels = channels,
        )
    }

    private fun parseExtInf(line: String): IptvChannelBuilder {
        val builder = IptvChannelBuilder()
        val afterPrefix = line.removePrefix("#EXTINF:")

        // Duration is before first space/comma
        builder.duration = afterPrefix.takeWhile { it != ' ' && it != ',' }.toIntOrNull() ?: -1

        // Title is after the last comma
        val commaIdx = afterPrefix.lastIndexOf(',')
        if (commaIdx > 0) {
            builder.name = afterPrefix.substring(commaIdx + 1).trim()
            val attrSection = afterPrefix.substring(0, commaIdx)

            builder.tvgId = extractAttr(attrSection, "tvg-id")
            builder.tvgName = extractAttr(attrSection, "tvg-name")
            builder.tvgLogo = extractAttr(attrSection, "tvg-logo")
            builder.groupTitle = extractAttr(attrSection, "group-title")
            builder.tvgLanguage = extractAttr(attrSection, "tvg-language")
            builder.tvgCountry = extractAttr(attrSection, "tvg-country")
            builder.tvgShift = extractAttr(attrSection, "tvg-shift")?.toIntOrNull()
            builder.channelNumber = extractAttr(attrSection, "channel-number")?.toIntOrNull()
            builder.catchupType = extractAttr(attrSection, "catchup")
            builder.catchupDays = extractAttr(attrSection, "catchup-days")?.toIntOrNull()
            builder.catchupSource = extractAttr(attrSection, "catchup-source")
            builder.userAgent = extractAttr(attrSection, "user-agent")
        }
        return builder
    }

    private fun extractAttr(text: String, key: String): String? {
        val pattern = """$key="([^"]*?)"""".toRegex()
        return pattern.find(text)?.groupValues?.get(1)
    }
}

data class M3uPlaylist(
    val epgUrl: String?,
    val refreshSeconds: Int?,
    val channels: List<IptvChannel>,
)

@Serializable
data class IptvChannel(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val tvgLanguage: String?,
    val tvgCountry: String?,
    val tvgShift: Int?,
    val channelNumber: Int?,
    val duration: Int = -1,
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val vlcOptions: List<String> = emptyList(),
    val kodiProps: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
)

class IptvChannelBuilder {
    var name = ""; var url = ""
    var tvgId: String? = null; var tvgName: String? = null
    var tvgLogo: String? = null; var groupTitle: String? = null
    var tvgLanguage: String? = null; var tvgCountry: String? = null
    var tvgShift: Int? = null; var channelNumber: Int? = null
    var duration = -1
    var catchupType: String? = null; var catchupDays: Int? = null
    var catchupSource: String? = null; var userAgent: String? = null
    val vlcOptions = mutableListOf<String>()
    val kodiProps = mutableMapOf<String, String>()

    fun build() = IptvChannel(
        name = name, url = url, tvgId = tvgId, tvgName = tvgName,
        tvgLogo = tvgLogo, groupTitle = groupTitle, tvgLanguage = tvgLanguage,
        tvgCountry = tvgCountry, tvgShift = tvgShift, channelNumber = channelNumber,
        duration = duration, catchupType = catchupType, catchupDays = catchupDays,
        catchupSource = catchupSource, userAgent = userAgent,
        vlcOptions = vlcOptions.toList(), kodiProps = kodiProps.toMap(),
    )
}
```

## 1.2 XMLTV EPG Parser

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/iptv/EpgParser.kt

class EpgParser {

    /**
     * Parse XMLTV EPG. Handles plain XML and gzipped.
     *
     * <tv>
     *   <channel id="CNN.us">
     *     <display-name>CNN</display-name>
     *     <icon src="http://logo.png"/>
     *   </channel>
     *   <programme start="20250221180000 +0000" stop="20250221190000 +0000" channel="CNN.us">
     *     <title lang="en">Anderson Cooper 360</title>
     *     <desc lang="en">In-depth reporting...</desc>
     *     <category>News</category>
     *     <icon src="http://poster.jpg"/>
     *   </programme>
     * </tv>
     */
    fun parse(xmlContent: String): EpgData {
        val channels = mutableMapOf<String, EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()

        // Platform XML parser (expect/actual for KMP)
        val parser = PlatformXmlParser.create(xmlContent)

        parser.forEachElement { tag, attributes ->
            when (tag) {
                "channel" -> {
                    val id = attributes["id"] ?: return@forEachElement
                    var displayName: String? = null
                    var icon: String? = null
                    forEachChild { childTag, childAttrs ->
                        when (childTag) {
                            "display-name" -> displayName = textContent()
                            "icon" -> icon = childAttrs["src"]
                        }
                    }
                    channels[id] = EpgChannel(id, displayName ?: id, icon)
                }
                "programme" -> {
                    val startStr = attributes["start"] ?: return@forEachElement
                    val stopStr = attributes["stop"] ?: return@forEachElement
                    val channelId = attributes["channel"] ?: return@forEachElement
                    var title: String? = null
                    var desc: String? = null
                    var category: String? = null
                    var icon: String? = null
                    var subTitle: String? = null

                    forEachChild { childTag, childAttrs ->
                        when (childTag) {
                            "title" -> title = textContent()
                            "sub-title" -> subTitle = textContent()
                            "desc" -> desc = textContent()
                            "category" -> category = textContent()
                            "icon" -> icon = childAttrs["src"]
                        }
                    }
                    programmes.add(EpgProgramme(
                        channelId = channelId,
                        startTime = parseXmltvTimestamp(startStr),
                        endTime = parseXmltvTimestamp(stopStr),
                        title = title ?: "Unknown",
                        subTitle = subTitle,
                        description = desc,
                        category = category,
                        iconUrl = icon,
                    ))
                }
            }
        }

        return EpgData(channels, programmes)
    }

    // "20250221180000 +0000" → epoch millis
    private fun parseXmltvTimestamp(ts: String): Long {
        val d = ts.take(14)
        val ldt = LocalDateTime(
            d.substring(0, 4).toInt(), d.substring(4, 6).toInt(),
            d.substring(6, 8).toInt(), d.substring(8, 10).toInt(),
            d.substring(10, 12).toInt(), d.substring(12, 14).toInt()
        )
        val tzPart = ts.substring(14).trim()
        val offsetMin = if (tzPart.isNotEmpty()) {
            val sign = if (tzPart[0] == '-') -1 else 1
            sign * (tzPart.substring(1, 3).toInt() * 60 + tzPart.substring(3, 5).toInt())
        } else 0
        return ldt.toInstant(UtcOffset(minutes = offsetMin)).toEpochMilliseconds()
    }
}

data class EpgData(
    val channels: Map<String, EpgChannel>,
    val programmes: List<EpgProgramme>,
)

data class EpgChannel(val id: String, val displayName: String, val iconUrl: String?)

data class EpgProgramme(
    val channelId: String,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val category: String?,
    val iconUrl: String?,
)
```

## 1.3 IPTV Repository — Channel/EPG Matching

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/iptv/IptvRepositoryImpl.kt

class IptvRepositoryImpl(
    private val m3uParser: M3uParser,
    private val epgParser: EpgParser,
    private val httpClient: HttpClient,
    private val database: StreamVaultDatabase,
) : IptvRepository {

    private var cachedPlaylist: M3uPlaylist? = null
    private var cachedEpg: EpgData? = null

    override suspend fun addPlaylist(name: String, url: String, epgUrl: String?) {
        database.iptvPlaylistQueries.insert(
            id = generateId(), name = name, url = url,
            epg_url = epgUrl,
            last_updated = Clock.System.now().toEpochMilliseconds()
        )
        refreshPlaylist(url, epgUrl)
    }

    override suspend fun refreshPlaylist(playlistUrl: String, epgUrl: String?) {
        val m3uContent: String = httpClient.get(playlistUrl).body()
        cachedPlaylist = m3uParser.parse(m3uContent)

        val resolvedEpgUrl = epgUrl ?: cachedPlaylist?.epgUrl
        if (resolvedEpgUrl != null) {
            val raw: ByteArray = httpClient.get(resolvedEpgUrl).body()
            val xml = if (resolvedEpgUrl.endsWith(".gz")) decompressGzip(raw) else raw.decodeToString()
            cachedEpg = epgParser.parse(xml)
        }
    }

    override suspend fun getChannelsByGroup(): Map<String, List<IptvChannel>> {
        return (cachedPlaylist?.channels ?: emptyList()).groupBy { it.groupTitle ?: "Ungrouped" }
    }

    override suspend fun getEnrichedChannels(): List<EnrichedChannel> {
        val channels = cachedPlaylist?.channels ?: return emptyList()
        val epg = cachedEpg ?: return channels.map { EnrichedChannel(it, null, null) }
        val now = Clock.System.now().toEpochMilliseconds()

        return channels.map { ch ->
            val epgId = matchEpgChannel(ch, epg)
            val current = epgId?.let { id ->
                epg.programmes.find { it.channelId == id && it.startTime <= now && it.endTime > now }
            }
            val next = epgId?.let { id ->
                epg.programmes.filter { it.channelId == id && it.startTime > now }
                    .minByOrNull { it.startTime }
            }
            EnrichedChannel(ch, current, next)
        }
    }

    /**
     * Match M3U channel → EPG channel. Priority:
     * 1. tvg-id exact match
     * 2. tvg-name matches display-name (case-insensitive)
     * 3. Channel name fuzzy matches display-name
     */
    private fun matchEpgChannel(ch: IptvChannel, epg: EpgData): String? {
        ch.tvgId?.let { if (it in epg.channels) return it }
        ch.tvgName?.let { name ->
            epg.channels.entries.find { it.value.displayName.equals(name, true) }
                ?.let { return it.key }
        }
        return epg.channels.entries.find {
            it.value.displayName.equals(ch.name, true) ||
            it.value.displayName.contains(ch.name, true)
        }?.key
    }
}

data class EnrichedChannel(
    val channel: IptvChannel,
    val currentProgramme: EpgProgramme?,
    val nextProgramme: EpgProgramme?,
)
```

## 1.4 IPTV Live TV Grid UI (iOS)

```swift
// iosApp/StreamVault/UI/IPTV/LiveTvView.swift

struct LiveTvView: View {
    @StateObject private var viewModel = IptvViewModelWrapper()
    @State private var selectedGroup: String? = nil

    var body: some View {
        NavigationSplitView {
            List(viewModel.groups, id: \.self, selection: $selectedGroup) { group in
                Label(group, systemImage: groupIcon(group))
            }
            .navigationTitle("Live TV")
        } detail: {
            if let group = selectedGroup {
                ChannelGrid(
                    channels: viewModel.channelsForGroup(group),
                    onPlay: { viewModel.play($0) }
                )
            } else {
                Text("Select a category").foregroundColor(.secondary)
            }
        }
    }

    private func groupIcon(_ g: String) -> String {
        let l = g.lowercased()
        if l.contains("news") { return "newspaper" }
        if l.contains("sport") { return "sportscourt" }
        if l.contains("movie") { return "film" }
        if l.contains("music") { return "music.note" }
        if l.contains("kid") { return "figure.play" }
        return "tv"
    }
}

struct ChannelCard: View {
    let enriched: EnrichedChannel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                AsyncImage(url: URL(string: enriched.channel.tvgLogo ?? "")) { img in
                    img.resizable().aspectRatio(contentMode: .fit)
                } placeholder: {
                    Image(systemName: "tv").foregroundColor(.secondary)
                }
                .frame(width: 32, height: 32)
                .clipShape(RoundedRectangle(cornerRadius: 4))

                Text(enriched.channel.name)
                    .font(.subheadline).fontWeight(.semibold).lineLimit(1)
            }

            if let current = enriched.currentProgramme {
                Text(current.title).font(.caption).lineLimit(1)
                let progress = programmeProgress(current)
                ProgressView(value: progress).tint(.accentColor)
            }

            if let next = enriched.nextProgramme {
                Text("Next: \(next.title)")
                    .font(.caption2).foregroundColor(.secondary).lineLimit(1)
            }
        }
        .padding(10)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
    }

    private func programmeProgress(_ p: EpgProgramme) -> Double {
        let now = Date().timeIntervalSince1970 * 1000
        let total = Double(p.endTime - p.startTime)
        let elapsed = now - Double(p.startTime)
        return min(max(elapsed / total, 0), 1)
    }
}
```

## 1.5 Catchup / Timeshift Support

```kotlin
// Catchup allows watching past programmes (if provider supports it)
// Common catchup URL patterns:

class CatchupResolver {
    fun resolveCatchupUrl(
        channel: IptvChannel,
        programme: EpgProgramme
    ): String? {
        val type = channel.catchupType ?: return null
        val source = channel.catchupSource ?: return null
        val baseUrl = channel.url

        // Standard IPTV catchup variables
        val startUtc = programme.startTime / 1000
        val endUtc = programme.endTime / 1000
        val duration = endUtc - startUtc
        val startFormatted = formatCatchupDate(programme.startTime)

        return when (type) {
            "default" -> {
                // Replace variables in catchup-source
                source
                    .replace("{utc}", startUtc.toString())
                    .replace("{start}", startUtc.toString())
                    .replace("{end}", endUtc.toString())
                    .replace("{duration}", duration.toString())
                    .replace("{Y}", startFormatted.year)
                    .replace("{m}", startFormatted.month)
                    .replace("{d}", startFormatted.day)
                    .replace("{H}", startFormatted.hour)
                    .replace("{M}", startFormatted.minute)
                    .replace("{S}", startFormatted.second)
            }
            "append" -> {
                // Append catchup-source to base URL
                baseUrl + source
                    .replace("{utc}", startUtc.toString())
                    .replace("{duration}", duration.toString())
            }
            "shift" -> {
                // Use timeshift parameter
                val shiftSec = (Clock.System.now().epochSeconds - startUtc)
                "$baseUrl?utc=$startUtc&lutc=${Clock.System.now().epochSeconds}"
            }
            "flussonic", "fs" -> {
                // Flussonic server format
                val path = baseUrl.substringBeforeLast("/")
                "$path/timeshift_abs-$startUtc.ts"
            }
            else -> null
        }
    }
}
```

---

# 2. RECOMMENDATION ENGINE — ON-DEVICE INTELLIGENCE

Runs entirely on-device. No backend. Builds a user taste profile from watch history, generates candidates from TMDB + addon catalogs, scores and ranks them.

## 2.1 Architecture

```
Watch History (Trakt + local) → Feature Extraction → Taste Profile
  ↓
Candidate Generation (TMDB similar/discover + addon catalogs)
  ↓
Scoring & Ranking → Filter already watched → Top 20 recommendations
```

## 2.2 Implementation

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/domain/usecase/GetRecommendationsUseCase.kt

class GetRecommendationsUseCase(
    private val watchHistory: WatchHistoryRepository,
    private val traktRepo: TraktRepository,
    private val metadataRepo: MetadataRepository,
) {
    suspend fun execute(limit: Int = 20): List<ScoredMediaItem> {
        // 1. Build taste profile
        val watched = traktRepo.getWatchedHistory(limit = 100)
        val profile = buildTasteProfile(watched)

        // 2. Generate candidates from multiple sources
        val candidates = mutableSetOf<MediaItem>()

        // Recently watched → TMDB "similar" and "recommendations"
        watched.take(5).forEach { item ->
            val tmdbId = item.tmdbId?.toString() ?: return@forEach
            candidates.addAll(metadataRepo.getSimilar(item.type.name, tmdbId))
            candidates.addAll(metadataRepo.getRecommendations(item.type.name, tmdbId))
        }

        // Top genres → TMDB discover
        profile.genreScores.entries
            .sortedByDescending { it.value }
            .take(3)
            .forEach { (genreId, _) ->
                candidates.addAll(
                    metadataRepo.discoverByGenre(genreId, minRating = profile.avgRating - 1.0)
                )
            }

        // 3. Filter watched, score, rank
        val watchedIds = watched.map { it.id }.toSet()
        return candidates
            .filter { it.id !in watchedIds }
            .map { ScoredMediaItem(it, calculateScore(it, profile), generateReason(it, profile)) }
            .distinctBy { it.item.id }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun buildTasteProfile(items: List<MediaItem>): TasteProfile {
        val genreCounts = mutableMapOf<Int, Int>()
        val actorCounts = mutableMapOf<String, Int>()
        val ratings = mutableListOf<Double>()
        val runtimes = mutableListOf<Int>()

        items.forEach { item ->
            item.genreIds?.forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
            item.cast.take(3).forEach { a -> actorCounts[a.name] = (actorCounts[a.name] ?: 0) + 1 }
            item.rating?.let { ratings.add(it) }
            item.runtime?.let { runtimes.add(it) }
        }

        val total = items.size.toDouble().coerceAtLeast(1.0)
        return TasteProfile(
            genreScores = genreCounts.mapValues { it.value / total },
            actorScores = actorCounts.mapValues { it.value / total },
            avgRating = if (ratings.isNotEmpty()) ratings.average() else 6.0,
            avgRuntime = if (runtimes.isNotEmpty()) runtimes.average().toInt() else 120,
        )
    }

    private fun calculateScore(item: MediaItem, profile: TasteProfile): Double {
        var score = 0.0

        // Genre match (40%)
        val genreOverlap = item.genreIds?.sumOf { profile.genreScores[it] ?: 0.0 } ?: 0.0
        score += genreOverlap * 0.4

        // Rating quality (20%)
        score += ((item.rating ?: 5.0) / 10.0) * 0.2

        // Actor match (15%)
        val actorOverlap = item.cast.take(5).sumOf { profile.actorScores[it.name] ?: 0.0 }
        score += actorOverlap.coerceAtMost(1.0) * 0.15

        // Runtime preference (10%)
        val rtDiff = kotlin.math.abs((item.runtime ?: 120) - profile.avgRuntime)
        score += (1.0 - (rtDiff / 120.0).coerceIn(0.0, 1.0)) * 0.1

        // Recency bonus (10%)
        val yearBonus = item.year?.let { ((it - 2000) / 25.0).coerceIn(0.0, 1.0) } ?: 0.5
        score += yearBonus * 0.1

        // Popularity (5%)
        score += (if ((item.rating ?: 0.0) > 7.5) 1.0 else if ((item.rating ?: 0.0) > 6.0) 0.5 else 0.2) * 0.05

        return score
    }

    private fun generateReason(item: MediaItem, profile: TasteProfile): String {
        val matchedActor = item.cast.firstOrNull { it.name in profile.actorScores }
        val topGenre = item.genreIds?.maxByOrNull { profile.genreScores[it] ?: 0.0 }?.let { genreIdToName(it) }
        return when {
            matchedActor != null -> "Because you like ${matchedActor.name}"
            topGenre != null -> "Popular in $topGenre"
            (item.rating ?: 0.0) > 8.0 -> "Highly rated"
            else -> "Recommended for you"
        }
    }
}

data class TasteProfile(
    val genreScores: Map<Int, Double>,
    val actorScores: Map<String, Double>,
    val avgRating: Double,
    val avgRuntime: Int,
)

data class ScoredMediaItem(val item: MediaItem, val score: Double, val reason: String)
```

---

# 3. XCODE PROJECT CONFIGURATION

## 3.1 Multi-Target Setup

```
StreamVault.xcodeproj/
├── Targets:
│   ├── StreamVault (iOS)        — Bundle: com.streamvault.ios — iOS 16+
│   ├── StreamVault-tvOS (tvOS)  — Bundle: com.streamvault.tvos — tvOS 16+
│   ├── StreamVault-macOS (macOS)— Bundle: com.streamvault.macos — macOS 13+
│   └── StreamVaultTopShelf      — tvOS Extension (Continue Watching)
│
├── Shared Swift Code (all targets):
│   ├── UI/Theme/       — Colors, fonts, shared design tokens
│   ├── UI/Components/  — MediaCard, CatalogShelf (adaptive)
│   ├── Player/         — MpvPlayerWrapper, PiP
│   └── DI/             — Swift-side dependency injection
│
├── Platform-Specific:
│   ├── iOS: Tab navigation, gestures, compact layouts
│   ├── tvOS: Focus engine, Siri Remote, Top Shelf
│   └── macOS: Menu bar, keyboard shortcuts, window management
```

## 3.2 Info.plist Key Entries

```xml
<!-- Background audio for PiP / AirPlay -->
<key>UIBackgroundModes</key>
<array><string>audio</string><string>processing</string></array>

<!-- Allow HTTP streams from any source -->
<key>NSAppTransportSecurity</key>
<dict><key>NSAllowsArbitraryLoads</key><true/></dict>

<!-- Deep link: streamvault:// -->
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array><string>streamvault</string></array>
  </dict>
</array>

<!-- PiP / multi-scene -->
<key>UISupportsMultipleScenes</key><true/>

<!-- Local network for DLNA/UPnP discovery (future) -->
<key>NSLocalNetworkUsageDescription</key>
<string>StreamVault uses local network to discover media servers.</string>
```

## 3.3 MPVKit Integration

```
Option A: Swift Package Manager (if available)
  .package(url: "https://github.com/nicklama/MPVKit.git", from: "0.38.0")

Option B: Manual xcframework
  1. Download MPVKit.xcframework (arm64 iOS, arm64 tvOS, arm64+x86_64 macOS)
  2. Drag into Xcode → Frameworks, Libraries, and Embedded Content → "Embed & Sign"
  3. Bridging Header:

// StreamVault-Bridging-Header.h
#import <mpv/client.h>
#import <mpv/render.h>
#import <mpv/render_gl.h>
```

## 3.4 Xcode Build Phases for KMP Framework

```bash
# Build Phase → Run Script → "Build KMP Framework"
cd "$SRCROOT/../"
./gradlew :shared:linkReleaseFrameworkIosArm64

# Or for debug builds:
if [ "$CONFIGURATION" = "Debug" ]; then
    ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
else
    ./gradlew :shared:linkReleaseFrameworkIosArm64
fi
```

---

# 4. ANDROID PROJECT CONFIGURATION

## 4.1 Build Variants

```kotlin
// androidApp/build.gradle.kts

android {
    namespace = "com.streamvault.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streamvault.android"
        minSdk = 24   // Android 7.0
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    flavorDimensions += "platform"
    productFlavors {
        create("mobile") { dimension = "platform" }
        create("tv") { dimension = "platform"; minSdk = 24 }
    }

    buildFeatures { compose = true }
    ndkVersion = "26.1.10909125"
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    "tvImplementation"(libs.compose.tv.material)
    "tvImplementation"(libs.compose.tv.foundation)
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(libs.coil.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    "mobileImplementation"("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("io.sentry:sentry-android:7.19.1")
}
```

## 4.2 Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <!-- TV declarations -->
    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:name=".StreamVaultApp"
        android:banner="@drawable/tv_banner"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="true">

        <!-- Phone launcher -->
        <activity android:name=".MainActivity" android:exported="true"
            android:supportsPictureInPicture="true"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="streamvault" />
            </intent-filter>
        </activity>

        <!-- TV launcher -->
        <activity android:name=".TvMainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Background playback service -->
        <service android:name=".player.PlaybackService"
            android:foregroundServiceType="mediaPlayback" android:exported="false">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

---

# 5. KMP GRADLE BUILD CONFIGURATION

```kotlin
// shared/build.gradle.kts

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }

    // iOS
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    // tvOS
    listOf(tvosArm64(), tvosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    // macOS
    listOf(macosX64(), macosArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.logging)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.android)
            implementation(libs.sqldelight.android)
            implementation(libs.datastore)
        }
        // Shared Darwin source set
        val darwinMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.darwin)
                implementation(libs.sqldelight.native)
            }
        }
        iosMain.get().dependsOn(darwinMain)
        // tvosMain.get().dependsOn(darwinMain)
        // macosMain.get().dependsOn(darwinMain)

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.streamvault.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("StreamVaultDatabase") {
            packageName.set("com.streamvault.db")
        }
    }
}
```

---

# 6. NETWORKING & CACHING LAYER

## 6.1 Ktor HTTP Client Configuration

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/network/HttpClientFactory.kt

object HttpClientFactory {

    fun create(): HttpClient = HttpClient {
        // JSON serialization
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                coerceInputValues = true
            })
        }

        // Logging (debug builds only)
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.HEADERS
        }

        // Timeouts
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }

        // Retry on transient failures
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            retryOnException(maxRetries = 2, retryOnTimeout = true)
            exponentialDelay()
        }

        // Default headers
        defaultRequest {
            header("User-Agent", "StreamVault/1.0")
            header("Accept", "application/json")
        }
    }
}
```

## 6.2 Multi-Layer Cache Strategy

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/cache/CacheManager.kt

class CacheManager(
    private val database: StreamVaultDatabase,
    private val memoryCache: LruCache<String, CacheEntry>,  // In-memory LRU
) {
    companion object {
        // TTL by data type
        val TTL_TRENDING = 1.hours          // Trending changes frequently
        val TTL_METADATA = 7.days           // Movie details are stable
        val TTL_SEARCH = 15.minutes         // Search results are ephemeral
        val TTL_ADDON_MANIFEST = 24.hours   // Addon manifests don't change often
        val TTL_EPG = 6.hours               // EPG data refreshes periodically
        val TTL_IMAGES = 30.days            // Poster/backdrop images are permanent
    }

    suspend fun <T> getOrFetch(
        key: String,
        ttl: Duration,
        fetch: suspend () -> T,
        serialize: (T) -> String,
        deserialize: (String) -> T,
    ): T {
        // Layer 1: In-memory cache
        memoryCache.get(key)?.let { entry ->
            if (!entry.isExpired(ttl)) {
                @Suppress("UNCHECKED_CAST")
                return entry.data as T
            }
        }

        // Layer 2: SQLite cache
        val cachedJson = database.metadataCacheQueries
            .getCachedMeta(key, Clock.System.now().minus(ttl).toEpochMilliseconds())
            .executeAsOneOrNull()

        if (cachedJson != null) {
            val data = deserialize(cachedJson)
            memoryCache.put(key, CacheEntry(data as Any, Clock.System.now()))
            return data
        }

        // Layer 3: Network fetch
        val freshData = fetch()
        val json = serialize(freshData)

        // Store in both caches
        memoryCache.put(key, CacheEntry(freshData as Any, Clock.System.now()))
        database.metadataCacheQueries.insertCachedMeta(
            key, "generic", json, Clock.System.now().toEpochMilliseconds()
        )

        return freshData
    }

    fun invalidate(keyPrefix: String) {
        memoryCache.evictByPrefix(keyPrefix)
        database.metadataCacheQueries.deleteByPrefix("$keyPrefix%")
    }
}

data class CacheEntry(val data: Any, val cachedAt: Instant) {
    fun isExpired(ttl: Duration): Boolean =
        Clock.System.now() - cachedAt > ttl
}
```

## 6.3 Image Caching Strategy

```
Images are cached using platform-native libraries:
- iOS: Nuke or Kingfisher (via AsyncImage with custom loader)
- Android: Coil 3 (compose-native, KMP-compatible)

Both support:
- Memory cache (50MB default)
- Disk cache (250MB default)
- Progressive JPEG loading
- Placeholder/error images
- Crossfade transitions

Configuration:
- Poster images: Cache 30 days, resize to 300x450 max
- Backdrop images: Cache 30 days, resize to 1280x720 max
- Channel logos: Cache 30 days, resize to 64x64
- Thumbnails: Cache 7 days, resize to 160x90
```

---

# 7. CLOUD SYNC ARCHITECTURE

## 7.1 Sync Strategy

```
Platform-specific sync backends:
- Apple ecosystem: CloudKit (via NSUbiquitousKeyValueStore + CKRecord)
- Android ecosystem: Firebase Realtime Database or Firestore
- Cross-platform: Custom lightweight sync server (future v2)

What syncs:
├── Installed addons (manifest URLs only, not cached data)
├── Debrid service selection (not API keys — those stay in Keychain/Keystore)
├── User preferences (quality, language, subtitle settings)
├── Watch progress (position, duration, timestamp)
├── Watchlist (Trakt handles this, but local backup)
└── Favorite IPTV channels

What does NOT sync:
├── API keys / tokens (platform keychain only)
├── Cached metadata (rebuilt from TMDB)
├── Cached streams (ephemeral)
└── EPG data (too large, rebuilt from source)
```

## 7.2 Sync Data Model

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/sync/SyncPayload.kt

@Serializable
data class SyncPayload(
    val version: Int = 1,
    val lastModified: Long,     // epoch millis
    val deviceId: String,
    val addons: List<SyncAddon>,
    val preferences: Map<String, String>,
    val watchProgress: List<SyncProgress>,
    val favorites: List<String>,  // channel IDs
)

@Serializable
data class SyncAddon(
    val manifestUrl: String,
    val isEnabled: Boolean,
    val priority: Int,
)

@Serializable
data class SyncProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

// Conflict resolution: last-write-wins per field
// If remote.updatedAt > local.updatedAt → use remote
// Exception: watch progress uses max(position) to prevent losing progress
```

## 7.3 iOS iCloud Sync

```swift
// iosApp/StreamVault/Sync/ICloudSyncManager.swift

class ICloudSyncManager {
    private let kvStore = NSUbiquitousKeyValueStore.default

    func pushSync(_ payload: SyncPayload) {
        let encoder = JSONEncoder()
        if let data = try? encoder.encode(payload) {
            kvStore.set(data, forKey: "sync_payload")
            kvStore.synchronize()
        }
    }

    func pullSync() -> SyncPayload? {
        guard let data = kvStore.data(forKey: "sync_payload") else { return nil }
        return try? JSONDecoder().decode(SyncPayload.self, from: data)
    }

    func observeChanges(handler: @escaping (SyncPayload?) -> Void) {
        NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: kvStore, queue: .main
        ) { _ in
            handler(self.pullSync())
        }
    }
}
```

---

# 8. TRAKT SCROBBLER — FULL IMPLEMENTATION

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/trakt/TraktScrobbler.kt

class TraktScrobbler(
    private val traktClient: TraktApiClient,
    private val watchHistory: WatchHistoryRepository,
) {
    private var currentItem: MediaItem? = null
    private var scrobbleState: ScrobbleState = ScrobbleState.IDLE

    enum class ScrobbleState { IDLE, STARTED, PAUSED }

    /**
     * Called by PlayerViewModel on playback events.
     * Trakt scrobble rules:
     * - "start" when playback begins
     * - "pause" when user pauses
     * - "stop" when playback ends or user stops
     * - Auto-scrobble (mark as watched) when progress > 80%
     */

    suspend fun onPlaybackStarted(item: MediaItem, progressPercent: Double) {
        currentItem = item
        if (scrobbleState != ScrobbleState.STARTED) {
            traktClient.scrobble("start", buildScrobbleBody(item, progressPercent))
            scrobbleState = ScrobbleState.STARTED
        }
    }

    suspend fun onPlaybackPaused(progressPercent: Double) {
        val item = currentItem ?: return
        if (scrobbleState == ScrobbleState.STARTED) {
            traktClient.scrobble("pause", buildScrobbleBody(item, progressPercent))
            scrobbleState = ScrobbleState.PAUSED
        }
    }

    suspend fun onPlaybackResumed(progressPercent: Double) {
        val item = currentItem ?: return
        traktClient.scrobble("start", buildScrobbleBody(item, progressPercent))
        scrobbleState = ScrobbleState.STARTED
    }

    suspend fun onPlaybackStopped(progressPercent: Double) {
        val item = currentItem ?: return
        traktClient.scrobble("stop", buildScrobbleBody(item, progressPercent))
        scrobbleState = ScrobbleState.IDLE

        // Auto-mark as watched if > 80% complete
        if (progressPercent > 80.0) {
            traktClient.addToHistory(item)
        }

        currentItem = null
    }

    /**
     * Periodic progress update — call every 30 seconds during playback.
     * Also saves local progress for "continue watching".
     */
    suspend fun onProgressUpdate(positionMs: Long, durationMs: Long) {
        val item = currentItem ?: return
        val percent = if (durationMs > 0) (positionMs.toDouble() / durationMs * 100) else 0.0

        // Save local progress
        watchHistory.upsertProgress(WatchProgress(
            mediaId = item.id,
            mediaType = item.type,
            title = item.title,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            showTitle = item.showTitle,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        ))
    }

    private fun buildScrobbleBody(item: MediaItem, progress: Double): TraktScrobbleBody {
        return when (item.type) {
            MediaType.MOVIE -> TraktScrobbleBody(
                movie = TraktMovie(
                    ids = TraktIds(imdb = item.imdbId, tmdb = item.tmdbId)
                ),
                progress = progress,
            )
            MediaType.EPISODE -> TraktScrobbleBody(
                episode = TraktEpisode(
                    season = item.seasonNumber ?: 1,
                    number = item.episodeNumber ?: 1,
                ),
                show = TraktShow(
                    ids = TraktIds(imdb = item.imdbId, tmdb = item.tmdbId)
                ),
                progress = progress,
            )
            else -> TraktScrobbleBody(progress = progress)
        }
    }
}

// Trakt API models
@Serializable
data class TraktScrobbleBody(
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
    val episode: TraktEpisode? = null,
    val progress: Double,
)

@Serializable
data class TraktIds(val imdb: String? = null, val tmdb: Int? = null, val trakt: Int? = null)

@Serializable
data class TraktMovie(val ids: TraktIds)

@Serializable
data class TraktShow(val ids: TraktIds)

@Serializable
data class TraktEpisode(val season: Int, val number: Int)
```

## 8.1 Trakt OAuth2 Flow

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/data/trakt/TraktApiClient.kt

class TraktApiClient(
    private val httpClient: HttpClient,
    private val settingsRepo: SettingsRepository,
) {
    private val baseUrl = "https://api.trakt.tv"
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
    private val redirectUri = "streamvault://trakt/callback"

    // Step 1: Generate auth URL for OAuth2 browser flow
    fun getAuthUrl(): String {
        return "https://trakt.tv/oauth/authorize" +
            "?response_type=code" +
            "&client_id=$clientId" +
            "&redirect_uri=$redirectUri"
    }

    // Step 2: Exchange authorization code for access token
    suspend fun exchangeCode(code: String): TraktToken {
        val response: TraktToken = httpClient.post("$baseUrl/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(TraktCodeExchange(
                code = code,
                client_id = clientId,
                client_secret = clientSecret,
                redirect_uri = redirectUri,
                grant_type = "authorization_code",
            ))
        }.body()

        settingsRepo.setTraktToken(response)
        return response
    }

    // Step 3: Refresh token when expired
    suspend fun refreshToken(): TraktToken {
        val current = settingsRepo.getTraktToken() ?: throw Exception("No Trakt token")
        val response: TraktToken = httpClient.post("$baseUrl/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(TraktCodeExchange(
                refresh_token = current.refreshToken,
                client_id = clientId,
                client_secret = clientSecret,
                redirect_uri = redirectUri,
                grant_type = "refresh_token",
            ))
        }.body()

        settingsRepo.setTraktToken(response)
        return response
    }

    // Authenticated request helper
    private suspend fun <T> authedRequest(block: suspend HttpClient.() -> T): T {
        val token = settingsRepo.getTraktToken() ?: throw Exception("Not logged in to Trakt")

        // Check if token needs refresh (expires within 24h)
        if (token.isExpiringSoon()) {
            refreshToken()
        }

        return httpClient.block()
    }

    // Scrobble endpoint
    suspend fun scrobble(action: String, body: TraktScrobbleBody) {
        authedRequest {
            post("$baseUrl/scrobble/$action") {
                header("Authorization", "Bearer ${settingsRepo.getTraktToken()!!.accessToken}")
                header("trakt-api-key", clientId)
                header("trakt-api-version", "2")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    // Watchlist
    suspend fun getWatchlist(): List<MediaItem> {
        return authedRequest {
            get("$baseUrl/users/me/watchlist/movies,shows") {
                header("Authorization", "Bearer ${settingsRepo.getTraktToken()!!.accessToken}")
                header("trakt-api-key", clientId)
                header("trakt-api-version", "2")
            }.body<List<TraktWatchlistItem>>()
                .map { it.toMediaItem() }
        }
    }

    // Watch history
    suspend fun getWatchedHistory(limit: Int = 100): List<MediaItem> {
        return authedRequest {
            get("$baseUrl/users/me/history?limit=$limit") {
                header("Authorization", "Bearer ${settingsRepo.getTraktToken()!!.accessToken}")
                header("trakt-api-key", clientId)
                header("trakt-api-version", "2")
            }.body<List<TraktHistoryItem>>()
                .map { it.toMediaItem() }
        }
    }
}

@Serializable
data class TraktToken(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val created_at: Long,
) {
    val accessToken get() = access_token
    val refreshToken get() = refresh_token
    fun isExpiringSoon(): Boolean {
        val expiresAt = created_at + expires_in
        val now = Clock.System.now().epochSeconds
        return (expiresAt - now) < 86400 // Less than 24h remaining
    }
}
```

---

# 9. SETUP WIZARD — UX FLOW

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  1. Welcome   │ ──→ │  2. Content   │ ──→ │  3. Trakt    │
│               │     │  Preferences  │     │  Login       │
│  [Get Started]│     │  ☑ Movies     │     │  [Connect]   │
│               │     │  ☑ TV Shows   │     │  [Skip]      │
│               │     │  ☐ Anime      │     │              │
│               │     │  ☐ Live TV    │     │              │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
         ┌────────────────────────────────────────┘
         ▼
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  4. Debrid    │ ──→ │  5. Addons    │ ──→ │  6. Done!    │
│  Setup       │     │              │     │              │
│  ○ Real-Debrid│     │  [Recommended │     │  Summary:    │
│  ○ AllDebrid  │     │   Setup]      │     │  3 addons ✓  │
│  ○ Premiumize │     │  [Custom]     │     │  Debrid ✓    │
│  ○ TorBox     │     │  [Skip]       │     │  Trakt ✓     │
│  [Skip]       │     │              │     │              │
│               │     │  Auto-installs│     │  [Start →]   │
│  Paste API key│     │  Torrentio    │     │              │
│  [Validate ✓] │     │  OpenSubs     │     │              │
└──────────────┘     └──────────────┘     └──────────────┘
```

**"Recommended Setup" auto-installs these addons when debrid is connected:**
1. Torrentio (with user's debrid provider pre-configured)
2. MediaFusion (backup stream source)
3. OpenSubtitles v3 (subtitle addon)

**Total time to setup: ~90 seconds** vs Omni's 10+ minute configuration process.

---

# 10. ERROR HANDLING & RESILIENCE PATTERNS

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/util/Result.kt

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val error: AppError) : AppResult<Nothing>()
    data class Loading(val progress: Float? = null) : AppResult<Nothing>()
}

sealed class AppError(val message: String, val cause: Throwable? = null) {
    class NetworkError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class ApiError(val code: Int, message: String) : AppError(message)
    class AddonError(val addonName: String, message: String) : AppError(message)
    class DebridError(val service: String, message: String) : AppError(message)
    class PlayerError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class AuthError(message: String) : AppError(message)
    class CacheError(message: String) : AppError(message)
}

// Resilience wrapper for addon calls
suspend fun <T> withAddonResilience(
    addonName: String,
    timeoutMs: Long = 10_000,
    block: suspend () -> T,
): AppResult<T> {
    return try {
        val result = withTimeout(timeoutMs) { block() }
        AppResult.Success(result)
    } catch (e: TimeoutCancellationException) {
        AppResult.Error(AppError.AddonError(addonName, "Addon timed out after ${timeoutMs}ms"))
    } catch (e: IOException) {
        AppResult.Error(AppError.NetworkError("Network error: ${e.message}", e))
    } catch (e: Exception) {
        AppResult.Error(AppError.AddonError(addonName, "Addon failed: ${e.message}"))
    }
}

// Circuit breaker for repeatedly failing addons
class AddonCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val resetTimeMs: Long = 60_000,
) {
    private val failureCounts = mutableMapOf<String, Int>()
    private val lastFailure = mutableMapOf<String, Long>()

    fun isOpen(addonId: String): Boolean {
        val count = failureCounts[addonId] ?: 0
        if (count < failureThreshold) return false
        val last = lastFailure[addonId] ?: return false
        if (Clock.System.now().toEpochMilliseconds() - last > resetTimeMs) {
            // Reset after cooldown
            failureCounts[addonId] = 0
            return false
        }
        return true // Circuit is open, skip this addon
    }

    fun recordFailure(addonId: String) {
        failureCounts[addonId] = (failureCounts[addonId] ?: 0) + 1
        lastFailure[addonId] = Clock.System.now().toEpochMilliseconds()
    }

    fun recordSuccess(addonId: String) {
        failureCounts[addonId] = 0
    }
}
```

---

# 11. SECURITY — API KEY STORAGE

## 11.1 Platform Abstraction

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/platform/SecureStorage.kt

// expect/actual pattern for platform-specific secure storage
expect class SecureStorage {
    fun store(key: String, value: String)
    fun retrieve(key: String): String?
    fun delete(key: String)
}
```

## 11.2 iOS — Keychain

```swift
// iosApp/StreamVault/Platform/SecureStorage.swift

actual class SecureStorage {
    actual func store(key: String, value: String) {
        let data = value.data(using: .utf8)!
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: "com.streamvault",
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        SecItemDelete(query as CFDictionary) // Delete existing
        SecItemAdd(query as CFDictionary, nil)
    }

    actual func retrieve(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: "com.streamvault",
            kSecReturnData as String: true,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    actual func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: "com.streamvault",
        ]
        SecItemDelete(query as CFDictionary)
    }
}
```

## 11.3 Android — EncryptedSharedPreferences

```kotlin
// androidApp/.../platform/SecureStorage.kt

actual class SecureStorage(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "streamvault_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    actual fun store(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    actual fun retrieve(key: String): String? = prefs.getString(key, null)
    actual fun delete(key: String) { prefs.edit().remove(key).apply() }
}
```

## 11.4 What Gets Stored Securely

```
SecureStorage keys:
├── "rd_api_key"      — Real-Debrid API token
├── "ad_api_key"      — AllDebrid API token
├── "pm_api_key"      — Premiumize API token
├── "tb_api_key"      — TorBox API token
├── "trakt_token"     — Trakt OAuth access token (JSON)
├── "tmdb_session"    — TMDB session ID (if using v4 auth)
└── "sync_key"        — Cloud sync encryption key
```

---

# 12. ACCESSIBILITY & LOCALIZATION

## 12.1 Accessibility Checklist

```
iOS (VoiceOver):
☐ All MediaCard views have accessibilityLabel with title + year + rating
☐ Stream quality badges read as "1080p, 5.2 gigabytes, cached"
☐ Player controls: "Play button", "Seek forward 15 seconds"
☐ EPG grid navigable with VoiceOver gestures
☐ Dynamic Type support on all text elements
☐ Reduce Motion: disable parallax effects and animated transitions

Android (TalkBack):
☐ contentDescription on all interactive elements
☐ importantForAccessibility set correctly
☐ Focus order follows logical reading order
☐ Android TV: D-pad navigation fully functional with TalkBack
☐ Material 3 dynamic color support for high contrast

Both platforms:
☐ Minimum touch target: 44x44pt (iOS), 48x48dp (Android)
☐ Color contrast ratio ≥ 4.5:1 for all text
☐ No information conveyed by color alone (e.g., cached badge also has icon)
☐ Subtitle customization: size, color, background, position
```

## 12.2 Localization Setup

```kotlin
// shared/src/commonMain/kotlin/com/streamvault/util/Strings.kt

// Use expect/actual for platform string resources
// Or use a KMP-compatible library like Lyricist or moko-resources

// Supported languages (MVP):
// en — English (default)
// de — German
// es — Spanish
// fr — French
// pt — Portuguese
// nl — Dutch

// Post-MVP:
// zh — Chinese
// ja — Japanese
// ko — Korean
// ar — Arabic (RTL support)
// ru — Russian
```

---

# 13. PERFORMANCE BUDGETS

```
┌─────────────────────────────────┬──────────────┬──────────────┐
│ Metric                          │ Target       │ Acceptable   │
├─────────────────────────────────┼──────────────┼──────────────┤
│ App startup (cold)              │ < 1.5s       │ < 2.5s       │
│ App startup (warm)              │ < 500ms      │ < 1s         │
│ Home screen first paint         │ < 1s         │ < 2s         │
│ Search response                 │ < 500ms      │ < 1s         │
│ Detail page load                │ < 800ms      │ < 1.5s       │
│ Stream resolution (all addons)  │ < 3s         │ < 5s         │
│ Debrid resolve to playback URL  │ < 2s         │ < 4s         │
│ Time to first frame (player)    │ < 3s         │ < 6s         │
│ Subtitle load                   │ < 1s         │ < 2s         │
│ EPG parse (5000 programmes)     │ < 2s         │ < 5s         │
│ M3U parse (1000 channels)       │ < 500ms      │ < 1s         │
│ Memory usage (browsing)         │ < 150MB      │ < 250MB      │
│ Memory usage (playback)         │ < 300MB      │ < 500MB      │
│ Battery (1hr playback)          │ < 10%        │ < 15%        │
│ App size (iOS)                  │ < 50MB       │ < 80MB       │
│ App size (Android APK)          │ < 40MB       │ < 70MB       │
│ Crash rate                      │ < 0.5%       │ < 1%         │
│ ANR rate (Android)              │ < 0.1%       │ < 0.5%       │
│ Frame drop rate (UI scrolling)  │ < 1%         │ < 3%         │
└─────────────────────────────────┴──────────────┴──────────────┘
```

---

# 14. TESTING STRATEGY

## 14.1 Test Pyramid

```
                    ┌─────────┐
                    │  E2E    │  5%  — Appium / XCUITest
                    │  Tests  │       Critical user flows only
                   ─┤         ├─
                  / └─────────┘ \
                 ┌───────────────┐
                 │  Integration  │  25% — Ktor mock server
                 │  Tests        │        Real DB, fake network
                ─┤               ├─
               / └───────────────┘ \
              ┌─────────────────────┐
              │  Unit Tests         │  70% — Pure logic tests
              │                     │        Domain, ViewModels
              └─────────────────────┘
```

## 14.2 Key Test Cases

```kotlin
// Addon Engine Tests
class StreamAggregatorTest {
    @Test
    fun `resolves streams from multiple addons in parallel`()
    @Test
    fun `deduplicates streams by info hash`()
    @Test
    fun `respects addon timeout of 10 seconds`()
    @Test
    fun `marks debrid-cached streams correctly`()
    @Test
    fun `filters by minimum quality preference`()
    @Test
    fun `sorts by quality then seeds descending`()
    @Test
    fun `gracefully handles addon failures without affecting others`()
    @Test
    fun `circuit breaker opens after 3 consecutive failures`()
}

// Debrid Service Tests
class RealDebridServiceTest {
    @Test
    fun `authenticates with valid API key`()
    @Test
    fun `batch cache check returns correct hash status`()
    @Test
    fun `resolves cached torrent to direct URL`()
    @Test
    fun `selects largest video file from torrent`()
    @Test
    fun `handles expired API key gracefully`()
}

// M3U Parser Tests
class M3uParserTest {
    @Test
    fun `parses basic M3U with channels`()
    @Test
    fun `extracts header EPG URL`()
    @Test
    fun `handles all tvg attributes`()
    @Test
    fun `handles EXTVLCOPT and KODIPROP directives`()
    @Test
    fun `handles malformed lines gracefully`()
    @Test
    fun `parses 1000 channels under 500ms`()
    @Test
    fun `handles UTF-8 channel names`()
}

// EPG Parser Tests
class EpgParserTest {
    @Test
    fun `parses XMLTV channels and programmes`()
    @Test
    fun `handles timezone offsets correctly`()
    @Test
    fun `parses 5000 programmes under 2 seconds`()
}

// TMDB Client Tests
class TmdbApiClientTest {
    @Test
    fun `fetches trending movies`()
    @Test
    fun `searches by title`()
    @Test
    fun `fetches movie detail with credits`()
    @Test
    fun `fetches TV series seasons and episodes`()
    @Test
    fun `caches metadata for 7 days`()
}

// Recommendation Engine Tests
class GetRecommendationsUseCaseTest {
    @Test
    fun `generates recommendations based on genre preference`()
    @Test
    fun `filters out already watched items`()
    @Test
    fun `provides reason strings for each recommendation`()
    @Test
    fun `handles empty watch history gracefully`()
    @Test
    fun `actor affinity increases score for matching cast`()
}

// ViewModel Tests
class DetailViewModelTest {
    @Test
    fun `loads metadata on init`()
    @Test
    fun `resolves streams updates UI state`()
    @Test
    fun `auto-play selects best cached stream`()
    @Test
    fun `error state shown on debrid failure`()
}

// Trakt Tests
class TraktScrobblerTest {
    @Test
    fun `sends start scrobble on playback begin`()
    @Test
    fun `sends pause scrobble on user pause`()
    @Test
    fun `auto-marks watched at 80 percent progress`()
    @Test
    fun `refreshes expired token automatically`()
}
```

## 14.3 CI Integration

```yaml
# Run on every PR
shared-tests:       # KMP unit tests (commonTest)
android-lint:       # Lint + Detekt
android-unit:       # Android-specific unit tests
ios-unit:           # iOS XCTest suite

# Run nightly
integration-tests:  # Ktor mock server integration tests
performance-tests:  # Benchmark parse times, cache hits
snapshot-tests:     # UI screenshot comparison (Paparazzi for Android)

# Run on release branch
e2e-tests:          # Appium flows on real devices (BrowserStack)
```

---

# APPENDIX A: DEBRID SERVICE COMPARISON

```
┌───────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
│               │ Real-Debrid  │ AllDebrid    │ Premiumize   │ TorBox       │
├───────────────┼──────────────┼──────────────┼──────────────┼──────────────┤
│ Price         │ ~$4/mo       │ ~$3/mo       │ ~$10/mo      │ ~$3/mo       │
│ Cache Check   │ Batch (100)  │ Batch (50)   │ Batch (200)  │ Batch (100)  │
│ API Style     │ REST         │ REST         │ REST         │ REST         │
│ Auth          │ API key      │ API key      │ API key      │ API key      │
│ Rate Limits   │ ~250 req/min │ ~150 req/min │ Generous     │ ~200 req/min │
│ CDN Speed     │ Very fast    │ Fast         │ Fast         │ Fast         │
│ Popularity    │ Most popular │ Growing      │ Premium      │ Newest       │
│ Direct DL     │ ✓            │ ✓            │ ✓            │ ✓            │
│ Streaming     │ ✓            │ ✓            │ ✓            │ ✓            │
└───────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
```

---

# APPENDIX B: STREMIO ADDON PROTOCOL QUICK REFERENCE

```
Base URL structure: https://addon-host.com/{optional-config}/

Endpoints:
  GET /manifest.json              → AddonManifest
  GET /catalog/{type}/{id}.json   → { metas: [...] }
  GET /stream/{type}/{id}.json    → { streams: [...] }
  GET /meta/{type}/{id}.json      → { meta: {...} }
  GET /subtitles/{type}/{id}.json → { subtitles: [...] }

Types: "movie", "series", "channel", "tv", "anime", "other"

IDs for streams:
  Movies:  "tt1234567"                    (IMDB ID)
  Series:  "tt1234567:1:5"               (IMDB:season:episode)
  TMDB:    "tmdb:12345"                   (if addon supports)
  Kitsu:   "kitsu:12345"                  (anime)

Stream object:
{
  "url": "https://direct-link.mp4",       // Direct HTTP(S) stream
  "infoHash": "abc123...",                 // Torrent magnet hash
  "fileIdx": 0,                           // File index in torrent
  "externalUrl": "https://...",           // Open in browser
  "ytId": "dQw4w9WgXcQ",                 // YouTube video ID
  "name": "Source Name",                  // e.g., "Torrentio\n4K"
  "title": "1080p BluRay REMUX DTS-HD",   // Detailed description
  "behaviorHints": {
    "notWebReady": true,                  // Needs transcoding
    "bingeGroup": "torrentio|720p",       // Group similar streams
    "proxyHeaders": { "request": {"User-Agent": "..."} }
  }
}
```

---

# APPENDIX C: TMDB API ENDPOINTS USED

```
Base: https://api.themoviedb.org/3

Authentication:
  All requests: ?api_key=YOUR_KEY  or  Authorization: Bearer YOUR_TOKEN

Endpoints:
  GET /trending/{type}/week                     → Trending content
  GET /search/multi?query={q}                   → Universal search
  GET /movie/{id}?append_to_response=credits,similar,recommendations,videos
  GET /tv/{id}?append_to_response=credits,similar,recommendations
  GET /tv/{id}/season/{num}                     → Season episodes
  GET /discover/movie?with_genres={id}&vote_average.gte={min}
  GET /discover/tv?with_genres={id}
  GET /movie/{id}/similar                       → Similar movies
  GET /movie/{id}/recommendations               → Recommended movies
  GET /genre/movie/list                         → Genre ID mapping
  GET /genre/tv/list

Image URLs:
  Poster:   https://image.tmdb.org/t/p/w500{poster_path}
  Backdrop: https://image.tmdb.org/t/p/w1280{backdrop_path}
  Profile:  https://image.tmdb.org/t/p/w185{profile_path}

Rate limit: 50 requests / second (generous for a client app)
```

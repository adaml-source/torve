# StreamVault — Product & Development Plan

## Cross-Platform Media Hub: MVP Scope, Tech Stack & Roadmap

---

## 1. Product Vision

**One-liner:** The media app that combines Infuse-quality playback with Stremio's content ecosystem — cross-platform, beautiful, and dead simple.

**Target Users:**
- Cord-cutters who use debrid services (Real-Debrid, AllDebrid, Premiumize, TorBox)
- Users frustrated with multi-app workflows (Wako → Real-Debrid → Infuse)
- Apple ecosystem users who want Stremio-like functionality on iOS/tvOS
- Android users who want a more polished alternative to Stremio
- Power users who want IPTV + VOD in a single app

**Core Value Proposition:**
> "Browse. Pick. Watch. One app. Every device."

No more juggling 3 apps. No more WebDAV hacks. No more KSPlayer stuttering.

---

## 2. Competitive Positioning

| Feature | Infuse | Omni | Stremio Lite | Fusion | **StreamVault** |
|---------|--------|------|-------------|--------|-----------------|
| Playback Quality | ★★★★★ | ★★☆☆☆ | ★★★☆☆ | ★★★☆☆ | ★★★★★ |
| Content Discovery | ✗ | ★★★★☆ | ★★★☆☆ | ★★★★☆ | ★★★★★ |
| Addon Ecosystem | ✗ | ★★★★☆ | ★★★☆☆ | ★★★★☆ | ★★★★★ |
| Debrid Integration | ★★☆☆☆ | ★★★★☆ | ★★★☆☆ | ★★★★☆ | ★★★★★ |
| iOS / Apple TV | ✓ | ✓ | ✓ | TestFlight | ✓ |
| Android / Android TV | ✗ | ✗ | ✓ | ✗ | ✓ |
| IPTV / M3U | ✗ | ✗ | via addon | ✗ | ✓ |
| Ease of Setup | ★★★★☆ | ★★☆☆☆ | ★★★☆☆ | ★★☆☆☆ | ★★★★★ |
| Recommendations | ✗ | ✗ | ✗ | ✗ | ✓ |

---

## 3. MVP Feature Set (v1.0)

### Tier 1 — Must Ship (Launch Blockers)

**Playback Engine**
- High-quality video player based on MPV (libmpv) or custom FFmpeg pipeline
- Dolby Vision, HDR10, HDR10+ support
- DTS, DTS-HD MA, Dolby TrueHD, Dolby Atmos passthrough
- MKV, MP4, AVI, HEVC, AV1, WEBM and all common containers
- Hardware-accelerated decoding on all platforms
- Subtitle support: SRT, ASS/SSA, PGS, VobSub, embedded
- Picture-in-Picture (iOS/Android)
- AirPlay and Chromecast output

**Content Discovery & Browsing**
- TMDB-powered home screen: Trending, Popular, Top Rated, Upcoming
- Genre browsing with rich metadata (posters, backdrops, ratings, trailers)
- Search across all catalogs simultaneously
- Movie/show detail pages with cast, reviews, similar titles
- "Where to Watch" — show which streaming services have it (JustWatch-style data)

**Stremio Addon Compatibility**
- Full support for Stremio addon API (catalogs, streams, subtitles, metadata)
- In-app addon configuration (no external browser needed — this is what makes Omni great)
- Pre-configured addon recommendations for new users
- Addon snapshots — export/import your full configuration

**Debrid Integration (First-Class)**
- Native Real-Debrid, AllDebrid, Premiumize, TorBox support
- Direct API integration — no WebDAV hack
- Instant stream resolution — pick quality, see file size, play
- Auto-select best available stream based on user preferences (4K > 1080p, etc.)

**Tracking & Progress**
- Trakt.tv integration (scrobbling, watchlist sync, watch history)
- Continue Watching shelf on home screen
- Cross-device sync via iCloud (Apple) / Google account (Android)
- Up Next — auto-queue next episode

**User Experience**
- 2-minute setup wizard: sign in to Trakt → connect debrid → auto-add popular addons → done
- Clean, Netflix-style UI — not overwhelming like Omni
- Dark mode default, OLED-friendly

### Tier 2 — Fast Follow (v1.1–1.2)

- M3U / IPTV playlist support with EPG guide
- User profiles with separate watch history and preferences
- Parental controls (content rating filters)
- Smart recommendations ("Because you watched X...")
- Offline download queue (debrid → local storage)
- Custom home screen shelves and catalog groups
- Stream quality preferences per network (WiFi vs cellular)
- Lock screen widget (Continue Watching)

### Tier 3 — Differentiation (v1.3+)

- Social features — share watchlists with friends, see what friends are watching
- AI-powered search ("find me a thriller from the 2010s with a twist ending")
- Calendar view for upcoming episodes of tracked shows
- Multi-audio/multi-subtitle quick switcher in player
- Skip intro / skip credits detection
- Watch party — synchronized viewing with friends
- Immersive environments for visionOS

---

## 4. Technical Architecture

### 4.1 Cross-Platform Strategy

```
┌─────────────────────────────────────────────────────┐
│                    Shared Core (Kotlin Multiplatform) │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │ Addon   │ │ Debrid   │ │ Metadata │ │ Trakt   │ │
│  │ Engine  │ │ Client   │ │ Service  │ │ Sync    │ │
│  └─────────┘ └──────────┘ └──────────┘ └─────────┘ │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐             │
│  │ Stream  │ │ Catalog  │ │ User     │             │
│  │ Resolver│ │ Manager  │ │ Prefs DB │             │
│  └─────────┘ └──────────┘ └──────────┘             │
└─────────────────────────────────────────────────────┘
          │                          │
    ┌─────┴─────┐              ┌─────┴─────┐
    │   Apple   │              │  Android  │
    │ SwiftUI   │              │ Jetpack   │
    │ + libmpv  │              │ Compose   │
    │           │              │ + libmpv  │
    │ iOS       │              │ Phone     │
    │ tvOS      │              │ Android TV│
    │ macOS     │              │ Fire TV   │
    │ visionOS  │              │           │
    └───────────┘              └───────────┘
```

### 4.2 Recommended Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| **Shared Business Logic** | Kotlin Multiplatform (KMP) | Single codebase for networking, data models, addon engine, debrid clients. Compiles to native on both platforms. |
| **iOS/tvOS/macOS UI** | SwiftUI | Native Apple look and feel, best performance on Apple devices, easy Apple TV adaptation |
| **Android/TV UI** | Jetpack Compose | Modern Android UI, great TV support via Leanback Compose |
| **Video Player** | libmpv (MPV) | Battle-tested, supports every codec/format, Dolby Vision capable, hardware acceleration. Streamer app already proves this works on iOS. |
| **Local Database** | SQLite via SQLDelight (KMP) | Cross-platform, shared schema and queries |
| **Networking** | Ktor (KMP) | Cross-platform HTTP client, works with KMP serialization |
| **Image Loading** | Coil (Android) / Nuke (iOS) | Platform-native, fast, caching |
| **Metadata API** | TMDB API v3/v4 | Industry standard, free tier generous, rich data |
| **Tracking** | Trakt.tv API | Community standard for watch tracking |
| **Cloud Sync** | iCloud (Apple) / Firebase (Android) | Platform-native sync, no custom backend needed for MVP |
| **Analytics** | PostHog or Mixpanel | Privacy-friendly, self-hostable option |

### 4.3 Alternative: React Native / Expo

If you want faster iteration with a smaller team:

| Layer | Technology | Tradeoff |
|-------|-----------|----------|
| **UI + Logic** | React Native + Expo | Single codebase for both platforms. Faster development. |
| **TV Support** | react-native-tvos | Works but less polished than native |
| **Video Player** | react-native-video (v6+) with libmpv | Good enough for most cases, but DV/DTS passthrough may need native modules |
| **State** | Zustand or Redux Toolkit | Simple, performant |
| **Navigation** | React Navigation | Mature, TV-compatible |

**Verdict:** React Native gets you to market faster. KMP + native UI delivers a better product. For a media app where playback quality is the #1 differentiator, I'd lean toward KMP + native UI.

### 4.4 Addon Engine Architecture

The addon engine is the heart of the app. It must be compatible with the Stremio addon protocol:

```
Stremio Addon Protocol (HTTP JSON API)
├── /manifest.json          — addon metadata, capabilities
├── /catalog/{type}/{id}    — content catalogs (movies, series, TV)
├── /stream/{type}/{id}     — stream sources for a given IMDB/TMDB ID
├── /meta/{type}/{id}       — metadata enrichment
└── /subtitles/{type}/{id}  — subtitle sources
```

Your addon engine should:
1. Maintain a registry of installed addon URLs
2. Fan out requests to all relevant addons in parallel
3. Merge and deduplicate results
4. Apply user filters (quality, size, language)
5. Cache results aggressively (catalogs: 1hr, streams: 5min)
6. Handle addon failures gracefully (timeout, retry, skip)

### 4.5 Debrid Integration Architecture

```
Stream URL from addon (magnet/torrent hash)
        │
        ▼
┌──────────────────┐
│  Debrid Resolver  │
│                   │
│  1. Check cache   │◄── Is this torrent already cached on RD?
│  2. If cached:    │     └─ Yes → Get direct download link
│     get link      │     └─ No  → Add to RD, wait for cache
│  3. Return HTTP   │
│     stream URL    │
└──────────────────┘
        │
        ▼
   MPV Player (direct HTTP playback)
```

Debrid services supported at launch:
- **Real-Debrid** — largest user base, most cached content
- **AllDebrid** — good alternative
- **Premiumize** — premium option
- **TorBox** — growing community

Each needs its own API client, but the interface is the same:
```kotlin
interface DebridService {
    suspend fun checkCache(hashes: List<String>): Map<String, Boolean>
    suspend fun addTorrent(magnet: String): TorrentInfo
    suspend fun getStreamUrl(torrentId: String, fileId: String): String
    suspend fun getAccountInfo(): AccountInfo
}
```

---

## 5. App Store Compliance Strategy

This is critical. Here's how to stay on the App Store:

### What the App IS (publicly)
- A media player and content discovery tool
- Connects to cloud storage services (WebDAV, SMB, FTP, cloud drives)
- Browses TMDB for movie/show information
- Tracks watch history via Trakt
- Plays local and network media files

### What the App DOES (for power users)
- Stremio-compatible addon system (addons are user-configured URLs, not bundled)
- Debrid service connections framed as "cloud storage" / "premium file hosting"
- Stream resolution happens via user-configured services

### Red Lines — Do NOT
- ❌ Bundle any addons that provide pirated content
- ❌ Include a built-in addon directory/store that lists unofficial addons
- ❌ Include torrent downloading functionality
- ❌ Advertise debrid integration as a primary feature on the App Store listing
- ❌ Use words like "torrent", "pirate", "free movies" in any store-facing content

### Safe Patterns
- ✅ "Connect your cloud storage" (this is how Infuse frames WebDAV/RD)
- ✅ "Install community addons via URL" (user's choice, not app's)
- ✅ "Compatible with Stremio addon protocol" (legitimate open protocol)
- ✅ Addon URLs are entered manually by the user
- ✅ App Store screenshots show TMDB browsing and local media playback only

---

## 6. Development Roadmap

### Phase 0: Foundation (Weeks 1–4)
- [ ] Set up KMP project structure (or React Native if chosen)
- [ ] Implement TMDB API client + metadata models
- [ ] Build basic catalog browsing UI (home screen, search, detail pages)
- [ ] Integrate libmpv for video playback on iOS and Android
- [ ] Basic local file playback working on both platforms

### Phase 1: Core Experience (Weeks 5–10)
- [ ] Stremio addon engine — manifest parsing, catalog fetching, stream resolution
- [ ] Debrid service clients (Real-Debrid first, then AllDebrid)
- [ ] Stream selection UI — quality picker with file size, codec info
- [ ] Trakt integration — auth, scrobbling, watchlist sync
- [ ] Continue Watching + Up Next logic
- [ ] Subtitle fetching and rendering (addon-sourced + OpenSubtitles)

### Phase 2: Polish & Platform (Weeks 11–16)
- [ ] Apple TV / Android TV UI adaptation
- [ ] AirPlay / Chromecast support
- [ ] Setup wizard for first-run experience
- [ ] Addon configuration UI (in-app, no external browser)
- [ ] iCloud / Firebase sync for cross-device state
- [ ] Player refinements: skip forward/back, speed control, audio track selection
- [ ] Picture-in-Picture

### Phase 3: TestFlight / Beta (Weeks 17–20)
- [ ] Closed beta on TestFlight (iOS) and Google Play beta track
- [ ] Performance optimization (startup time, catalog loading, stream resolution speed)
- [ ] Crash reporting + analytics integration
- [ ] App Store listing preparation (screenshots, description, review-safe language)
- [ ] Community Discord setup for beta feedback

### Phase 4: Launch (Weeks 21–24)
- [ ] App Store + Google Play submission
- [ ] Marketing site / landing page
- [ ] Launch on Reddit (r/StremioAddons, r/Addons4Stremio, r/cordcutters, r/appletv)
- [ ] Post-launch: monitor crash reports, fast-fix cycle

### Phase 5: Growth Features (Post-Launch)
- [ ] IPTV / M3U support
- [ ] User profiles
- [ ] Smart recommendations engine
- [ ] Offline downloads
- [ ] Social features
- [ ] Watch party

---

## 7. Monetization Strategy

### Recommended: Freemium + One-Time Purchase

| Tier | Price | Features |
|------|-------|----------|
| **Free** | $0 | TMDB browsing, local file playback, 1 addon, Trakt tracking, basic player |
| **Pro** | $9.99 one-time | Unlimited addons, all debrid services, IPTV, auto-play best stream, profiles, PiP, advanced player features |
| **Pro+** (optional) | $2.99/month or $19.99/year | Cloud sync across platforms, priority stream resolution, AI recommendations, watch party |

### Why one-time purchase works
- Infuse users consistently complain about subscriptions
- Omni charges $10 one-time and users love it
- One-time purchase creates goodwill and word-of-mouth
- You can still offer an optional subscription for premium cloud features

### Revenue Projections (Conservative)
- Target: 10,000 Pro purchases in Year 1
- Revenue: ~$70,000 (after App Store 30% cut on $9.99)
- Growth path: IPTV power users, Android TV market, visionOS

---

## 8. Team Requirements (MVP)

| Role | Count | Responsibility |
|------|-------|---------------|
| **iOS/Swift Developer** | 1 | SwiftUI, tvOS, libmpv integration, Apple platform specifics |
| **Android/Kotlin Developer** | 1 | Jetpack Compose, Android TV, libmpv integration |
| **Shared/Backend Developer** | 1 | KMP business logic, addon engine, debrid clients, Trakt/TMDB APIs |
| **UI/UX Designer** | 1 (part-time or contract) | App design, TV UI, onboarding flow |

**Solo developer path:** If you're building this alone, go React Native. You'll sacrifice some playback quality but ship 2x faster. Hire a native iOS dev later to build a custom MPV bridge if needed.

---

## 9. Key Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| App Store rejection | Critical | Conservative store listing, no piracy-adjacent language, frame addons as "community extensions" |
| Apple pulls the app later | High | Build Android first to have a fallback, maintain TestFlight as backup distribution |
| Stremio addon API changes | Medium | Abstract the addon interface, support versioned protocols |
| Debrid services go down/change API | Medium | Pluggable debrid architecture, support multiple services |
| Infuse adds addon support | High | Unlikely given their App Store positioning, but differentiate on cross-platform + ease of use |
| Omni improves rapidly | Medium | Omni is Apple-only and solo dev. Move faster, be cross-platform, nail the player |
| MPV/libmpv licensing (LGPL) | Low | LGPL allows dynamic linking. Ship MPV as a dynamic library, not statically linked. Consult a lawyer. |

---

## 10. Success Metrics

### Launch (Month 1)
- 1,000+ downloads
- 4.5+ star rating
- < 1% crash rate
- Average stream-to-playback time < 5 seconds

### Growth (Month 3)
- 5,000+ downloads
- Active community Discord (500+ members)
- 3+ positive mentions on Reddit/tech forums
- 50%+ Day 7 retention

### Scale (Month 6)
- 10,000+ Pro purchases
- Featured in "Apps We Love" or similar editorial
- Community-contributed addon ecosystem growing
- Android TV / Fire TV traction

---

## 11. Quick Start — What to Do This Week

1. **Decide on tech stack** — KMP + native UI (better product) vs React Native (faster to market)
2. **Register TMDB API key** — free at https://developer.themoviedb.org
3. **Register Trakt API key** — free at https://trakt.tv/oauth/applications
4. **Get a Real-Debrid account** — $4/month, needed for testing
5. **Study the Stremio addon protocol** — https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
6. **Download and test Omni** — $10, your primary competitor, understand its strengths and weaknesses firsthand
7. **Join the Omni, Fusion, and Stremio Discords** — listen to what users want
8. **Start with the player** — get libmpv playing a test video on iOS. This is the hardest technical risk. Prove it first.

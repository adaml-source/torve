# Torve Channel Playback Diff: TV vs Android Mobile

Date: 2026-03-11

Scope:
- Mobile live/channel playback path in `androidApp/src/main/kotlin/com/torve/android/ui/player/PlayerScreen.kt`
- TV live/channel playback path in `androidApp/src/tv/kotlin/com/torve/android/tv/screens/TvLivePlayerScreen.kt`
- Shared engine behavior in:
  - `androidApp/src/main/kotlin/com/torve/android/player/ExoPlayerEngine.kt`
  - `androidApp/src/main/kotlin/com/torve/android/player/MPVPlayerEngine.kt`
  - `androidApp/src/main/kotlin/com/torve/android/player/LiveAudioCompatibilityStore.kt`
  - `androidApp/src/main/kotlin/com/torve/android/player/LiveAudioDiagnostics.kt`

## Executive summary

Matching the mobile playback configuration on TV is not a large codec project. It is mostly a playback-path divergence problem.

The highest-impact difference is simple:

- Mobile is `MPV-first` and mostly leaves the stream alone.
- TV is `compatibility-stack first`, usually starts from `ExoPlayer`, applies remembered hints, and can enter TV-only audio recovery/restart logic.

For channels that already work on mobile, the fastest path to parity is:

1. Make TV first-attempt live playback match mobile exactly.
2. Only run the TV compatibility ladder after that mobile-equivalent first attempt fails.

Estimated difficulty:

- Narrow parity fix: low to medium.
  - Mostly `TvLivePlayerScreen.kt` and how it chooses/configures the first engine.
- Full architectural parity: medium.
  - Requires separating "first attempt" from "recovery attempt" more cleanly and reducing TV-only policy leakage.

## Side-by-side diff

### 1. Initial engine selection

Mobile:
- `PlayerScreen.kt` creates the engine once.
- It tries `MPVPlayerEngine.initialize()` first.
- If MPV initializes, mobile stays on MPV.
- ExoPlayer is only used if MPV is unavailable.

TV:
- `TvLivePlayerScreen.kt` builds a per-channel `LivePlayerEngineSession`.
- It resolves a preferred engine from `LiveAudioCompatibilityStore`.
- If there is no remembered engine, TV currently defaults to `ExoPlayer`.
- TV can rebuild the session during recovery and channel zapping.

Impact:
- Mobile reaches the known-good MPV path immediately.
- TV often starts on Exo first, which is already a divergence from the working mobile reference.

Alignment cost:
- Low.
- Change the TV first attempt to `MPV-first`, or explicitly use a "mobile-reference first pass" before any compatibility hint logic.

### 2. Remembered compatibility hints

Mobile:
- Mobile live logging reports `rememberedHint = null`.
- Mobile does not use `LiveAudioCompatibilityStore` to choose engine/track on first play.
- Mobile is closer to a raw player path.

TV:
- TV resolves remembered hints and preferred engine via `LiveAudioCompatibilityStore`.
- TV passes `setLivePlaybackContext(channel, honorRememberedHints)` to both engines.
- TV can also retry with `honorRememberedHints = false` in "mobile reference" recovery mode.

Impact:
- TV can be pushed into a stale or overfit path that mobile never takes.
- This adds a second source of divergence beyond engine choice.

Alignment cost:
- Low to medium.
- The clean fix is to skip remembered hints on the first TV live attempt and only apply them after a success has been proven on that device/channel.

### 3. Silent-session recovery

Mobile:
- No TV-style silent-session probe.
- No delayed "audio is probably silent" heuristic.
- No auto track reselection or engine switch driven by that heuristic in `PlayerScreen.kt`.

TV:
- TV runs a delayed silent-session probe after playback is stable.
- TV computes a risky-track heuristic.
- TV can:
  - switch audio track
  - restart MPV with hints disabled
  - switch Exo -> MPV
  - switch MPV -> Exo

Impact:
- TV may move away from a potentially working path because of a heuristic that mobile never runs.
- Even when intended as recovery, this introduces churn that mobile does not have.

Alignment cost:
- Medium.
- Best approach is not to remove TV recovery entirely, but to move it behind a mobile-equivalent first attempt.

### 4. Audio track handling

Mobile:
- Uses whatever the active engine selects.
- Logs selected track and inventory.
- Does not perform TV-specific aggressive live track reselection in `PlayerScreen.kt`.

TV:
- Keeps explicit live audio track state.
- Has TV-side alternate-track scoring and silent-session track reselection.
- Exo and MPV both apply remembered track hints if available.

Impact:
- TV can pick a different track than mobile even on the same engine.
- TV can also re-pick the track after startup based on its own heuristics.

Alignment cost:
- Medium.
- If mobile is the reference, TV should preserve the engine-selected track on the first pass and only enter alternate-track scoring after failure evidence.

### 5. Failure callbacks and restart behavior

Mobile:
- Generic playback errors can trigger stream fallback.
- Exo codec errors can trigger stream fallback or back navigation.
- Mobile does not have live-audio-specific engine churn in the main live playback path.

TV:
- Exo live-audio compatibility failures can directly trigger engine switch to MPV.
- TV has pending engine recovery and pending track recovery state.
- TV can rebuild the live player session mid-channel.

Impact:
- TV has more moving parts around audio specifically.
- That is useful for broken streams, but it is still a divergence from the mobile path when the channel is already known-good on mobile.

Alignment cost:
- Medium.
- Keep the recovery ladder, but only after a "mobile-first" attempt has been exhausted.

### 6. Session lifetime

Mobile:
- Engine is created once via `remember`.
- URL changes reuse the same engine instance.

TV:
- Engine session can be replaced per channel and during recovery.
- Channel zapping and recovery are more tightly coupled to engine lifecycle.

Impact:
- TV has more opportunities to lose working state or re-enter a non-working startup path.

Alignment cost:
- Medium.
- Full parity would mean keeping one engine stable longer on TV as well, but a first-pass MPV-first change would likely cover most of the benefit without a full lifecycle rewrite.

## Practical root cause for "mobile works, TV silent"

The likely root cause is not missing decoder capability. It is that TV does not start from the same playback contract as mobile.

Mobile contract:
- MPV first
- no remembered live-audio hint on startup
- no TV-only silent-session heuristic before first playback outcome

TV contract:
- remembered preferred engine or Exo default
- remembered live-audio hint on startup
- TV-only silent-session recovery and possible engine/track churn

That means TV is not actually reproducing the mobile playback path that already works.

## Minimal parity plan

### Recommended first pass

For TV live channels:

1. First attempt:
   - force `MPV`
   - ignore remembered engine and remembered track hints
   - apply only user audio prefs:
     - passthrough
     - surround preference
     - live audio output mode
   - do not run silent-session recovery during the first short startup window

2. If first attempt hard-fails or still has no selected audio track after settle:
   - run the existing TV compatibility ladder
   - allow track reselection
   - allow Exo fallback
   - allow remembered compatibility hints

3. If that path succeeds:
   - remember the successful engine/track for later reuse

### Why this is the lowest-risk fix

- It aligns TV with the already working mobile reference.
- It does not delete the current TV recovery ladder.
- It limits regression risk to the startup phase.

## Suggested implementation targets

Primary:
- `androidApp/src/tv/kotlin/com/torve/android/tv/screens/TvLivePlayerScreen.kt`

Specific changes:
- Introduce an explicit `mobileReferenceFirstAttempt` mode.
- On initial channel tune:
  - use MPV if available
  - set `honorRememberedHintsForSession = false`
  - suppress silent-session recovery until the mobile-reference first pass has either succeeded or clearly failed
- Only after first-pass failure:
  - consult `LiveAudioCompatibilityStore`
  - allow Exo start
  - allow silent-session heuristic recovery

Secondary:
- `androidApp/src/main/kotlin/com/torve/android/player/LiveAudioCompatibilityStore.kt`
  - treat successful first-pass mobile-reference playback as the strongest hint source

- `androidApp/src/main/kotlin/com/torve/android/player/MPVPlayerEngine.kt`
  - keep automatic compatible track selection, but avoid overriding the engine-selected track too early on first pass unless no track is selected

- `androidApp/src/main/kotlin/com/torve/android/player/ExoPlayerEngine.kt`
  - keep current recovery ladder, but treat it as fallback, not as the initial reference path for TV

## Bottom line

How difficult is it to match mobile where it works flawlessly?

- Narrow, practical answer: not very difficult.
- The core change is behavioral, not codec-heavy.
- The biggest win is to make TV actually attempt the same startup path that mobile already proves works:
  - MPV first
  - no remembered live hint on first try
  - no early TV-only recovery churn

If that change is made cleanly, TV should stop diverging from the working mobile path on channels like `3sat HD`.

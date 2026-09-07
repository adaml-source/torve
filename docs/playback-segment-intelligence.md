# Playback Segment Intelligence

## Current playback architecture

Torve uses one Compose `PlayerScreen` for episodic VOD on Android mobile and TV. It drives either mpv or Media3 through the shared `PlayerEngine` interface, persists progress through `WatchProgressRepository`, and prepares the next episode through the existing source-continuity pipeline. `SourceProfile` is already the canonical parsed release identity used by continuation selection and subtitle matching. SQLDelight is the shared persistence layer.

The former `SkipSegmentDetector` generated an intro at the start of most episodes and credits near EOF from fixed time/range rules. Those guesses were unsafe and are removed by this design. With no trustworthy markers, playback and the existing end-of-file next-episode fallback continue normally.

## Segment model

`PlaybackSegment` is the canonical, versioned timeline record. It supports unknown, cold-open, recap, intro, content, midroll, credits, post-credit, final-credits, and next-episode-preview segments. Segments carry normalized confidence, semantic confidence, source attribution, independent evidence, release identity, and optional `creditsOverlay` state. Multiple disjoint segments of one type are valid.

## Detection pipeline

`PlaybackSegmentEngine` resolves evidence asynchronously in this order:

1. exact fingerprint cache and locally validated markers;
2. validated embedded chapters enumerated from mpv container metadata;
3. bounded external `SegmentMarkerProvider` implementations;
4. optional subtitle, audio, and sampled-visual detectors;
5. season consensus as a prior.

Providers and optional detectors fail independently. All remote timestamps are validated before fusion. Analysis failure returns an empty timeline and cannot stop playback.

Audio and visual APIs accept compact observations rather than raw media, keeping decoding platform-specific and optional. Audio matches are track-scoped. Visual analysis is designed for sampled observations around candidate windows. Subtitle analysis recognizes localized recap phrases and treats subtitle disappearance as supporting evidence only.

## Confidence and conflict handling

Confidence thresholds and boundary tolerances live in `PlaybackSegmentConfig`. Fusion first collapses correlated observations by evidence family, then combines independent families. Close boundaries reinforce one another. Material disagreement lowers confidence and disables automatic actions. Intro/recap end boundaries resolve toward the earlier value; credits boundaries resolve toward the later value.

Manual buttons require medium confidence. Automatic skipping and automatic next playback require very-high confidence, validated boundaries, an enabled user preference, and no contrary seek intent. Credit text over active dialogue or scene motion is represented as a credits overlay on content and cannot trigger Play Next.

## Source fingerprint and reconciliation

`MediaIdentity` is built from the existing `SourceProfile`. Exact torrent hash/file index or movie hash is preferred; otherwise a stable release signature uses release family, group, codecs, resolution, audio layout, frame rate, edition, runtime, and provider. URLs and signed query strings are never persisted as fingerprints.

Markers from another release are not copied by timestamp. Exact identity is accepted. Explicit alignment anchors allow piecewise translation. A recognized constant-speed frame-rate conversion can be scaled. Similar runtimes alone only reduce uncertainty; a structural runtime mismatch leaves the marker unaligned and below automatic confidence.

## Storage

SQLDelight stores one analysis row per canonical episode, media fingerprint, and analysis version. The row contains serialized normalized segments, source runtime, detector versions, validation count, and timestamps. Corrupt or obsolete rows are ignored. Algorithm changes increment `CURRENT_ANALYSIS_VERSION` and are reanalyzed lazily.

Exact-source skip undo observations are retained inside the cached evidence. One correction cannot change a boundary. Three consistent inlier observations may move an intro or recap end earlier using the median; learning never lengthens an automatic skip and remains local to the source fingerprint.

## Player integration

The player consumes a pure `PlaybackSegmentStateMachine`. It determines the current structural state, button visibility, countdown eligibility, post-credit protection, watched completion, and deterministic seek behavior. A backward seek into a skipped segment suppresses further automatic skipping for that playback session. Skip targets include confidence-sensitive safety padding before the detected end.

The existing next-source warmup remains in place and shares `SourceProfile`. With strong closing-credit evidence, the prompt may appear at credits. A known or likely post-credit scene prevents countdown until final credits. With no markers, Torve retains conservative EOF behavior.

## Failure and performance behavior

Analysis never blocks startup. Cache and chapter work are cheap; providers use short caller-enforced timeouts; local signal extraction is windowed and optional. Metadata-only operation is fully supported on low-power devices. Missing chapters, subtitles, fingerprints, network, or decoders produces normal playback. No new external telemetry is introduced, and structured diagnostics contain detector names and scores but no playback URL or credentials.

## Testing

Pure common tests cover validation, correlated-evidence fusion, conservative boundary selection, exact and mismatched release reconciliation, post-credit timelines, credits-over-content safety, watched decisions, learning medians/outliers, seeks, resume, and automatic-action confidence gates. Android tests cover focus restoration when transient segment actions disappear and when Play Next is cancelled. SQLDelight migration tests verify cache creation.

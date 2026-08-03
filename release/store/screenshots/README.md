# Store screenshot release set

Release-ready screenshots use these filename prefixes. PNG, JPEG and WebP are
accepted; a suffix such as `-tv`, `-mobile-portrait` or a locale is allowed.

1. `01-home-watch-now` — Home discovery and hero artwork.
2. `02-detail-watch-or-save` — distinct Watch now and Save permanently actions.
3. `03-acquisition-status` — Requested, Downloading, Processing and Available.
4. `04-permanent-library` — Jellyfin library hero, real posters and ratings.
5. `05-live-tv-now-next` — channels with now/next guide information.
6. `06-connections-status` — setup readiness and actionable recovery state.

TV captures must show a visible D-pad focus target. The final set should also
include mobile portrait browsing and landscape/PiP playback. Do not place API
keys, email addresses, server URLs, provider usernames or library paths in a
capture.

Run `tools/check-public-positioning.ps1 -RequireScreenshots` before publishing.

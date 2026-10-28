# Torve add-on compatibility

Torve accepts Stremio-compatible HTTP add-ons. Install the add-on's base URL or
manifest.json URL; Torve normalizes the endpoint and reads the manifest before
making it available on phone, TV, and desktop.

## Manifest contract

Required top-level fields:

- id: stable, globally unique add-on identifier
- name: user-facing name
- version: add-on version string

Supported resources are stream, catalog, meta, and subtitles. Resources may use
either the string form or object form with name, types, and idPrefixes. Unknown
resource names generate a compatibility warning but do not block installation,
allowing protocol extensions to remain forward-compatible.

Every catalog must have a non-empty type and id. Torve supports catalog extras
including search, genre, and skip; unknown extras are passed through when the
caller knows how to use them.

## Endpoint shapes

Torve calls the standard endpoints:

- /manifest.json
- /stream/{type}/{id}.json
- /catalog/{type}/{catalogId}.json
- /catalog/{type}/{catalogId}/{extras}.json
- /meta/{type}/{id}.json
- /subtitles/{type}/{id}.json

Series stream IDs use imdbId:season:episode. A stream result should provide at
least one of url, magnet, or infoHash. Direct URLs, magnet links, and
info-hash-only results are supported; Torve applies the user's source-quality,
language, cache, and codec rules before automatic playback.

## Limits and behavior

- Manifest response: 256 KiB maximum
- Stream and catalog response: 2 MiB maximum
- Meta and subtitle response: 512 KiB maximum
- Non-JSON manifest responses are rejected with a setup error
- Blank manifest identity or malformed catalogs are rejected
- Unknown JSON fields are ignored
- Add-on failures are isolated so one unhealthy add-on does not remove results
  from healthy add-ons

## Automated compatibility checks

The contract lives in StremioManifestCompatibility; its tests cover required
identity, catalog structure, mixed string/object resources, and
forward-compatible resource names.

Run:

    ./gradlew :shared:allTests

Add-on authors should also test movie and episode stream endpoints with real
IMDb IDs and verify that returned URLs support seeking and range requests where
their transport permits it.

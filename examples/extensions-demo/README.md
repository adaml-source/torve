# Torve JSON extension examples

These public fixtures exercise Torve's ordinary JSON extension installation and playback.
They contain original synthetic test media only. There are no commercial film sources,
credentials, provider API keys, application source files, or review-specific switches.

## URLs to paste in iOS

All-in-one demo manifest:

https://raw.githubusercontent.com/adaml-source/torve-demo-extensions/main/demo/manifest.json

Selectable source collection:

https://raw.githubusercontent.com/adaml-source/torve-demo-extensions/main/sources.json

In Torve, open Settings → Libraries & extensions → Extensions · Add from URL.
Paste a URL, preview it, and confirm installation. Collection entries start unselected.
Install all three demo entries to test the separate catalogue, playback and subtitle roles.
Open the installed Demo Catalogue's Details & configuration → Browse catalogue,
select the original test title, find playback sources and play it.

The video has two silent audio tracks and an embedded subtitle. The separate subtitle
extension also exposes an English WebVTT through the JSON protocol.

## Personal Panda manifests

Panda is configured separately at its provider website. Paste the complete personal
manifest URL supplied by that provider. Do not commit a personal Panda URL, management
token, debrid key or Usenet credential to this repository.

A configurable manifest can advertise `behaviorHints.configurable`,
`behaviorHints.configurationRequired`, and `configurationUrl`, or a `links` entry
whose name contains Configure. Torve opens that provider configuration in the browser.

## Source collection format

`sources.json` is a version 1 Torve collection. Its `extensions` array contains a name,
`manifestUrl`, optional description and capability hints (`catalog`, `meta`, `stream`,
`subtitles`). URLs may be HTTPS absolute URLs or relative to the collection file.
Each selected extension's real manifest is checked before installation. A collection
cannot execute code or silently install its entries.

## Test media

The original synthetic clip is provided for playback testing. It does not represent a
commercial movie. Media provenance and its SHA-256 digest are in `media/provenance.json`.
This endpoint is available to everyone, and is kept available after application review.

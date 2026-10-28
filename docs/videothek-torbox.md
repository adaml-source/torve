# Videothek and TorBox

Torve is a Newznab client that directly queries the user's saved NZB indexers. Its request identity for searches and NZB retrieval is:

```text
Torve/1.0 (+https://torve.app)
```

For Videothek, add `https://videothek.io` as a custom Newznab indexer with your own API key. Videothek must approve Torve for both **Suche** (search) and **Download** (NZB retrieval). If Torve is missing from the allowed-tools list, request it with this public repository as the project page. Do not impersonate another tool's User-Agent. A successful capabilities check does not prove search permission; Videothek can return a Newznab error inside an HTTP 200 response.

Select TorBox as the Usenet download client and supply your TorBox API key. Open a movie or episode and load its source list. Torve queries all distinct configured indexers concurrently, with individual deadlines and errors. The title search box finds titles; loading sources triggers indexer lookup. An installed Panda extension already providing this path avoids a duplicate direct lookup.

Browsing does not start a TorBox download. When a source is selected, Torve retrieves and validates the NZB, uploads its bytes to TorBox, waits for cloud preparation, and requests a playable video URL. Episode selection matches the requested season and episode and excludes samples. Saved connection and profile ownership are checked during preparation. The raw NZB URL is never passed to the video player as a ready video.

Relevant implementation:

- [ConfiguredUsenetSources](../shared/src/commonMain/kotlin/com/torve/data/usenet/ConfiguredUsenetSources.kt)
- [NewznabClient](../shared/src/commonMain/kotlin/com/torve/data/usenet/NewznabClient.kt)
- [TorBoxUsenetClient](../shared/src/commonMain/kotlin/com/torve/data/usenet/TorBoxUsenetClient.kt)
- [Focused mocked integration tests](../shared/src/commonTest/kotlin/com/torve/data/usenet/ConfiguredUsenetSourcesTest.kt)

Indexer credentials are used only for their configured indexer. The configured key is added to same-origin download URLs; third-party download URLs do not receive it. Request URLs and credential-bearing headers are excluded from transport logging.

package com.torve.data.replays

import kotlin.test.*

class WebReplayParserTest {
    @Test fun preservesRaceBroadcastQualityAndHostHeadingsForEmbedsAndLinks() {
        val sources = WebReplayParser.sources("""<p><b>F1TV 1080p - OK</b></p>
            <iframe src="https://ok.ru/videoembed/123"></iframe>
            <p><strong>Sky Sports English 720p - Dailymotion</strong></p>
            <iframe src="https://www.dailymotion.com/embed/video/x123"></iframe>
            <p>FM</p><a class="su-button" href="https://byse.example/watch">Watch</a>
            <p>Vidara</p><a class="su-button" href="https://gasobni.example/watch">Watch</a>""", "https://sports.example/race")
        assertEquals(listOf("F1TV 1080p - OK", "Sky Sports English 720p - Dailymotion", "FM", "Vidara"), sources.map { it.title })
        assertTrue(sources.all { it.gameTitle == null })
    }
    @Test fun okReplayPrefersHighestQualityProgressiveVideoOverOpenEndedHls() {
        val metadata = """{"hlsManifestUrl":"https://cdn.example/broadcast.m3u8","videos":[
            {"name":"full","url":"https://cdn.example/replay-1080.mp4"},
            {"name":"720","url":"https://cdn.example/replay-720.mp4"},
            {"name":"mobile","url":"https://cdn.example/replay-144.mp4"}]}"""
        assertEquals("https://cdn.example/replay-1080.mp4", WebReplayParser.playable(metadata, "https://ok.ru/videoembed/123"))
        assertEquals("https://cdn.example/broadcast.m3u8", WebReplayParser.playable(metadata, "https://other.example/player"))
        assertEquals("https://cdn.example/broadcast.m3u8", WebReplayParser.playable("""{"hlsManifestUrl":"https://cdn.example/broadcast.m3u8"}""", "https://ok.ru/videoembed/123"))
    }
    @Test fun preservesMatchNamesOnMultiGameEuroleaguePages() {
        val html = """<p><span><b>Paris Basketball vs Valencia Basket</b></span></p>
            <p><strong><a class="su-button" href="https://host.example/paris"><span>Watch</span></a></strong></p>
            <p>------------</p><p><b>Olympiacos Piraeus vs Fenerbahce Istanbul</b></p>
            <p><a class="su-button" href="https://host.example/olympiacos">Watch</a></p>
            <p><b>Zalgiris Kaunas vs ASVEL Villeurbanne</b></p>
            <p><a class="su-button" href="https://host.example/zalgiris">Watch</a></p>"""
        val options = WebReplayParser.sources(html, "https://sports.example/euroleague")
        assertEquals(listOf("Paris Basketball vs Valencia Basket", "Olympiacos Piraeus vs Fenerbahce Istanbul", "Zalgiris Kaunas vs ASVEL Villeurbanne"), options.map { it.gameTitle })
        assertEquals(3, options.size)
    }
    private val site = ReplayWebsite("one", "My replays", "https://sports.example/replays/")
    @Test fun parsesSiteCardsWithArtworkDatesAndSport() {
        val page = WebReplayParser.catalogue("""<div class="short_item"><div class="poster"><a href="/game"><img src="/art.jpg"></a></div><h3><a href="/game">Red &amp; Blue vs Green - NBA Full Game Replay - October 24, 2026</a></h3><div class="short_cat">NBA</div></div><a rel="next" href="?page=2">Next</a>""", site.url, site)
        assertEquals(1, page.events.size)
        val event = page.events.single()
        assertTrue(event.title.contains("Red & Blue")); assertEquals("Basketball", event.sport)
        assertEquals("2026-10-24", event.date); assertEquals("https://sports.example/art.jpg", event.artwork)
        assertEquals("https://sports.example/game", event.pageUrl)
        assertEquals("https://sports.example/replays/?page=2", page.next)
    }
    @Test fun preservesPercentEncodingInUrls() {
        assertEquals("https://sports.example/a%2Fb?id=x%26y", WebReplayUrls.normalize("/a%2Fb?id=x%26y#watch", site.url))
        assertEquals("https://sports.example/replays/game.html", WebReplayUrls.normalize("game.html", site.url))
    }
    @Test fun paginationQueriesNeverBecomePartOfEventOrPosterAddresses() {
        val base = "https://sports.example/replays/?page=2"
        assertEquals("https://sports.example/game", WebReplayUrls.normalize("/game", base))
        assertEquals("https://sports.example/replays/?page=3", WebReplayUrls.normalize("?page=3", base))
        assertEquals(base, WebReplayUrls.normalize("#comments", base))
        assertNull(WebReplayUrls.normalize("http://0177.0.0.1/"))
        assertNull(WebReplayUrls.normalize("http://0x7f.0.0.1/"))
    }
    @Test fun rejectsNonWebAndPrivateAddresses() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "http://localhost/x", "http://127.0.0.1/x", "http://10.0.0.1/", "http://172.16.0.1/", "http://192.168.1.1/", "https://user:secret@sports.example/")) assertNull(WebReplayUrls.normalize(url), url)
        assertNotNull(WebReplayUrls.normalize("https://sports.example/"))
    }
    @Test fun ignoresScriptCreatedAdFramesAndOffSiteCards() {
        val html = """<script>var s='<iframe src="https://ads.example/e/1"></iframe>';</script><article><h2><a href="https://other.example/game">Basketball replay</a></h2></article>"""
        assertTrue(WebReplayParser.catalogue(html, site.url, site).events.isEmpty())
        assertTrue(WebReplayParser.sources(html, site.url).isEmpty())
    }
    @Test fun keepsHostLabelsThroughIntermediateLinks() {
        val options = WebReplayParser.sources("""<p><b>Server #1 (OK)</b></p><p><a class="su-button" href="https://intermediate.example/event">Watch</a></p><p><b>Server #2 (FM)</b></p><p><a class="su-button" href="/second">Watch</a></p>""", site.url)
        assertEquals(listOf("Server #1 (OK)", "Server #2 (FM)"), options.map { it.title })
    }
    @Test fun resolvesOkMetadataEncodedInAnHtmlAttribute() {
        val html = """<div data-options="{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;hlsManifestUrl\&quot;:\&quot;https://cdn.example/video.m3u8?x=1\\u0026y=2\&quot;}&quot;}}"></div>"""
        assertEquals("https://cdn.example/video.m3u8?x=1&y=2", WebReplayParser.playable(html, site.url))
    }
    @Test fun readsDailymotionQualityMetadata() {
        val text = """{"qualities":{"auto":[{"type":"application/x-mpegURL","url":"https://cdn.example/master.m3u8"}],"720":[{"type":"video/mp4","url":"https://cdn.example/video.mp4"}]}}"""
        assertEquals("https://cdn.example/master.m3u8", WebReplayParser.playable(text, site.url))
    }
    @Test fun readsOkMetadataWhenFlashvarsContainsAnObject() {
        val html = """<div data-options="{&quot;flashvars&quot;:{&quot;metadata&quot;:{&quot;hlsManifestUrl&quot;:&quot;https://cdn.example/video.m3u8&quot;}}}"></div>"""
        assertEquals("https://cdn.example/video.m3u8", WebReplayParser.playable(html, site.url))
    }
    @Test fun acceptsSchemaOrgCatalogueAndDirectVideoPages() {
        val html = """<script type="application/ld+json">{"@type":"ItemList","itemListElement":[{"item":{"@type":"VideoObject","name":"F1 Race Replay","url":"/race","thumbnailUrl":"/race.jpg","datePublished":"2026-10-24"}}]}</script>"""
        val event = WebReplayParser.catalogue(html, site.url, site).events.single()
        assertEquals("Motorsport", event.sport); assertEquals("2026-10-24", event.date)
        assertEquals("https://sports.example/race.jpg", event.artwork)
    }
    @Test fun selectsMediaPropertiesAndIgnoresImagesAndAds() {
        assertEquals("https://cdn.example/a.m3u8?x=1&y=2", WebReplayParser.playable("""<script>image:"https://ads.example/art.jpg"; player={file:"https:\/\/cdn.example/a.m3u8?x=1\u0026y=2"};</script>""", site.url))
        assertNull(WebReplayParser.playable("""<img src="https://cdn.example/a.jpg"><script>ad="https://ads.example/a.mp4"</script>""", site.url))
    }
    @Test fun deduplicatesCardsAndBoundsParserWork() {
        val card = """<article><h2><a href="/same">Basketball replay</a></h2><img src="/p.jpg"></article>"""
        assertEquals(1, WebReplayParser.catalogue(card.repeat(10), site.url, site).events.size)
        assertFailsWith<IllegalArgumentException> { WebReplayParser.catalogue("x".repeat(2_000_001), site.url, site) }
    }
    @Test fun headersAreExactUrlScopedOneShotAndExpire() {
        var time = 1L; val handoff = ReplayHeaderHandoff { time }
        handoff.stage("a", ResolvedReplay("https://cdn.example/v.m3u8", mapOf("Referer" to site.url)))
        assertNull(handoff.consume("a", "https://cdn.example/v.m3u8/another"))
        assertNotNull(handoff.peek("a", "https://cdn.example/v.m3u8"))
        assertNotNull(handoff.consume("a", "https://cdn.example/v.m3u8")); assertNull(handoff.consume("a", "https://cdn.example/v.m3u8"))
        handoff.stage("a", ResolvedReplay("https://cdn.example/v.m3u8")); time += 60_001
        assertNull(handoff.peek("a", "https://cdn.example/v.m3u8"))
    }
    @Test fun changingProfileClearsPendingPlaybackHeaders() {
        val handoff = ReplayHeaderHandoff()
        handoff.stage("first", ResolvedReplay("https://cdn.example/v.m3u8", mapOf("Referer" to site.url)))
        assertNull(handoff.peek("second", "https://cdn.example/v.m3u8")); assertNull(handoff.peek("first", "https://cdn.example/v.m3u8"))
    }
    @Test fun protectedMetadataIsNotOfferedAsAClearStream() {
        assertNull(WebReplayParser.playable("""{"drm":{"licenseUrl":"https://license.example/"},"contentUrl":"https://cdn.example/encrypted.m3u8"}""", site.url))
    }
    @Test fun repeatedHostUrlsHaveDistinctGameIdentities() {
        val html = """<h2>Red vs Blue</h2><a class="su-button" href="/watch">Watch</a><h2>Green vs Gold</h2><a class="su-button" href="/watch">Watch</a>"""
        val sources = WebReplayParser.sources(html, site.url)
        assertEquals(2, sources.size)
        assertEquals(2, sources.map { it.id }.distinct().size)
        assertEquals(listOf("Red vs Blue", "Green vs Gold"), sources.map { it.gameTitle })
    }
}

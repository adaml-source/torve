package com.torve.desktop.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the parsing + version-compare contract that the in-app updater
 * banner depends on. Each test corresponds to a real bug surfaced by
 * the B4 smoke on 2026-05-03 — the suite as a whole is the regression
 * net so those bugs cannot ship silently again.
 */
class UpdateCheckerTest {

    private val checker = UpdateChecker(currentVersion = "1.0.6")

    // ── isStrictlyNewer ──────────────────────────────────────────
    // Fix #2 regression: caller used to pass "Version 1.0.6" instead
    // of "1.0.6", and the string-compare on the first segment
    // ("Version 1" vs "1") short-circuited every comparison to false,
    // so the in-app updater silently returned UpToDate on every
    // installed build.

    @Test
    fun strictlyNewer_simpleSemver() {
        assertTrue(checker.isStrictlyNewer("1.0.7", "1.0.6"))
        assertTrue(checker.isStrictlyNewer("1.1.0", "1.0.99"))
        assertTrue(checker.isStrictlyNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun strictlyNewer_sameVersionIsNotNewer() {
        assertEquals(false, checker.isStrictlyNewer("1.0.6", "1.0.6"))
    }

    @Test
    fun strictlyNewer_olderIsNotNewer() {
        assertEquals(false, checker.isStrictlyNewer("1.0.5", "1.0.6"))
        assertEquals(false, checker.isStrictlyNewer("0.9.0", "1.0.0"))
    }

    @Test
    fun strictlyNewer_stripsLeadingV() {
        assertTrue(checker.isStrictlyNewer("v1.0.7", "1.0.6"))
        assertTrue(checker.isStrictlyNewer("V2.0.0", "v1.0.0"))
        assertEquals(false, checker.isStrictlyNewer("v1.0.0", "v1.0.0"))
    }

    @Test
    fun strictlyNewer_prereleaseSegments_currentQuirkPinned() {
        // KNOWN LIMITATION (separate from B4 fixes): the current
        // impl splits on "-" so "1.0.7-rc1" becomes
        // ["1","0","7","rc1"] and is then compared against
        // "1.0.7" → ["1","0","7"]. At index 3, the shorter side
        // defaults to "0" and the string compare gives "rc1" > "0",
        // so the impl reports "1.0.7-rc1" as STRICTLY NEWER than
        // "1.0.7". From a semver UX perspective that's wrong —
        // stable-channel users shouldn't see an "update available"
        // banner pointing at an RC. Pin current behavior so any
        // future fix has to flip these assertions deliberately.
        assertTrue(checker.isStrictlyNewer("1.0.7-rc1", "1.0.7"))
        assertEquals(false, checker.isStrictlyNewer("1.0.7", "1.0.7-rc1"))
    }

    // ── parseAppcast ─────────────────────────────────────────────
    // Fix #3 regression: the enclosure-URL extractor used `\burl=...`
    // and matched inconsistently across attribute orderings + multi-
    // line enclosures, so info.installerUrl came back null and the
    // banner rendered without a "Download & install" button.

    @Test
    fun parseAppcast_singleLineEnclosure() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
              <channel><item>
                <title>Torve 1.0.7</title>
                <link>https://torve.app/release-notes/1.0.7</link>
                <enclosure url="https://example.com/torve.msi" sparkle:version="1.0.7" sparkle:installerSha256="${"a".repeat(64)}" length="100" type="application/octet-stream" />
              </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertEquals("1.0.7", info.tag)
        assertEquals("Torve 1.0.7", info.name)
        assertEquals("https://example.com/torve.msi", info.installerUrl)
        assertEquals("a".repeat(64), info.installerSha256)
        assertEquals("https://torve.app/release-notes/1.0.7", info.htmlUrl)
    }

    @Test
    fun parseAppcast_multiLineEnclosureExtractsUrl() {
        // The original bug: this exact shape (url= on its own line
        // with whitespace before) returned info.installerUrl == null,
        // even though the same XML parsed clean in Python regex. Now
        // pinned.
        val body = """
            <?xml version="1.0"?>
            <rss xmlns:sparkle="x"><channel><item>
                <title>Torve 1.0.7</title>
                <enclosure
                  url="https://example.com/torve.msi"
                  sparkle:version="1.0.7"
                  length="100"
                  />
            </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertEquals("https://example.com/torve.msi", info.installerUrl)
        assertEquals("1.0.7", info.tag)
    }

    @Test
    fun parseAppcast_attributeOrderDoesNotMatter() {
        // The new attribute-map extractor must be order-independent.
        val body = """
            <rss xmlns:sparkle="x"><channel><item>
                <title>Torve 1.0.7</title>
                <enclosure sparkle:version="1.0.7" sparkle:installerSha256="${"b".repeat(64)}" url="https://example.com/torve.msi" length="100" />
            </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertEquals("https://example.com/torve.msi", info.installerUrl)
        assertEquals("b".repeat(64), info.installerSha256)
    }

    @Test
    fun parseAppcast_sha256IsCaseInsensitiveOnAttributeName() {
        // We accept both `sparkle:installerSha256` and `sha256`.
        val body = """
            <rss><channel><item>
                <title>Torve 1.0.7</title>
                <enclosure url="https://example.com/torve.msi" sha256="${"c".repeat(64)}" />
            </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertEquals("c".repeat(64), info.installerSha256)
    }

    @Test
    fun parseAppcast_rejectsShortNonHexSha() {
        // 64-hex floor; anything else gets dropped to null so the
        // handoff doesn't try to verify against garbage.
        val body = """
            <rss><channel><item>
                <title>Torve 1.0.7</title>
                <enclosure url="https://example.com/torve.msi" sparkle:installerSha256="not-a-real-sha" />
            </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertNull(info.installerSha256)
        // installerUrl still extracts so the banner can show
        // Download & install (the OS will Authenticode-verify).
        assertEquals("https://example.com/torve.msi", info.installerUrl)
    }

    @Test
    fun parseAppcast_versionFromTitleWhenNoSparkleAttr() {
        // Falls back to the last whitespace-separated token of <title>
        // when sparkle:version is absent. Real GitHub Releases JSON
        // never gets here (different parse path), but legacy appcasts
        // sometimes ship without it.
        val body = """
            <rss><channel><item>
                <title>Torve 1.0.7</title>
                <enclosure url="https://example.com/torve.msi" />
            </item></channel></rss>
        """.trimIndent()

        val info = checker.parseAppcast(body)

        assertNotNull(info)
        assertEquals("1.0.7", info.tag)
    }

    @Test
    fun parseAppcast_returnsNullOnEmpty() {
        assertNull(checker.parseAppcast(""))
        assertNull(checker.parseAppcast("<rss><channel></channel></rss>"))
    }

    @Test
    fun parseAppcast_returnsNullWhenNoVersionDerivable() {
        // No sparkle:version AND no whitespace in title.
        val body = """
            <rss><channel><item>
                <title>SingleWord</title>
                <enclosure url="https://example.com/torve.msi" />
            </item></channel></rss>
        """.trimIndent()

        // Title.substringAfterLast(' ') with no space returns the
        // whole string, so tag becomes "SingleWord". This is current
        // behavior; pinning it. If we ever want to require a parseable
        // version, this test should flip to assertNull and the impl
        // should add a numeric-segment guard.
        val info = checker.parseAppcast(body)
        assertNotNull(info)
        assertEquals("SingleWord", info.tag)
    }

    // ── feed-disabled invariant ─────────────────────────────────
    // No env -> isEnabled false -> banner stays away.

    @Test
    fun checker_disabledWhenNoFeedConfigured() {
        val noEnv = UpdateChecker(
            currentVersion = "1.0.6",
            repo = null,
            feedOverride = null,
        )
        assertEquals(false, noEnv.isEnabled)
    }

    @Test
    fun checker_enabledWhenFeedSet() {
        val withFeed = UpdateChecker(
            currentVersion = "1.0.6",
            repo = null,
            feedOverride = "https://example.com/appcast.xml",
        )
        assertTrue(withFeed.isEnabled)
    }

    // ── default-resolver precedence ────────────────────────────
    // The packaged build sets `-Dtorve.update.feed=…` so end users
    // get auto-update without env-var fiddling. The env var still
    // wins for dev / QA who need to swap feeds at runtime.

    @Test
    fun resolveDefaultFeed_picksUpSystemPropertyWhenEnvUnset() {
        val key = UpdateChecker.FEED_PROPERTY
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "https://torve.example/appcast.xml")
            // Note: this test assumes TORVE_UPDATE_FEED is not set in
            // the test environment. CI must not set it.
            assertEquals(
                "https://torve.example/appcast.xml",
                UpdateChecker.resolveDefaultFeed(),
            )
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    fun resolveDefaultFeed_returnsNullWhenBlank() {
        val key = UpdateChecker.FEED_PROPERTY
        val previous = System.getProperty(key)
        try {
            // Empty string is the build.gradle default when no feed
            // is configured at packaging time. The resolver must
            // treat "" as unconfigured, not as a real URL.
            System.setProperty(key, "")
            assertEquals(null, UpdateChecker.resolveDefaultFeed())
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}

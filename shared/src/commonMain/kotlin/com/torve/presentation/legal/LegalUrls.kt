package com.torve.presentation.legal

/**
 * Single source of truth for legal / support URLs surfaced from every
 * platform's Settings / About screens (Prompt 12 hardening).
 *
 * Every platform Settings page MUST link to all four targets — Privacy
 * Policy, Terms, Support, and Account Deletion — for store-policy
 * compliance (Google Play, Apple App Store, Amazon Appstore all require
 * a deletion path that's reachable in-app, not just on the website).
 *
 * URL changes go here, not in per-platform string resources, so a copy
 * change is one PR and not seven.
 */
object LegalUrls {
    /** Privacy policy. Public, in-browser. */
    const val PRIVACY_POLICY: String = "https://torve.app/privacy.html"

    /** Terms of service. Public, in-browser. */
    const val TERMS_OF_SERVICE: String = "https://torve.app/terms.html"

    /** General help / support landing. */
    const val HELP: String = "https://torve.app/app/help.html"

    /** Direct support email. Settings should offer a `mailto:` action. */
    const val SUPPORT_EMAIL: String = "support@torve.app"

    /** Privacy / GDPR contact. */
    const val PRIVACY_EMAIL: String = "privacy@torve.app"

    /**
     * Web mirror of the in-app account-deletion flow. Required by app
     * stores so users without the app installed can still request
     * deletion. The in-app DELETE action is the primary path.
     *
     * Filename matches the live page hosted at torve.app — the slug
     * convention there is `<noun>-<verb>.html` (`account-deletion`,
     * not `delete-account`). The previous `delete-account.html` value
     * was a 404 only because the constant didn't match the actual
     * published filename.
     */
    const val ACCOUNT_DELETION_WEB: String = "https://torve.app/account-deletion.html"
}

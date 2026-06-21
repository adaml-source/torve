"""
Content policy domain model and classification engine.

Provides:
- Enums for policy mode, content classification, age band, access context
- A text classifier for backend-served metadata (file names, titles, addon metadata)
- A policy decision engine that combines classification + user state + context
- Manual override support (allowlist/denylist by external ID)

Design:
- The backend does NOT proxy TMDB. This module classifies backend-served text
  (acceleration file names, inventory titles, addon metadata) and provides a
  policy contract the Google Play client enforces locally for TMDB content.
- BLOCKED_ILLEGAL never returns regardless of user state.
- SENSITIVE_CATALOG returns only for adult-unlocked users in permitted contexts.
- SAFE always returns.
- REVIEW_REQUIRED fails closed to SENSITIVE_CATALOG on Google Play.
"""
import logging
import re
from enum import Enum

_log = logging.getLogger(__name__)


# ── Enums ───────────────────────────────────────────────────────────────


class ContentPolicyMode(str, Enum):
    OPEN = "open"                              # Desktop, sideload, non-Play
    GOOGLE_PLAY_SAFE_DEFAULT = "google_play"   # Google Play flavor


class ContentClassification(str, Enum):
    SAFE = "safe"
    SENSITIVE_CATALOG = "sensitive_catalog"
    BLOCKED_ILLEGAL = "blocked_illegal"
    REVIEW_REQUIRED = "review_required"


class AgeBand(str, Enum):
    UNKNOWN = "unknown"
    UNDER_18 = "under_18"
    ADULT = "adult"


class AddonClassification(str, Enum):
    SAFE = "safe"
    SENSITIVE_CATALOG = "sensitive_catalog"
    BLOCKED_ILLEGAL = "blocked_illegal"
    REVIEW_REQUIRED = "review_required"


class AccessContext(str, Enum):
    DEFAULT_DISCOVERY = "default_discovery"
    DIRECT_SEARCH = "direct_search"
    DETAIL_REQUEST = "detail_request"
    HISTORY_DERIVED = "history_derived"
    USER_LIBRARY = "user_library"
    ACCELERATION_INVENTORY = "acceleration_inventory"


class PolicyDecision(str, Enum):
    ALLOW_FULL = "allow_full"
    ALLOW_REDACTED = "allow_redacted"
    HIDE = "hide"
    BLOCK = "block"


# Current policy version — bump when wording changes require re-confirmation
CURRENT_POLICY_VERSION = "2026-04-01-v1"

# Valid distribution channels — resolved per-request, not stored on account
VALID_CHANNELS = frozenset({"google_play", "open"})
DEFAULT_CHANNEL = "google_play"  # Fail safe: unknown channel = Google Play rules

# Request header used by clients to declare their distribution channel
CHANNEL_HEADER = "x-torve-channel"


# ── Text normalization ──────────────────────────────────────────────────


_SEPARATOR_RE = re.compile(r"[.\-_\[\](){},;:+/\\|~`]+")
_WHITESPACE_RE = re.compile(r"\s+")
_LEETSPEAK = str.maketrans({
    "0": "o", "1": "i", "3": "e", "4": "a", "5": "s",
    "7": "t", "@": "a", "$": "s",
})


def normalize_text(raw: str) -> str:
    """Normalize text for classification: lowercase, strip separators,
    collapse whitespace. Leetspeak is handled separately in matching."""
    if not raw:
        return ""
    t = raw.lower()
    t = _SEPARATOR_RE.sub(" ", t)
    t = _WHITESPACE_RE.sub(" ", t).strip()
    return t


def _normalize_with_leetspeak(text: str) -> str:
    """Apply leetspeak reversal for keyword matching only."""
    return text.translate(_LEETSPEAK)


# ── Keyword sets ────────────────────────────────────────────────────────
# Organized by severity. Each set is checked against normalized text.
# Multilingual: English + major European languages relevant to Torve metadata.

# BLOCKED_ILLEGAL: porn, illegal sexual content, exploitative markers
_BLOCKED_KEYWORDS = frozenset({
    # English
    "porn", "porno", "pornography", "pornographic", "xxx", "xxxx",
    "hardcore sex", "gangbang", "gang bang", "creampie", "bukake", "bukkake",
    "deepthroat", "deep throat", "blowjob", "blow job", "handjob", "hand job",
    "cumshot", "cum shot", "facial cumshot", "anal sex", "double penetration",
    "threesome", "foursome", "orgy", "orgies",
    "milf", "gilf", "dilf", "stepmom porn", "stepsis",
    "hentai", "futanari", "tentacle",
    "child porn", "child sexual", "cp ", "csam",
    "lolicon", "shotacon", "underage",
    "bestiality", "zoophilia",
    "incest", "rape porn", "snuff",
    "onlyfans leak", "leaked nudes", "revenge porn",
    # German
    "pornos", "pornografie", "pornofilm",
    # French
    "pornographie", "porno francais", "film porno",
    # Spanish
    "pornografia", "pelicula porno",
    # Italian
    "pornografico", "pornografia",
    # Portuguese
    "pornografico",
    # Turkish
    "pornosu", "porno izle", "porno film",
})

# BLOCKED addon URL patterns (obviously adult-only addon sources)
_BLOCKED_ADDON_PATTERNS = frozenset({
    "porn", "xxx", "adult", "nsfw", "hentai",
    "xhamster", "pornhub", "xvideos", "xnxx", "redtube",
    "youporn", "brazzers", "bangbros",
})

# SENSITIVE_CATALOG: legal adult content, erotic themes, nudity-oriented
_SENSITIVE_KEYWORDS = frozenset({
    # English
    "erotic", "erotica", "erotic film", "erotic thriller",
    "softcore", "soft core",
    "nude", "nudes", "nudity", "full frontal",
    "striptease", "strip tease", "stripper",
    "playboy", "penthouse", "hustler",
    "adult film", "adult movie", "adult content", "adults only",
    "sexual content", "sexually explicit",
    "sex scene", "sex scenes",
    "bondage", "bdsm", "fetish",
    "escort", "call girl", "gigolo",
    "cam girl", "camgirl", "webcam model",
    "lap dance", "lapdance", "pole dance",
    "sexual fantasy", "sexual desire",
    # German
    "erotik", "erotisch", "erotikfilm", "nackt", "nacktheit",
    "freizuegig",
    # French
    "erotique", "film erotique", "nudite",
    # Spanish
    "erotico", "erotica", "desnudo", "desnudez", "pelicula erotica",
    # Italian
    "erotico", "erotica", "nudo", "nudita",
    # Portuguese
    "erotico", "nudez",
    # Turkish
    "erotik", "erotik film",
})

# Documentary/artistic override terms — suppress SENSITIVE if these co-occur
# with nudity terms but no BLOCKED signals
_DOCUMENTARY_TERMS = frozenset({
    "documentary", "dokumentar", "documentaire", "documental", "documentario",
    "belgesel",  # Turkish
    "art", "artistic", "kunst", "artistique", "artistico",
    "museum", "gallery", "exhibition",
    "anatomy", "medical", "scientific", "biology",
    "education", "educational",
    "history", "historical", "renaissance", "classical",
    "photography", "photographer",
    "body positive", "body positivity",
    "naturism", "naturist", "fkk",  # European naturism
})


# ── Classifier ──────────────────────────────────────────────────────────


def classify_text(
    raw: str,
    *,
    allowlist_ids: frozenset[str] | None = None,
    denylist_ids: frozenset[str] | None = None,
    external_id: str | None = None,
) -> ContentClassification:
    """Classify a text string (file name, title, addon name, etc.)

    Returns the highest-severity classification found.
    Manual overrides (allow/denylist) take precedence.
    """
    # Manual override: denylist forces BLOCKED
    if external_id and denylist_ids and external_id in denylist_ids:
        return ContentClassification.BLOCKED_ILLEGAL

    # Manual override: allowlist forces SAFE
    if external_id and allowlist_ids and external_id in allowlist_ids:
        return ContentClassification.SAFE

    if not raw:
        return ContentClassification.SAFE

    normalized = normalize_text(raw)
    if not normalized:
        return ContentClassification.SAFE

    # Also check leetspeak variant for evasion detection
    leet = _normalize_with_leetspeak(normalized)

    # Check BLOCKED first (highest severity) — check both variants
    if _matches_any(normalized, _BLOCKED_KEYWORDS) or _matches_any(leet, _BLOCKED_KEYWORDS):
        return ContentClassification.BLOCKED_ILLEGAL

    # Check SENSITIVE — check both variants
    if _matches_any(normalized, _SENSITIVE_KEYWORDS) or _matches_any(leet, _SENSITIVE_KEYWORDS):
        # Documentary/artistic override: if documentary terms present
        # AND no blocked signals, downgrade to SAFE
        if _matches_any(normalized, _DOCUMENTARY_TERMS) or _matches_any(leet, _DOCUMENTARY_TERMS):
            return ContentClassification.SAFE
        return ContentClassification.SENSITIVE_CATALOG

    return ContentClassification.SAFE


def classify_addon(
    *,
    manifest_url: str | None = None,
    name: str | None = None,
    description: str | None = None,
    addon_id: str | None = None,
    allowlist_urls: frozenset[str] | None = None,
    denylist_urls: frozenset[str] | None = None,
) -> AddonClassification:
    """Classify an addon by its manifest URL and metadata.

    URL-based patterns are checked first (high confidence for known
    adult-only addon sources), then metadata text is classified.
    """
    # Manual URL overrides
    if manifest_url:
        if denylist_urls and manifest_url in denylist_urls:
            return AddonClassification.BLOCKED_ILLEGAL
        if allowlist_urls and manifest_url in allowlist_urls:
            return AddonClassification.SAFE

    # URL pattern check
    if manifest_url:
        url_lower = manifest_url.lower()
        for pattern in _BLOCKED_ADDON_PATTERNS:
            if pattern in url_lower:
                return AddonClassification.BLOCKED_ILLEGAL

    # Classify metadata text
    texts = [t for t in (name, description, addon_id) if t]
    highest = ContentClassification.SAFE
    for text in texts:
        cls = classify_text(text)
        if cls == ContentClassification.BLOCKED_ILLEGAL:
            return AddonClassification.BLOCKED_ILLEGAL
        if cls == ContentClassification.SENSITIVE_CATALOG:
            highest = ContentClassification.SENSITIVE_CATALOG

    if highest == ContentClassification.SENSITIVE_CATALOG:
        return AddonClassification.SENSITIVE_CATALOG

    return AddonClassification.SAFE


# ── Policy decision engine ──────────────────────────────────────────────


def decide(
    *,
    policy_mode: ContentPolicyMode,
    classification: ContentClassification | AddonClassification | None,
    age_band: AgeBand,
    sensitive_enabled: bool,
    context: AccessContext,
) -> PolicyDecision:
    """Resolve a policy decision for a classified item.

    This is the central decision point. All backend filtering and all
    client-facing policy signals should route through this function.

    Null/unknown classification fails closed to HIDE (treated as
    REVIEW_REQUIRED). REVIEW_REQUIRED is treated as SENSITIVE_CATALOG
    in ALL modes, not just Google Play — it requires a manual override
    to become SAFE.
    """
    # BLOCKED_ILLEGAL: never return, any mode, any user
    if classification in (ContentClassification.BLOCKED_ILLEGAL, AddonClassification.BLOCKED_ILLEGAL):
        return PolicyDecision.BLOCK

    # Null/unknown classification: fail closed (treat as REVIEW_REQUIRED)
    if classification is None:
        classification = ContentClassification.REVIEW_REQUIRED

    # SAFE: always allow in any mode
    if classification in (ContentClassification.SAFE, AddonClassification.SAFE):
        return PolicyDecision.ALLOW_FULL

    # REVIEW_REQUIRED: fail closed in ALL modes (not just Google Play).
    # Only a manual allowlist override can promote it to SAFE.
    # Treat identically to SENSITIVE_CATALOG below.

    # OPEN mode: SENSITIVE_CATALOG and REVIEW_REQUIRED still require
    # adult + enabled for intentional access, but are less restrictive
    # on context (no discovery restriction).
    if policy_mode == ContentPolicyMode.OPEN:
        if classification in (ContentClassification.REVIEW_REQUIRED, AddonClassification.REVIEW_REQUIRED):
            # REVIEW_REQUIRED fails closed even in open mode
            return PolicyDecision.HIDE
        # SENSITIVE_CATALOG in open mode: allow if adult+enabled
        if age_band == AgeBand.ADULT and sensitive_enabled:
            return PolicyDecision.ALLOW_FULL
        return PolicyDecision.HIDE

    # From here: Google Play mode
    # REVIEW_REQUIRED fails closed on Google Play — only a manual allowlist
    # override can promote it to SAFE. Even adult+enabled cannot override.
    if classification in (ContentClassification.REVIEW_REQUIRED, AddonClassification.REVIEW_REQUIRED):
        return PolicyDecision.HIDE

    # Non-adult or unknown age: never show sensitive content
    if age_band != AgeBand.ADULT:
        return PolicyDecision.HIDE

    # Adult but sensitive not enabled: hide
    if not sensitive_enabled:
        return PolicyDecision.HIDE

    # Adult with sensitive enabled: depends on context
    if context == AccessContext.DEFAULT_DISCOVERY:
        # Never in default promotional surfaces
        return PolicyDecision.HIDE
    elif context in (
        AccessContext.DIRECT_SEARCH,
        AccessContext.DETAIL_REQUEST,
        AccessContext.HISTORY_DERIVED,
        AccessContext.USER_LIBRARY,
        AccessContext.ACCELERATION_INVENTORY,
    ):
        return PolicyDecision.ALLOW_FULL

    # Fallback: hide (fail closed)
    return PolicyDecision.HIDE


# ── Helpers ─────────────────────────────────────────────────────────────


def _matches_any(normalized: str, keywords: frozenset[str]) -> bool:
    """Check if normalized text contains any keyword as a word boundary match."""
    for kw in keywords:
        # Use word boundary matching for multi-word keywords
        # and simple containment for single-word keywords
        if " " in kw:
            if kw in normalized:
                return True
        else:
            # Match as whole word or at boundaries
            if re.search(r"\b" + re.escape(kw) + r"\b", normalized):
                return True
    return False

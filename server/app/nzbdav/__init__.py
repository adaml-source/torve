"""NzbDAV upstream streaming integration.

This package encapsulates the NzbDAV (https://github.com/nzbdav-dev/nzbdav)
adapter. Upstream semantics NEVER leak to Torve clients — public types are
Torve-native (UsenetCandidate, ResolvedStream, WarmJob).

Module layout:
    client.py          HTTP adapter to the upstream WebDAV server
    account_store.py   DB layer over NzbdavConfig
    warm_service.py    Speculative warm jobs + per-user concurrency cap
    resolve_service.py Normalized ready|warming|failed resolution
    release_cache.py   In-process caches (warm-success, dead-release, health)
    telemetry.py       Structured log event helpers
    handoff.py         HMAC-signed handoff tokens (no raw URL leaks)
    failures.py        Normalized upstream error codes
    state.py           Canonical + simplified state machines
"""

"""Backend-native premium provider stream resolution.

The client is intentionally not trusted with provider credentials or native
provider APIs. This module talks to supported providers/addons server-side,
persists the resolved playback URL in resolve memory, and returns only opaque
metadata to the router.
"""
from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass
from urllib.parse import quote, urlparse, urlunparse

import httpx
from sqlalchemy.orm import Session

from app.crypto import decrypt_secret
from app.models import UserAddon, UserIntegration
from app.stream_handoff import is_safe_public_stream_url

_log = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=4.0, read=8.0, write=4.0, pool=4.0)


@dataclass(frozen=True)
class BackendResolveInput:
    content_id: str
    provider_type: str
    source_url: str | None = None
    addon_id: uuid.UUID | None = None
    stream_type: str | None = None
    stream_id: str | None = None
    file_name: str | None = None
    infohash: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None


@dataclass(frozen=True)
class ProviderResolvedStream:
    provider_type: str
    upstream_url: str
    provenance_kind: str
    file_name: str | None = None
    infohash: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None


class ProviderResolveError(Exception):
    def __init__(self, code: str, message: str, *, status_code: int = 400) -> None:
        super().__init__(code)
        self.code = code
        self.message = message
        self.status_code = status_code


def resolve_backend_stream(
    db: Session,
    *,
    user_id: uuid.UUID,
    body: BackendResolveInput,
) -> ProviderResolvedStream:
    """Resolve a supported provider/addon source into a backend-only URL."""
    provider = canonical_provider_type(body.provider_type)
    if provider == "real_debrid":
        return _resolve_real_debrid(db, user_id=user_id, body=body)
    if provider in {"panda", "addon"}:
        return _resolve_addon_stream(db, user_id=user_id, body=body, provider_type=provider)
    raise ProviderResolveError(
        "provider_unsupported",
        "This provider is not supported by backend resolution yet.",
        status_code=422,
    )


def canonical_provider_type(provider_type: str) -> str:
    value = (provider_type or "").strip().lower().replace("-", "_")
    aliases = {
        "rd": "real_debrid",
        "realdebrid": "real_debrid",
        "real_debrid": "real_debrid",
        "panda": "panda",
        "stremio_addon": "addon",
        "addon": "addon",
    }
    return aliases.get(value, value)


def _resolve_real_debrid(
    db: Session,
    *,
    user_id: uuid.UUID,
    body: BackendResolveInput,
) -> ProviderResolvedStream:
    source_url = _require_public_source_url(body.source_url)
    creds = _load_account_credentials(db, user_id=user_id, integration_type="real_debrid")
    api_key = _first_secret(creds, "api_key", "token", "access_token", "value")
    if not api_key:
        raise ProviderResolveError(
            "provider_credentials_missing",
            "Provider credentials are missing.",
            status_code=409,
        )

    try:
        with httpx.Client(
            timeout=_HTTP_TIMEOUT,
            follow_redirects=False,
            trust_env=False,
        ) as client:
            resp = client.post(
                "https://api.real-debrid.com/rest/1.0/unrestrict/link",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Accept": "application/json",
                },
                data={"link": source_url},
            )
    except httpx.HTTPError:
        _log.warning("PROVIDER_RESOLVE_UPSTREAM_ERROR provider=real_debrid user=%s", user_id)
        raise ProviderResolveError(
            "provider_unreachable",
            "Provider is temporarily unavailable.",
            status_code=502,
        )

    if resp.status_code in {401, 403}:
        _log.info("PROVIDER_CREDENTIALS_REJECTED provider=real_debrid user=%s status=%d",
                  user_id, resp.status_code)
        raise ProviderResolveError(
            "provider_credentials_invalid",
            "Provider credentials were rejected.",
            status_code=409,
        )
    if resp.status_code >= 400:
        _log.info("PROVIDER_RESOLVE_FAILED provider=real_debrid user=%s status=%d",
                  user_id, resp.status_code)
        raise ProviderResolveError(
            "provider_resolution_failed",
            "Provider could not resolve this stream.",
            status_code=502,
        )

    data = _json_response(resp)
    download_url = _first_string(data, "download", "link")
    if not download_url or not is_safe_public_stream_url(download_url):
        _log.info("PROVIDER_NO_PUBLIC_STREAM provider=real_debrid user=%s", user_id)
        raise ProviderResolveError(
            "provider_no_stream",
            "Provider did not return a playable stream.",
            status_code=404,
        )

    return ProviderResolvedStream(
        provider_type="real_debrid",
        upstream_url=download_url,
        provenance_kind="DEBRID_PROVIDER",
        file_name=body.file_name or _first_string(data, "filename", "name"),
        infohash=_clean_infohash(body.infohash),
        quality=body.quality,
        audio_flags=body.audio_flags,
        file_size=_first_int(data, "filesize", "size") or body.file_size,
    )


def _resolve_addon_stream(
    db: Session,
    *,
    user_id: uuid.UUID,
    body: BackendResolveInput,
    provider_type: str,
) -> ProviderResolvedStream:
    if not body.stream_type or not body.stream_id:
        raise ProviderResolveError(
            "stream_reference_required",
            "Addon stream type and id are required.",
            status_code=422,
        )

    addon = _select_addon(db, user_id=user_id, provider_type=provider_type, addon_id=body.addon_id)
    stream_url = _build_stremio_stream_url(
        addon.manifest_url,
        stream_type=body.stream_type,
        stream_id=body.stream_id,
    )
    if not is_safe_public_stream_url(stream_url):
        raise ProviderResolveError(
            "stream_reference_invalid",
            "Addon stream endpoint is not allowed.",
            status_code=422,
        )

    try:
        with httpx.Client(
            timeout=_HTTP_TIMEOUT,
            follow_redirects=False,
            trust_env=False,
        ) as client:
            resp = client.get(stream_url, headers={"Accept": "application/json"})
    except httpx.HTTPError:
        _log.warning("PROVIDER_RESOLVE_UPSTREAM_ERROR provider=%s user=%s", provider_type, user_id)
        raise ProviderResolveError(
            "provider_unreachable",
            "Provider is temporarily unavailable.",
            status_code=502,
        )

    if resp.status_code >= 400:
        _log.info("PROVIDER_RESOLVE_FAILED provider=%s user=%s status=%d",
                  provider_type, user_id, resp.status_code)
        raise ProviderResolveError(
            "provider_resolution_failed",
            "Provider could not resolve this stream.",
            status_code=502,
        )

    payload = _json_response(resp)
    stream = _first_public_addon_stream(payload.get("streams") if isinstance(payload, dict) else None)
    if stream is None:
        _log.info("PROVIDER_NO_PUBLIC_STREAM provider=%s user=%s", provider_type, user_id)
        raise ProviderResolveError(
            "provider_no_stream",
            "Provider did not return a playable stream.",
            status_code=404,
        )

    stream_url = stream["url"]
    return ProviderResolvedStream(
        provider_type=provider_type,
        upstream_url=stream_url,
        provenance_kind="ADDON_PROVIDER",
        file_name=body.file_name or _first_string(stream, "name", "title"),
        infohash=_clean_infohash(body.infohash),
        quality=body.quality,
        audio_flags=body.audio_flags,
        file_size=body.file_size,
    )


def _load_account_credentials(
    db: Session,
    *,
    user_id: uuid.UUID,
    integration_type: str,
) -> dict:
    row = (
        db.query(UserIntegration)
        .filter(
            UserIntegration.user_id == user_id,
            UserIntegration.integration_type == integration_type,
            UserIntegration.storage_mode == "account",
        )
        .first()
    )
    if row is None or not row.encrypted_credentials:
        raise ProviderResolveError(
            "provider_credentials_missing",
            "Provider credentials are missing.",
            status_code=409,
        )
    try:
        decoded = json.loads(decrypt_secret(row.encrypted_credentials))
    except (ValueError, json.JSONDecodeError):
        _log.warning("PROVIDER_CREDENTIALS_UNREADABLE provider=%s user=%s", integration_type, user_id)
        raise ProviderResolveError(
            "provider_credentials_invalid",
            "Provider credentials could not be used.",
            status_code=409,
        )
    return decoded if isinstance(decoded, dict) else {}


def _select_addon(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    addon_id: uuid.UUID | None,
) -> UserAddon:
    q = db.query(UserAddon).filter(
        UserAddon.user_id == user_id,
        UserAddon.is_enabled == True,  # noqa: E712
    )
    if addon_id is not None:
        addon = q.filter(UserAddon.id == addon_id).first()
    elif provider_type == "panda":
        addon = q.filter(
            (UserAddon.addon_id == "com.torve.panda")
            | (UserAddon.manifest_url.ilike("%panda%"))
        ).order_by(UserAddon.sort_order, UserAddon.created_at).first()
    else:
        addon = None

    if addon is None:
        raise ProviderResolveError(
            "provider_not_configured",
            "Provider is not configured for this account.",
            status_code=404,
        )
    if not addon.has_streams:
        raise ProviderResolveError(
            "provider_no_stream",
            "Provider does not expose streams.",
            status_code=404,
        )
    return addon


def _build_stremio_stream_url(manifest_url: str, *, stream_type: str, stream_id: str) -> str:
    parsed = urlparse(manifest_url)
    path = parsed.path or ""
    if path.endswith("/manifest.json"):
        base_path = path[: -len("/manifest.json")]
    elif path.endswith("manifest.json"):
        base_path = path[: -len("manifest.json")].rstrip("/")
    else:
        base_path = path.rstrip("/")
    stream_path = (
        f"{base_path}/stream/{quote(stream_type.strip(), safe='')}/"
        f"{quote(stream_id.strip(), safe='')}.json"
    )
    return urlunparse((parsed.scheme, parsed.netloc, stream_path, "", parsed.query, ""))


def _first_public_addon_stream(streams: object) -> dict | None:
    if not isinstance(streams, list):
        return None
    for item in streams:
        if not isinstance(item, dict):
            continue
        candidate = _first_string(item, "url", "externalUrl")
        if candidate and is_safe_public_stream_url(candidate):
            return {**item, "url": candidate}
    return None


def _require_public_source_url(value: str | None) -> str:
    if not value or not is_safe_public_stream_url(value):
        raise ProviderResolveError(
            "stream_reference_invalid",
            "Source reference is not allowed.",
            status_code=422,
        )
    return value


def _json_response(resp: httpx.Response) -> dict:
    try:
        data = resp.json()
    except ValueError:
        raise ProviderResolveError(
            "provider_resolution_failed",
            "Provider returned an unreadable response.",
            status_code=502,
        )
    if not isinstance(data, dict):
        raise ProviderResolveError(
            "provider_resolution_failed",
            "Provider returned an unreadable response.",
            status_code=502,
        )
    return data


def _first_secret(values: dict, *keys: str) -> str | None:
    for key in keys:
        value = values.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _first_string(values: dict, *keys: str) -> str | None:
    for key in keys:
        value = values.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _first_int(values: dict, *keys: str) -> int | None:
    for key in keys:
        value = values.get(key)
        if isinstance(value, int):
            return value
        if isinstance(value, str) and value.isdigit():
            return int(value)
    return None


def _clean_infohash(value: str | None) -> str | None:
    if not value:
        return None
    cleaned = value.strip().lower()
    if len(cleaned) == 40 and all(c in "0123456789abcdef" for c in cleaned):
        return cleaned
    return None

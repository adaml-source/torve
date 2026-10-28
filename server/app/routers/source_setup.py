"""Profile-scoped website editors for the app's existing source configuration.

The browser never receives provider secrets or a Torve/Panda bearer token.
Only the section being edited is patched; unrelated sources remain intact.
"""
import hashlib
import ipaddress
import json
import re
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit, urlunsplit

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.deps import get_current_user_id, get_db, require_premium
from app.family_policy import canonical_connection, locked_policy, require_parent_when_enabled
from app.models import AccountSettings, UserIntegration
from app.routers.profiles import _records
from app.security import create_access_token

router = APIRouter(prefix="/me/source-setup", tags=["source-setup"])
UPSTREAM = "https://panda.torve.app/api/v1"
REPLAY_KEY = "web_replays_v1_settings"
SECRET_FIELDS = ("usenetPassword", "downloadClientApiKey", "downloadClientPassword")
USENET_FIELDS = frozenset(("enableUsenet", "usenetProvider", "usenetHost", "usenetPort",
    "usenetUsername", "usenetPassword", "usenetSSL", "usenetConnections", "downloadClient",
    "downloadClientUrl", "downloadClientUsername", "downloadClientPassword", "downloadClientApiKey",
    "easynewsPreferNzb"))
INDEXERS = ("nzbgeek", "scenenzbs", "dognzb", "nzbplanet", "custom")
CLIENTS = ("none", "nzbget", "sabnzbd", "premiumize", "torbox", "alldebrid")


class Edit(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expectedRevision: str = Field(max_length=64)
    values: dict = Field(max_length=24)


def revision(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def source_revision(record):
    # Upstream GET includes auth metadata that PATCH responses omit.
    # Compare only configuration state so a successful save can be edited again.
    return revision({key: record.get(key) for key in ("config_id", "config", "updated_at")} if record else None)


def public_url(value):
    if not isinstance(value, str) or len(value) > 2000 or any(c.isspace() for c in value) or "\\" in value:
        raise HTTPException(422, "Enter a public HTTP or HTTPS website address")
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower().rstrip(".")
        if parsed.scheme not in ("http", "https") or not host or parsed.username is not None or parsed.password is not None:
            raise ValueError()
        if "." not in host or host.endswith((".local", ".localhost", ".internal")) or "%" in host:
            raise ValueError()
        try:
            if not ipaddress.ip_address(host).is_global: raise ValueError()
        except ValueError:
            if re.fullmatch(r"[0-9a-fAxX.]+", host) or ":" in host: raise ValueError()
        port = parsed.port
        authority = host + (":" + str(port) if port and port != (443 if parsed.scheme == "https" else 80) else "")
        return urlunsplit((parsed.scheme, authority, parsed.path or "/", parsed.query, ""))
    except ValueError:
        raise HTTPException(422, "Enter a public HTTP or HTTPS website address")


def context(request, response, user_id, db, write=False):
    response.headers["Cache-Control"] = "private, no-store"
    if write: locked_policy(db, uuid.UUID(user_id))
    require_parent_when_enabled(db, user_id, request)
    profile = request.headers.get("X-Torve-Profile-Id", "default")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", profile): raise HTTPException(422, "Invalid profile")
    settings = db.query(AccountSettings).filter_by(user_id=uuid.UUID(user_id)).first()
    if profile not in _records(settings.settings if settings else {}): raise HTTPException(404, "Profile is no longer available")
    return profile, settings


def reference(db, user_id, profile):
    suffix = "" if profile == "default" else "::p:" + profile
    # Match both current and older app integration names, without crossing profiles.
    rows = db.query(UserIntegration).filter(UserIntegration.user_id == uuid.UUID(user_id),
        UserIntegration.integration_type.in_(["PANDA_TOKEN" + suffix, "panda" + suffix,
            "panda_token" + suffix, "PANDA_TOKEN::profile:" + profile, "panda::profile:" + profile])).all()
    rows.sort(key=lambda r: r.integration_type != "PANDA_TOKEN" + suffix)
    if not rows: return None, {}
    row = canonical_connection(db, uuid.UUID(user_id), rows[0])
    if row.storage_mode != "account": raise HTTPException(409, "Save this connection with account sync in the app first")
    try: credentials = json.loads(decrypt_secret(row.encrypted_credentials))
    except (ValueError, TypeError): raise HTTPException(409, "Saved source credentials could not be loaded. Reconnect in the app")
    if not credentials.get("config_id") and not credentials.get("token"):
        raise HTTPException(409, "Saved source reference is incomplete. Reconnect in the app")
    return row, credentials


async def upstream(method, path, user_id, config_id=None, body=None, bearer=None):
    headers = {"Authorization": "Bearer " + (bearer or create_access_token(user_id))}
    if config_id: headers["X-Panda-Config-Id"] = config_id
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=False) as client:
            result = await client.request(method, UPSTREAM + path, headers=headers, json=body)
        if result.status_code >= 400:
            # Never reflect upstream bodies containing URLs, tokens or credentials.
            raise HTTPException(502, "The source service could not load or save this configuration. Try again or reconnect in the app")
        return result.json()
    except (httpx.HTTPError, ValueError):
        raise HTTPException(502, "The source service is unavailable. Your saved settings have been kept. Try again")


async def read_source(db, user_id, profile):
    row, credentials = reference(db, user_id, profile)
    if row is None: return None, {}, None
    config_id = credentials.get("config_id")
    record = await upstream("GET", "/configs/me", user_id, config_id,
        bearer=None if config_id else credentials.get("token"))
    if not record.get("config_id") or not isinstance(record.get("config"), dict):
        raise HTTPException(502, "Saved source settings could not be read. Try again")
    return record, credentials, row


def source_view(record):
    config = record["config"] if record else {}
    values = {key: config[key] for key in USENET_FIELDS - set(SECRET_FIELDS) if key in config}
    for key in SECRET_FIELDS: values[key + "Saved"] = bool(config.get(key))
    rows = config.get("nzbIndexers") or []
    if not rows and config.get("nzbIndexer") not in (None, "none", ""):
        rows = [{"type": config["nzbIndexer"], "url": config.get("nzbIndexerUrl", ""), "apiKey": config.get("nzbIndexerApiKey", "")}]
    values["nzbIndexers"] = [{"type": r.get("type"), "url": r.get("url", ""), "keySaved": bool(r.get("apiKey"))} for r in rows]
    return {"configured": record is not None, "revision": source_revision(record), "values": values}


def validate_usenet(values, current):
    if not values or set(values) - USENET_FIELDS: raise HTTPException(422, "Unsupported Usenet field")
    patch = dict(values)
    for key, value in patch.items():
        if key in ("enableUsenet", "usenetSSL", "easynewsPreferNzb"):
            if type(value) is not bool: raise HTTPException(422, "Choose on or off")
        elif key in ("usenetPort", "usenetConnections"):
            if type(value) is not int or not 1 <= value <= (65535 if key == "usenetPort" else 100):
                raise HTTPException(422, "Enter a valid port or connection limit")
        elif not isinstance(value, str) or len(value) > 2000 or value == "[redacted]":
            raise HTTPException(422, "Invalid source value")
    for key in SECRET_FIELDS:
        if not patch.get(key): patch.pop(key, None)  # Empty means retain the saved secret.
    if (patch.get("usenetProvider", current.get("usenetProvider")) != current.get("usenetProvider")
            and patch.get("usenetProvider") not in ("none", None) and not patch.get("usenetPassword")):
        raise HTTPException(422, "Enter the password for the selected Usenet provider")
    if (patch.get("downloadClient", current.get("downloadClient")) != current.get("downloadClient")
            and patch.get("downloadClient") not in ("none", None)
            and not (patch.get("downloadClientApiKey") or patch.get("downloadClientPassword"))):
        raise HTTPException(422, "Enter credentials for the selected download client")
    merged = {**current, **patch}
    if merged.get("usenetProvider", "none") not in ("none", "easynews", "generic") or merged.get("downloadClient", "none") not in CLIENTS:
        raise HTTPException(422, "Choose a supported provider or download client")
    if merged.get("enableUsenet") and merged.get("usenetProvider") not in ("none", None):
        if not merged.get("usenetUsername") or not merged.get("usenetPassword"):
            raise HTTPException(422, "Enter the Usenet username and password")
        if merged.get("usenetProvider") == "generic" and not re.fullmatch(r"[A-Za-z0-9.-]+", merged.get("usenetHost", "")):
            raise HTTPException(422, "Enter the NNTP hostname without a protocol or path")
    client = merged.get("downloadClient", "none")
    if client in ("torbox", "premiumize", "alldebrid", "sabnzbd") and not merged.get("downloadClientApiKey"):
        raise HTTPException(422, "Enter the download client API key")
    if client in ("sabnzbd", "nzbget"): public_url(merged.get("downloadClientUrl", ""))
    if client == "nzbget" and (not merged.get("downloadClientUsername") or not merged.get("downloadClientPassword")):
        raise HTTPException(422, "Enter the NZBGet username and password")
    if merged.get("enableUsenet") and merged.get("usenetProvider", "none") == "none" and client == "none":
        raise HTTPException(422, "Choose a Usenet provider or NZB download client")
    return patch


def validate_indexers(values, current, secrets):
    rows = values.get("nzbIndexers")
    if set(values) != {"nzbIndexers"} or not isinstance(rows, list) or len(rows) > 10:
        raise HTTPException(422, "Use up to 10 NZB indexers")
    def identity(kind, url): return (kind, public_url(url) if kind == "custom" else "")
    existing = {identity(r.get("type"), r.get("url", "")): r.get("api_key", "") for r in secrets.get("nzb_indexers", [])}
    if not existing and secrets.get("nzb_indexer_api_key"):
        existing[identity(current.get("nzbIndexer"), current.get("nzbIndexerUrl", ""))] = secrets["nzb_indexer_api_key"]
    result, seen = [], set()
    for row in rows:
        if not isinstance(row, dict) or set(row) - {"type", "url", "apiKey"} or row.get("type") not in INDEXERS:
            raise HTTPException(422, "Choose a supported NZB indexer")
        kind = row["type"]
        url = public_url(row.get("url", "")) if kind == "custom" else ""
        row_identity = (kind, url)
        if row_identity in seen: raise HTTPException(422, "This NZB indexer is listed twice")
        seen.add(row_identity)
        key = row.get("apiKey") or existing.get(row_identity, "")
        if not isinstance(key, str) or not key or len(key) > 2000 or key == "[redacted]":
            raise HTTPException(422, "Enter an API key for each new or changed indexer")
        result.append({"type": kind, "url": url, "apiKey": key})
    first = result[0] if result else {"type": "none", "url": "", "apiKey": ""}
    return {"nzbIndexers": result, "nzbIndexer": first["type"], "nzbIndexerUrl": first["url"], "nzbIndexerApiKey": first["apiKey"]}


@router.get("/usenet")
async def get_usenet(request: Request, response: Response, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    profile, _ = context(request, response, user_id, db)
    record, _, row = await read_source(db, user_id, profile)
    return {**source_view(record), "shared": bool(row and (row.config or {}).get("torve_profile_id", profile) != profile)}


@router.patch("/{section}")
async def save_source(section: str, body: Edit, request: Request, response: Response,
                      user_id: str = Depends(require_premium), db: Session = Depends(get_db)):
    if section not in ("usenet", "indexers"): raise HTTPException(404, "Unknown source section")
    profile, _ = context(request, response, user_id, db, write=True)
    record, credentials, row = await read_source(db, user_id, profile)
    if body.expectedRevision != source_revision(record): raise HTTPException(409, "Changed on another device. Close this form and reload before saving")
    current = record["config"] if record else {}
    if section == "usenet": patch = validate_usenet(body.values, current)
    else:
        secrets = await upstream("GET", "/configs/me/secrets", user_id, record["config_id"]) if record else {}
        patch = validate_indexers(body.values, current, secrets)
    if record:
        updated = await upstream("PATCH", "/configs/me", user_id, record["config_id"], patch)
    else:
        # No torrent providers or retired addon installation as a side effect.
        created = await upstream("POST", "/configs", user_id, body={"version": 2, "enabledProviders": [],
            "debridService": "none", "enableUsenet": False, "hideCatalog": True, **patch})
        if not created.get("config_id") or not created.get("panda_token"):
            raise HTTPException(502, "The source service did not return a saved configuration")
        kind = "PANDA_TOKEN" + ("" if profile == "default" else "::p:" + profile)
        row = UserIntegration(user_id=uuid.UUID(user_id), integration_type=kind, storage_mode="account",
            display_identifier="Usenet", is_connected=True, config={"torve_profile_id": profile, "family_copy_profile": profile},
            encrypted_credentials=encrypt_secret(json.dumps({"config_id": created["config_id"], "token": created["panda_token"],
                "manifest_url": created.get("manifest_url", ""), "management_token": created.get("management_token", "")})))
        db.add(row)
        # Persist the recovery reference before the optional follow-up read.
        db.commit()
        updated = await upstream("GET", "/configs/me", user_id, created["config_id"])
    return source_view(updated)


def replay_value(settings, profile):
    # Current apps transport replay configuration as a household navigation
    # record, including a monotonic changedAt stamp for cross-device merge.
    key = f"household_v1:{profile}:navigation:{REPLAY_KEY}"
    legacy = REPLAY_KEY if profile == "default" else f"profilepref_v1:{profile}:{REPLAY_KEY}"
    store = (settings.settings or {}) if settings else {}
    raw = store.get(key) if key in store else store.get(legacy)
    try:
        if key in store:
            record = json.loads(raw)
            if (record["profile"] != profile or record["kind"] != "navigation" or record["id"] != REPLAY_KEY
                    or type(record["changedAt"]) is not int): raise ValueError()
            value = {"enabled": False, "websites": []} if record.get("deleted") else json.loads(record["data"]["value"])
        else: value = json.loads(raw) if raw else {"enabled": False, "websites": []}
    except (ValueError, TypeError, KeyError): raise HTTPException(409, "Saved replay settings could not be read. Repair them in the app before saving")
    if not isinstance(value, dict) or not isinstance(value.get("websites", []), list):
        raise HTTPException(409, "Saved replay settings could not be read")
    return key, raw, value


@router.get("/replays")
def get_replays(request: Request, response: Response, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    profile, settings = context(request, response, user_id, db)
    _, raw, value = replay_value(settings, profile)
    return {"revision": revision(raw), "values": value}


@router.put("/replays")
def save_replays(body: Edit, request: Request, response: Response,
                 user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    profile, settings = context(request, response, user_id, db, write=True)
    key, raw, current = replay_value(settings, profile)
    if body.expectedRevision != revision(raw): raise HTTPException(409, "Replay websites changed on another device. Close this form and reload")
    value = body.values
    if set(value) != {"enabled", "websites"} or type(value["enabled"]) is not bool or not isinstance(value["websites"], list) or len(value["websites"]) > 12:
        raise HTTPException(422, "Use up to 12 replay websites")
    websites, ids, urls = [], set(), set()
    for site in value["websites"]:
        if not isinstance(site, dict) or set(site) - {"id", "name", "url", "enabled"}: raise HTTPException(422, "Invalid replay website")
        name, url = site.get("name"), public_url(site.get("url"))
        identity = site.get("id") or uuid.uuid4().hex[:16]
        if not isinstance(name, str) or not name.strip() or len(name) > 80 or type(site.get("enabled")) is not bool:
            raise HTTPException(422, "Enter a name and enabled state for each replay website")
        if not isinstance(identity, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", identity): raise HTTPException(422, "Invalid website identity")
        if identity in ids or url in urls: raise HTTPException(422, "This replay website is listed twice")
        # Existing IDs preserve each website's app history and bookmarks.
        ids.add(identity); urls.add(url)
        websites.append({"id": identity, "name": name.strip(), "url": url, "enabled": site["enabled"]})
    if settings is None:
        settings = AccountSettings(user_id=uuid.UUID(user_id), settings={}, version=0); db.add(settings)
    value = {"enabled": value["enabled"], "websites": websites}
    store = dict(settings.settings or {})
    previous = json.loads(store[key]) if key in store else {}
    stamp = max(int(datetime.now(timezone.utc).timestamp() * 1000), previous.get("changedAt", 0) + 1)
    encoded = json.dumps(value, separators=(",", ":"))
    record = {**previous, "profile": profile, "kind": "navigation", "id": REPLAY_KEY,
        "changedAt": stamp, "deleted": False, "data": {**previous.get("data", {}), "value": encoded}}
    raw = json.dumps(record, separators=(",", ":"))
    store[key] = raw
    legacy = REPLAY_KEY if profile == "default" else f"profilepref_v1:{profile}:{REPLAY_KEY}"
    if legacy in store: store[legacy] = encoded
    settings.settings = store
    settings.version += 1; settings.updated_at = datetime.now(timezone.utc); settings.updated_by_device_id = None
    db.commit()
    return {"revision": revision(raw), "values": value}

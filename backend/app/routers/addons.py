"""User addon/extension management endpoints.

Addons are community-built extensions using the Stremio addon protocol.
They are stored per-account and sync across signed-in devices.
"""
import logging
import uuid
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from fastapi import Request as FastAPIRequest
from app.content_policy import AddonClassification, PolicyDecision
from app.content_policy_service import classify_and_decide_addon, resolve_channel
from app.deps import get_current_user_id, get_db, require_account_access
from app.models import UserAddon
from app.schemas import AddonInstallRequest, AddonOut, AddonUpdateRequest

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/addons", tags=["addons"])


def _to_out(row: UserAddon) -> AddonOut:
    return AddonOut.model_validate(row)


@router.get("", response_model=list[AddonOut])
def list_addons(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> list[AddonOut]:
    """List all installed addons for the authenticated user."""
    uid = uuid.UUID(user_id)
    rows = (
        db.query(UserAddon)
        .filter(UserAddon.user_id == uid)
        .order_by(UserAddon.sort_order, UserAddon.created_at)
        .all()
    )
    return [_to_out(r) for r in rows]


@router.post("", response_model=AddonOut, status_code=status.HTTP_201_CREATED)
def install_addon(
    request: FastAPIRequest,
    body: AddonInstallRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> AddonOut:
    """Install an addon by manifest URL. Idempotent: returns existing if already installed."""
    uid = uuid.UUID(user_id)
    url = body.manifest_url.strip()

    if not url:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Manifest URL is required.",
        )

    # If the client didn't supply metadata (e.g. web "paste URL" flow),
    # fetch the manifest server-side and fill in the blanks. Client-sent
    # values always win if provided.
    fetched = _fetch_manifest_metadata(url) if not body.name else None
    effective_name = body.name or (fetched or {}).get("name")
    effective_version = body.version or (fetched or {}).get("version")
    effective_description = body.description or (fetched or {}).get("description")
    effective_addon_id = body.addon_id or (fetched or {}).get("id")
    effective_has_catalog = body.has_catalog if body.has_catalog else (fetched or {}).get("has_catalog", False)
    effective_has_streams = body.has_streams if body.has_streams else (fetched or {}).get("has_streams", False)
    effective_logo_url = (fetched or {}).get("logo") or _resolve_known_logo(effective_addon_id, url)

    # Classify addon and check policy
    pm = resolve_channel(request)
    addon_decision, addon_cls = classify_and_decide_addon(
        db, user_id=uid, policy_mode=pm, manifest_url=url,
        name=effective_name, description=effective_description, addon_id=effective_addon_id,
    )
    if addon_decision == PolicyDecision.BLOCK:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": "addon_blocked", "message": "This addon is not available on this platform."},
        )
    if addon_decision == PolicyDecision.HIDE:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": "addon_sensitive_locked", "message": "Enable sensitive content access to install this addon."},
        )

    # Check if already installed (upsert-style)
    existing = db.query(UserAddon).filter(
        UserAddon.user_id == uid,
        UserAddon.manifest_url == url,
    ).first()

    if existing:
        # Update metadata if we resolved it (body or fetched manifest)
        if effective_name is not None:
            existing.name = effective_name
        if effective_addon_id is not None:
            existing.addon_id = effective_addon_id
        if effective_description is not None:
            existing.description = effective_description
        if effective_version is not None:
            existing.version = effective_version
        existing.has_catalog = bool(effective_has_catalog)
        existing.has_streams = bool(effective_has_streams)
        if effective_logo_url:
            existing.logo_url = effective_logo_url
        existing.content_classification = addon_cls.value
        existing.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        _log.info("ADDON_UPDATED user=%s addon=%s url=%s", uid, existing.addon_id, url[:60])
        return _to_out(existing)

    # Get next sort order
    max_order = db.query(UserAddon.sort_order).filter(
        UserAddon.user_id == uid
    ).order_by(UserAddon.sort_order.desc()).first()
    next_order = (max_order[0] + 1) if max_order else 0

    addon = UserAddon(
        user_id=uid,
        manifest_url=url,
        addon_id=effective_addon_id,
        name=effective_name,
        description=effective_description,
        version=effective_version,
        logo_url=effective_logo_url,
        has_catalog=bool(effective_has_catalog),
        has_streams=bool(effective_has_streams),
        sort_order=next_order,
        installed_from=body.installed_from,
        content_classification=addon_cls.value,
    )

    try:
        db.add(addon)
        db.commit()
        db.refresh(addon)
    except IntegrityError:
        db.rollback()
        # Race condition: another request installed it
        existing = db.query(UserAddon).filter(
            UserAddon.user_id == uid,
            UserAddon.manifest_url == url,
        ).first()
        if existing:
            return _to_out(existing)
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Addon already installed.")

    _log.info("ADDON_INSTALLED user=%s addon=%s url=%s from=%s",
              uid, addon.addon_id, url[:60], body.installed_from)
    return _to_out(addon)


@router.patch("/{addon_id}", response_model=AddonOut)
def update_addon(
    addon_id: uuid.UUID,
    body: AddonUpdateRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> AddonOut:
    """Update addon metadata or enabled state."""
    addon = _get_own_addon(db, user_id, addon_id)

    if body.name is not None:
        addon.name = body.name
    if body.description is not None:
        addon.description = body.description
    if body.version is not None:
        addon.version = body.version
    if body.has_catalog is not None:
        addon.has_catalog = body.has_catalog
    if body.has_streams is not None:
        addon.has_streams = body.has_streams
    if body.is_enabled is not None:
        addon.is_enabled = body.is_enabled
        _log.info("ADDON_%s user=%s addon=%s",
                  "ENABLED" if body.is_enabled else "DISABLED", user_id, addon.addon_id)
    if body.sort_order is not None:
        addon.sort_order = body.sort_order

    addon.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(addon)
    return _to_out(addon)


@router.delete("/{addon_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_addon(
    addon_id: uuid.UUID,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> None:
    """Remove an installed addon."""
    addon = _get_own_addon(db, user_id, addon_id)
    addon_name = addon.addon_id or addon.name or str(addon_id)
    db.delete(addon)
    db.commit()
    _log.info("ADDON_REMOVED user=%s addon=%s", user_id, addon_name)


@router.post("/{addon_id}/toggle", response_model=AddonOut)
def toggle_addon(
    addon_id: uuid.UUID,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> AddonOut:
    """Toggle addon enabled/disabled state."""
    addon = _get_own_addon(db, user_id, addon_id)
    addon.is_enabled = not addon.is_enabled
    addon.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(addon)
    _log.info("ADDON_%s user=%s addon=%s",
              "ENABLED" if addon.is_enabled else "DISABLED", user_id, addon.addon_id)
    return _to_out(addon)


def _get_own_addon(db: Session, user_id: str, addon_id: uuid.UUID) -> UserAddon:
    addon = db.query(UserAddon).filter(
        UserAddon.id == addon_id,
        UserAddon.user_id == uuid.UUID(user_id),
    ).first()
    if not addon:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Addon not found.",
        )
    return addon


# Known-addon logo fallbacks. Used when a manifest doesn't declare a `logo`
# field (or when the manifest host blocks server-side fetches, e.g. Cloudflare).
# Keys: addon manifest id. Values: publicly-accessible logo URL.
_KNOWN_ADDON_LOGOS = {
    "com.linvo.cinemeta": "https://torve.app/assets/addon-logos/cinemeta.png",
    "com.stremio.torrentio.addon": "https://torve.app/assets/addon-logos/torrentio.svg",
    "com.torve.panda": "https://panda.torve.app/logo.png",
}

# URL pattern fallbacks (when addon_id isn't known yet but the manifest URL
# hints at a well-known addon).
_KNOWN_URL_LOGOS = [
    ("torrentio.strem.fun", "https://torve.app/assets/addon-logos/torrentio.svg"),
    ("v3-cinemeta.strem.io", "https://torve.app/assets/addon-logos/cinemeta.png"),
    ("panda.torve.app", "https://panda.torve.app/logo.png"),
]


def _resolve_known_logo(addon_id: str | None, manifest_url: str) -> str | None:
    """Return a local fallback logo for known addons, or None."""
    if addon_id and addon_id in _KNOWN_ADDON_LOGOS:
        return _KNOWN_ADDON_LOGOS[addon_id]
    for pattern, logo in _KNOWN_URL_LOGOS:
        if pattern in manifest_url:
            return logo
    return None


_MANIFEST_FETCH_TIMEOUT = 6.0


def _fetch_manifest_metadata(url: str) -> dict | None:
    """Fetch a Stremio-style manifest and extract display metadata.

    Returns None on any failure (network, non-2xx, bad JSON, missing fields).
    The caller merges result with client-sent values and uses client values
    on conflict.
    """
    try:
        with httpx.Client(timeout=_MANIFEST_FETCH_TIMEOUT, follow_redirects=True) as client:
            resp = client.get(url, headers={"accept": "application/json"})
        if resp.status_code >= 400:
            return None
        manifest = resp.json()
    except Exception as e:
        _log.info("ADDON_MANIFEST_FETCH_FAILED url=%s err=%s", url[:80], e)
        return None

    if not isinstance(manifest, dict):
        return None

    resources = manifest.get("resources") or []
    # resources can be list of strings or list of {name, types, idPrefixes}
    resource_names = set()
    for r in resources:
        if isinstance(r, str):
            resource_names.add(r)
        elif isinstance(r, dict) and isinstance(r.get("name"), str):
            resource_names.add(r["name"])

    has_streams = "stream" in resource_names
    # Has-catalog: either resources includes "catalog", or the manifest
    # declares a non-empty catalogs array
    has_catalog = ("catalog" in resource_names) or bool(manifest.get("catalogs"))

    logo = manifest.get("logo") or manifest.get("icon")
    if not isinstance(logo, str):
        logo = None

    return {
        "id": manifest.get("id") if isinstance(manifest.get("id"), str) else None,
        "name": manifest.get("name") if isinstance(manifest.get("name"), str) else None,
        "description": manifest.get("description") if isinstance(manifest.get("description"), str) else None,
        "version": manifest.get("version") if isinstance(manifest.get("version"), str) else None,
        "logo": logo,
        "has_catalog": has_catalog,
        "has_streams": has_streams,
    }

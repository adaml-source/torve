import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from app.family_policy import guard_profile_settings, locked_policy
from sqlalchemy.orm import Session

from app.account_settings_policy import scrub_stored_settings, strip_forbidden_keys
from app.deps import get_current_user_id, get_db
from app.models import AccountSettings
from app.routers.meta import VALID_AI_PROVIDER_VALUES
from app.schemas import AccountSettingsOut, AccountSettingsPatch

router = APIRouter(prefix="/me/account-settings", tags=["account-settings"])

# Default shared settings for new accounts
_DEFAULTS = {
    "language": "en",
    "home_layout": "default",
    "ratings_provider": "imdb",
}


@router.get("", response_model=AccountSettingsOut)
def get_account_settings(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> AccountSettingsOut:
    uid = uuid.UUID(user_id)
    row = _get_or_create(db, uid)
    # Legacy rows may contain secrets written before the strip-on-write
    # policy landed. Scrub on read; if anything was removed, persist the
    # scrubbed blob back so the next read is a no-op.
    scrubbed, removed = scrub_stored_settings(row.settings or {}, user_id=uid, context="get")
    if removed:
        row.settings = scrubbed
        row.version += 1
        row.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(row)
    return row


@router.patch("", response_model=AccountSettingsOut)
def patch_account_settings(
    body: AccountSettingsPatch,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> AccountSettingsOut:
    uid = uuid.UUID(user_id)
    # Match Family/profile writes: lock the owner before the settings row.
    # The opposite order can deadlock concurrent app sync and portal edits.
    locked_policy(db, uid)
    row = _get_or_create(db, uid)
    guard_profile_settings(db, uid, request, body.settings)

    # Validate ai_provider if present
    ai_prov = body.settings.get("ai_provider")
    if ai_prov is not None and ai_prov != "" and ai_prov not in VALID_AI_PROVIDER_VALUES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unsupported ai_provider: '{ai_prov}'. Valid values: {sorted(VALID_AI_PROVIDER_VALUES)}",
        )

    # Strip forbidden (secret-bearing) keys from the incoming patch. Silent
    # strip preserves safe keys in mixed payloads. Audit-logged.
    safe_incoming = strip_forbidden_keys(body.settings, user_id=uid, context="patch")

    # Household records converge even if two devices finish PATCH out of order.
    # Family restrictions are stored separately and use a server version check.
    import json
    def order(raw):
        value = json.loads(raw)
        return (int(value["changedAt"]), bool(value.get("deleted", False)), json.dumps(value.get("data", {}), sort_keys=True, separators=(",", ":"), ensure_ascii=False))
    for key in list(safe_incoming):
        if key.startswith("household_v1:"):
            try:
                candidate = json.loads(safe_incoming[key])
                expected = f"household_v1:{candidate['profile']}:{candidate['kind']}:{candidate['id']}"
                if expected != key or len(safe_incoming[key]) > 262144: raise ValueError()
                if key in row.settings and order(row.settings[key]) >= order(safe_incoming[key]): safe_incoming.pop(key)
            except (TypeError, KeyError, ValueError):
                raise HTTPException(422, "Invalid household sync record")
    merged = {**row.settings, **safe_incoming}
    row.settings = merged
    row.version += 1
    row.updated_at = datetime.now(timezone.utc)
    row.updated_by_device_id = body.device_id
    db.commit()
    db.refresh(row)
    return row


def _get_or_create(db: Session, user_id: uuid.UUID) -> AccountSettings:
    row = (
        db.query(AccountSettings)
        .filter(AccountSettings.user_id == user_id)
        .with_for_update()
        .first()
    )
    if not row:
        row = AccountSettings(
            user_id=user_id,
            settings=dict(_DEFAULTS),
        )
        db.add(row)
        db.commit()
        db.refresh(row)
    return row

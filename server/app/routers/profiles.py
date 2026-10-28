"""Account owner portal: edit existing app household records, never a second profile store."""
import json
import uuid
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import Field, field_validator
from sqlalchemy.orm import Session

from app.crypto import encrypt_secret
from app.deps import get_current_user_id, get_db
from app.family_policy import KidsRules, StrictModel, locked_policy, now, require_parent
from app.models import AccountSettings, UserIntegration
from app.profile_preferences import PREFERENCE_SCHEMA, read_preferences, remote_key, validate_preferences

router = APIRouter()
Rating = Literal["G", "PG", "PG_13", "R", "NC_17"]


class ProfileCreate(StrictModel):
    name: str = Field(min_length=1, max_length=60)
    avatar: int = Field(default=0, ge=0, le=7)
    rating: Rating | None = None
    pin: str | None = Field(default=None, pattern=r"^[0-9]{4,6}$")

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        value = value.strip()
        if not value or any(ord(c) < 32 for c in value): raise ValueError("Enter a profile name")
        return value


class ProfileEdit(ProfileCreate):
    expectedChangedAt: int = Field(ge=0)
    expectedFamilyVersion: int = Field(ge=0)
    clearPin: bool = False
    kidsRules: KidsRules | None = None
    preferences: dict[str, str] = Field(default_factory=dict, max_length=100)
    expectedPreferences: dict[str, str | None] = Field(default_factory=dict, max_length=100)


class ProfileDelete(StrictModel):
    expectedChangedAt: int = Field(ge=0)
    expectedFamilyVersion: int = Field(ge=0)


def _key(profile): return f"household_v1:{profile}:profile:{profile}"


def _records(settings):
    result = {}
    for key, raw in settings.items():
        if not key.startswith("household_v1:") or ":profile:" not in key: continue
        try:
            record = json.loads(raw)
            if (record.get("kind") == "profile" and record.get("id") == record.get("profile")
                    and _key(record["profile"]) == key and not record.get("deleted")):
                result[record["profile"]] = record
        except (ValueError, TypeError, KeyError): continue
    # Existing accounts may not have opened a profile-aware app yet.
    result.setdefault("default", {"profile": "default", "kind": "profile", "id": "default", "changedAt": 0,
        "data": {"name": "Default", "avatar": 0, "protected": False, "pinRevision": 0, "createdAt": 0}})
    return result


def _context(request, response, user_id, db):
    response.headers["Cache-Control"] = "private, no-store"
    policy = locked_policy(db, uuid.UUID(user_id))
    if policy.enabled: require_parent(policy, request)
    row = db.query(AccountSettings).filter_by(user_id=policy.user_id).with_for_update().first()
    if row is None:
        row = AccountSettings(user_id=policy.user_id, settings={}, version=0)
        db.add(row); db.flush()
    return policy, row


def _out(record, settings, policy):
    data = record.get("data", {})
    return {"id": record["profile"], "name": data.get("name", "Profile"), "avatar": data.get("avatar", 0),
        "rating": data.get("rating"), "protected": bool(data.get("protected")), "changedAt": record["changedAt"],
        "preferences": read_preferences(settings, record["profile"]),
        "kidsRules": (policy.kids or {}).get(record["profile"]) if policy.enabled else None}


def _commit(row, db):
    row.version += 1; row.updated_at = now(); row.updated_by_device_id = None
    db.commit()


def _stamp(old): return max(int(now().timestamp() * 1000), int(old.get("changedAt", 0)) + 1)


def _pin(db, uid, profile, pin, clear=False):
    existing = db.query(UserIntegration).filter_by(user_id=uid, integration_type=f"HOUSEHOLD_PIN:{profile}").first()
    if clear:
        if existing: db.delete(existing)
    elif pin:
        if existing is None:
            existing = UserIntegration(user_id=uid, integration_type=f"HOUSEHOLD_PIN:{profile}"); db.add(existing)
        existing.storage_mode = "account"; existing.is_connected = True
        existing.encrypted_credentials = encrypt_secret(json.dumps({"pin": pin}))
        existing.display_identifier = "Profile PIN"; existing.config = {"torve_profile_id": profile}
        existing.updated_at = now()


@router.get("")
def list_profiles(request: Request, response: Response, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    policy, row = _context(request, response, user_id, db)
    result = {"profiles": [_out(r, row.settings, policy) for r in _records(row.settings or {}).values()],
              "preferenceSchema": PREFERENCE_SCHEMA, "familyVersion": policy.version}
    db.commit()
    return result


@router.post("", status_code=201)
def create_profile(body: ProfileCreate, request: Request, response: Response,
                   user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    policy, row = _context(request, response, user_id, db)
    if len(_records(row.settings or {})) >= 32: raise HTTPException(409, "Profile limit reached")
    profile = uuid.uuid4().hex[:24]
    stamp = int(now().timestamp() * 1000)
    data = {"name": body.name, "avatar": body.avatar, "createdAt": stamp,
            "protected": bool(body.pin), "pinRevision": 1 if body.pin else 0}
    if body.rating: data["rating"] = body.rating
    record = {"profile": profile, "kind": "profile", "id": profile, "changedAt": stamp, "deleted": False, "data": data}
    _pin(db, policy.user_id, profile, body.pin)
    row.settings = {**(row.settings or {}), _key(profile): json.dumps(record, separators=(",", ":"))}
    _commit(row, db)
    return _out(record, row.settings, policy)


@router.put("/{profile}")
def edit_profile(profile: str, body: ProfileEdit, request: Request, response: Response,
                 user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    policy, row = _context(request, response, user_id, db)
    old = _records(row.settings or {}).get(profile)
    if not old: raise HTTPException(404, "Profile is no longer available")
    if old["changedAt"] != body.expectedChangedAt or policy.version != body.expectedFamilyVersion:
        raise HTTPException(409, "This profile changed on another device. Reload before saving")
    if body.pin and body.clearPin: raise HTTPException(422, "Choose a new PIN or remove the PIN")
    if body.kidsRules is not None and not policy.enabled: raise HTTPException(409, "Enable Family controls before choosing child source rules")
    validate_preferences(body.preferences)
    current = read_preferences(row.settings or {}, profile)
    if any(k not in body.expectedPreferences or current.get(k) != body.expectedPreferences[k] for k in body.preferences):
        raise HTTPException(409, "Preferences changed on another device. Reload before saving")
    data = {**old.get("data", {}), "name": body.name, "avatar": body.avatar}
    if body.rating is None: data.pop("rating", None)
    else: data["rating"] = body.rating
    if body.pin or body.clearPin:
        data["protected"] = bool(body.pin)
        data["pinRevision"] = int(data.get("pinRevision", 0)) + 1
        _pin(db, policy.user_id, profile, body.pin, body.clearPin)
    kids = dict(policy.kids or {})
    if body.kidsRules is None: kids.pop(profile, None)
    else: kids[profile] = body.kidsRules.model_dump()
    if len(kids) > 8: raise HTTPException(422, "Up to eight child profiles are supported")
    if kids != (policy.kids or {}): policy.kids = kids; policy.version += 1
    record = {**old, "changedAt": _stamp(old), "data": data}
    settings = {**(row.settings or {}), _key(profile): json.dumps(record, separators=(",", ":"))}
    for key, value in body.preferences.items(): settings[remote_key(profile, key)] = value
    row.settings = settings
    _commit(row, db)
    return {"profile": _out(record, row.settings, policy), "familyVersion": policy.version}


@router.delete("/{profile}")
@router.post("/{profile}/delete")
def delete_profile(profile: str, body: ProfileDelete, request: Request, response: Response,
                   user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    policy, row = _context(request, response, user_id, db)
    if profile == "default": raise HTTPException(422, "The default profile cannot be deleted")
    old = _records(row.settings or {}).get(profile)
    if not old: raise HTTPException(404, "Profile is no longer available")
    if old["changedAt"] != body.expectedChangedAt or policy.version != body.expectedFamilyVersion:
        raise HTTPException(409, "This profile changed on another device. Reload before deleting")
    record = {**old, "changedAt": _stamp(old), "deleted": True, "data": {}}
    row.settings = {**row.settings, _key(profile): json.dumps(record, separators=(",", ":"))}
    kids = dict(policy.kids or {})
    if profile in kids:
        kids.pop(profile); policy.kids = kids; policy.version += 1
    _pin(db, policy.user_id, profile, None, clear=True)
    _commit(row, db)
    return {"deleted": profile}

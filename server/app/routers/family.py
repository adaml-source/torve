import copy
import json
import secrets
import uuid
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import Field
from sqlalchemy.orm import Session

from app import mail
from app.crypto import decrypt_secret, encrypt_secret
from app.deps import get_current_user_id, get_db
from app.family_policy import (FamilyAccountPolicy, PolicyUpdate, PinProof, RecoveryProof, StrictModel, PROFILE,
    locked_policy, configuration, snapshot, parent_grant, require_parent, verify_pin, now, aware, recovery_digest)
from app.models import User, UserIntegration
from app.security import hash_password
from app.family_policy import canonical_connection, guard_integration_read

router = APIRouter(prefix="/me/family", tags=["family"])


def no_cache(response): response.headers["Cache-Control"] = "no-store"


@router.get("")
def get_policy(response: Response, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id)); db.commit()
    return snapshot(row)


@router.put("")
def update_policy(body: PolicyUpdate, request: Request, response: Response,
                  user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id))
    if row.version != body.expectedVersion: raise HTTPException(409, "Family settings changed on another device. Refresh and try again")
    if body.configuration.accountId not in ("", user_id): raise HTTPException(403, "Account mismatch")
    if row.enabled: require_parent(row, request)
    if body.configuration.enabled and (not row.enabled or body.pin is not None):
        if body.pin is None: raise HTTPException(422, "Choose a parent PIN")
        row.pin_hash = hash_password(body.pin); row.pin_revision += 1
    if not body.configuration.enabled:
        row.pin_hash = None; row.pin_revision += 1
    row.enabled = body.configuration.enabled
    row.kids = {key: value.model_dump() for key, value in body.configuration.kids.items()} if row.enabled else {}
    row.version += 1
    row.recovery_hash = None; row.recovery_expires = None
    result = parent_grant(row, request) if row.enabled else {**snapshot(row), "grant": "", "expiresAt": 0}
    db.commit()
    return result


@router.post("/authorize")
def authorize(body: PinProof, request: Request, response: Response,
              user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id))
    if not row.enabled: raise HTTPException(409, "Family controls are off")
    verify_pin(db, row, body.pin)
    result = parent_grant(row, request)
    db.commit()
    return result


@router.post("/recovery/request")
def request_recovery(response: Response, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id)); owner = db.get(User, row.user_id)
    if not row.enabled: raise HTTPException(409, "Family controls are off")
    if not owner.is_verified: raise HTTPException(403, "Verify the account owner's email before PIN recovery")
    if aware(row.recovery_sent) and now() - aware(row.recovery_sent) < timedelta(seconds=60):
        raise HTTPException(429, "Wait a minute before requesting another code")
    code = f"{secrets.randbelow(1000000):06d}"
    row.recovery_hash = recovery_digest(user_id, code, owner.email); row.recovery_expires = now() + timedelta(minutes=10)
    row.recovery_sent = now(); row.recovery_failures = 0
    if not mail.send_email(to=owner.email, subject="Reset your Torve parent PIN",
            html=f"<h2>Reset your parent PIN</h2><p>Your Torve code is <strong>{code}</strong>.</p><p>It expires in 10 minutes. Do not share it. If you did not request this, ignore this email.</p>",
            text=f"Your Torve parent PIN reset code is {code}. It expires in 10 minutes. Do not share it. If you did not request this, ignore this email."):
        db.rollback(); raise HTTPException(503, "Could not send the recovery email. Try again shortly")
    db.commit()
    name, domain = owner.email.rsplit("@", 1)
    return {"destination": name[:1] + "***@" + domain, "expiresInSeconds": 600}


@router.post("/recovery/confirm")
def confirm_recovery(body: RecoveryProof, request: Request, response: Response,
                     user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id))
    if (not row.enabled or not row.recovery_hash or not row.recovery_expires or aware(row.recovery_expires) <= now()
            or row.recovery_failures >= 5): raise HTTPException(403, "Request a new recovery code")
    import hmac
    if not hmac.compare_digest(row.recovery_hash, recovery_digest(user_id, body.code, db.get(User, row.user_id).email)):
        row.recovery_failures += 1; db.commit(); raise HTTPException(403, "Incorrect recovery code")
    row.pin_hash = hash_password(body.pin); row.pin_revision += 1; row.version += 1
    row.failures = 0; row.locked_until = None; row.recovery_hash = None; row.recovery_expires = None
    result = parent_grant(row, request)
    db.commit()
    return result


COPY_TYPES = {"DEBRID_API_KEY_REAL_DEBRID", "DEBRID_API_KEY_ALL_DEBRID", "DEBRID_API_KEY_PREMIUMIZE", "DEBRID_API_KEY_TORBOX",
    "TRAKT_TOKENS", "SIMKL_ACCESS_TOKEN", "JELLYFIN_API_KEY", "PLEX_ACCESS_TOKEN", "MDBLIST_API_KEY", "OMDB_API_KEY",
    "CLAUDE_API_KEY", "CHATGPT_API_KEY", "GEMINI_API_KEY", "PERPLEXITY_API_KEY", "DEEPSEEK_API_KEY"}


def scoped_type(base, profile): return base if profile == "default" else f"{base}::p:{profile}"


class CopyConnections(StrictModel):
    sourceProfile: str = Field(pattern=r"^[A-Za-z0-9_-]{1,30}$")
    targetProfile: str = Field(pattern=r"^[A-Za-z0-9_-]{1,30}$")
    services: list[str] = Field(min_length=1, max_length=20)
    replaceExisting: bool = False


@router.get("/connections/{profile}")
def list_connections(profile: str, request: Request, response: Response,
                     user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id))
    # When Family is enabled, only a parent can share credentials.
    if row.enabled: require_parent(row, request)
    if not PROFILE.fullmatch(profile): raise HTTPException(422, "Invalid profile")
    rows = db.query(UserIntegration).filter(UserIntegration.user_id == row.user_id).all()
    return {"services": [base for base in sorted(COPY_TYPES) if any(r.integration_type == scoped_type(base, profile)
            and r.storage_mode == "account" and r.is_connected for r in rows)]}


@router.post("/connections/copy")
def copy_connections(body: CopyConnections, request: Request, response: Response,
                     user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    row = locked_policy(db, uuid.UUID(user_id))
    if row.enabled: require_parent(row, request)
    if body.sourceProfile == body.targetProfile: raise HTTPException(422, "Choose different profiles")
    if not set(body.services) <= COPY_TYPES: raise HTTPException(422, "Unsupported connection")
    # Validate every requested row before writing any destination.
    pairs = []
    for service in sorted(set(body.services)):
        source = db.query(UserIntegration).filter_by(user_id=row.user_id, integration_type=scoped_type(service, body.sourceProfile)).first()
        target = db.query(UserIntegration).filter_by(user_id=row.user_id, integration_type=scoped_type(service, body.targetProfile)).first()
        if not source or source.storage_mode != "account" or not source.is_connected:
            raise HTTPException(409, "Save the selected source connection to your account first")
        if target and target.is_connected and not body.replaceExisting:
            raise HTTPException(409, "The destination already has a connection. Choose Replace existing to continue")
        # Re-encrypt instead of reusing ciphertext, and never return the credential.
        canonical = canonical_connection(db, row.user_id, source) if service == "TRAKT_TOKENS" else source
        encrypted = encrypt_secret(decrypt_secret(canonical.encrypted_credentials))
        pairs.append((service, source, target, encrypted))
    for service, source, target, encrypted in pairs:
        if not target:
            target = UserIntegration(user_id=row.user_id, integration_type=scoped_type(service, body.targetProfile)); db.add(target)
        target.storage_mode = "account"; target.is_connected = True
        target.encrypted_credentials = encrypted; target.display_identifier = source.display_identifier
        target.config = {**copy.deepcopy(source.config or {}), "torve_profile_id": body.targetProfile, "family_copy_profile": body.targetProfile}
        if service == "TRAKT_TOKENS": target.config = {**target.config, "shared_connection_origin": canonical_connection(db, row.user_id, source).integration_type}
        target.updated_at = now()
    db.commit()
    return {"copied": [p[0] for p in pairs], "targetProfile": body.targetProfile}


class RotateTrakt(StrictModel):
    profile: str = Field(pattern=r"^[A-Za-z0-9_-]{1,40}$")
    previousRefreshToken: str = Field(max_length=4096)
    credentials: dict[str, str]


@router.post("/connections/trakt/rotate")
def rotate_trakt(body: RotateTrakt, request: Request, response: Response,
                 user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    no_cache(response)
    uid = uuid.UUID(user_id)
    locked_policy(db, uid)
    selected = db.query(UserIntegration).filter_by(user_id=uid, integration_type=scoped_type("TRAKT_TOKENS", body.profile)).first()
    if not selected: raise HTTPException(404, "Connection missing")
    guard_integration_read(db, uid, request, selected)
    root = canonical_connection(db, uid, selected)
    current = json.loads(decrypt_secret(root.encrypted_credentials))
    import hmac
    prior = current.get("refreshToken", current.get("refresh_token", ""))
    if not body.previousRefreshToken or not hmac.compare_digest(prior, body.previousRefreshToken):
        return {"credentials": current}
    allowed = {"accessToken", "refreshToken", "expiresIn", "createdAt", "access_token", "refresh_token", "expires_in", "created_at", "client_id", "client_secret"}
    if not set(body.credentials) <= allowed or not body.credentials.get("accessToken", body.credentials.get("access_token")):
        raise HTTPException(422, "Invalid Trakt refresh payload")
    # Retain the OAuth app identity that issued these tokens when refreshing a
    # shared connection. Rotations from older clients contain only token fields.
    credentials = {**current, **body.credentials}
    for camel, snake in (("accessToken", "access_token"), ("refreshToken", "refresh_token"),
                         ("expiresIn", "expires_in"), ("createdAt", "created_at")):
        value = body.credentials.get(camel, body.credentials.get(snake, current.get(camel, current.get(snake))))
        credentials.pop(snake, None)
        if value is not None: credentials[camel] = value
    root.encrypted_credentials = encrypt_secret(json.dumps(credentials)); root.updated_at = now()
    db.commit()
    return {"credentials": credentials}


# The portal edits the same household records consumed by mobile and TV apps.
from app.routers.profiles import router as profile_router
router.include_router(profile_router, prefix="/profiles")

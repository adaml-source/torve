import hashlib
import json
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.cloud_backup import CloudBackup, CreateBackup, LibraryPayload, MAX_BYTES, RETAIN, metadata
from app.crypto import encrypt_secret, decrypt_secret
from app.deps import get_current_user_id, get_db
from app.models import User

router = APIRouter(prefix="/me/backups", tags=["cloud-backup"])


def owner(db, user_id, *, lock=False):
    query = db.query(User).filter(User.id == uuid.UUID(user_id))
    user = (query.with_for_update() if lock else query).first()
    if not user or not user.is_active:
        raise HTTPException(401, "Sign in to an active Torve account.")
    if not user.is_verified:
        raise HTTPException(403, "Confirm your email before using cloud backup.")
    return user.id


def owned(db, uid, backup_id):
    row = db.query(CloudBackup).filter_by(user_id=uid, id=backup_id).first()
    if not row:
        raise HTTPException(404, "Backup not found.")
    return row


@router.get("")
def list_backups(response: Response, user_id=Depends(get_current_user_id), db: Session = Depends(get_db)):
    uid = owner(db, user_id)
    response.headers["Cache-Control"] = "no-store"
    return {"backups": [metadata(r) for r in db.query(CloudBackup).filter_by(user_id=uid).order_by(CloudBackup.created_at.desc(), CloudBackup.id.desc()).limit(RETAIN)]}


@router.post("", status_code=201)
async def create_backup(request: Request, response: Response, user_id=Depends(get_current_user_id), db: Session = Depends(get_db)):
    uid = owner(db, user_id)
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BYTES:
            raise HTTPException(413, "Backup is too large.")
        chunks.append(chunk)
    try:
        body = CreateBackup.model_validate_json(b"".join(chunks))
    except (ValidationError, ValueError):
        # Never echo rejected input: it may contain credentials from an old client.
        raise HTTPException(422, "Backup contains unsupported data. Update Torve and try again.") from None
    from app.apple_subscription import active_expiry
    from app.billing import check_premium_active
    # Android and Fire TV are free clients. Ownership and verified-account rules
    # apply to every platform; Apple clients retain their existing write policy.
    if body.platform in {"ios", "tvos"} and active_expiry(db, uid) is None and not check_premium_active(db, uid):
        raise HTTPException(403, "Torve Premium is required to create cloud backups.")
    owner(db, user_id, lock=True)
    payload = body.payload.model_dump_json()
    digest = hashlib.sha256(payload.encode()).hexdigest()
    existing = db.query(CloudBackup).filter_by(user_id=uid, request_id=body.request_id).first()
    if existing:
        if existing.payload_hash != digest:
            raise HTTPException(409, "Backup request already used. Refresh and try again.")
        return metadata(existing)
    row = CloudBackup(user_id=uid, request_id=body.request_id, platform=body.platform, reason=body.reason,
        payload_hash=digest, byte_count=len(payload.encode()), counts_json=json.dumps(body.payload.counts()),
        encrypted_payload=encrypt_secret(payload))
    db.add(row); db.flush()
    old = db.query(CloudBackup).filter(CloudBackup.user_id == uid, CloudBackup.id != row.id).order_by(CloudBackup.created_at.desc(), CloudBackup.id.desc()).offset(RETAIN - 1).all()
    for item in old:
        db.delete(item)
    db.commit(); db.refresh(row)
    response.headers["Cache-Control"] = "no-store"
    return metadata(row)


@router.get("/{backup_id}")
def read_backup(backup_id: uuid.UUID, response: Response, user_id=Depends(get_current_user_id), db: Session = Depends(get_db)):
    row = owned(db, owner(db, user_id), backup_id)
    try:
        payload = LibraryPayload.model_validate_json(decrypt_secret(row.encrypted_payload))
    except (ValidationError, ValueError):
        raise HTTPException(503, "This backup could not be opened. Please try again later.") from None
    response.headers["Cache-Control"] = "no-store"
    return {"backup": metadata(row), "payload": payload.model_dump()}


@router.delete("/{backup_id}", status_code=204)
def delete_backup(backup_id: uuid.UUID, user_id=Depends(get_current_user_id), db: Session = Depends(get_db)):
    db.delete(owned(db, owner(db, user_id, lock=True), backup_id)); db.commit()
    return Response(status_code=204)

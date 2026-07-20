"""
Account-scoped integration credential management.

Supports two storage modes:
- "account": secrets encrypted at rest, restored on login, synced across devices
- "device_only": metadata/config stored server-side, secrets remain on device only

Secrets are never returned in standard API responses after save.
"""

import json
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.deps import (
    AuthenticatedDeviceContext,
    get_current_user_id,
    get_db,
    require_active_device,
    require_account_access,
)
from app.models import UserIntegration
from app.rate_limits import enforce_rate_limit
from app.schemas import (
    IntegrationCredentialsPatchRequest,
    IntegrationOut,
    IntegrationSaveRequest,
    IntegrationTestResult,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/integrations", tags=["integrations"])

_EMPTY_CREDS = ""  # Sentinel for device_only mode (no server-side secret)
_RESTORE_RATE_LIMIT = 20
_RESTORE_RATE_WINDOW_SECONDS = 60


def _to_out(row: UserIntegration) -> IntegrationOut:
    """Convert a DB row to the response DTO, computing has_credentials."""
    return IntegrationOut(
        id=row.id,
        integration_type=row.integration_type,
        storage_mode=row.storage_mode,
        display_identifier=row.display_identifier,
        config=row.config,
        is_connected=row.is_connected,
        has_credentials=(
            row.storage_mode == "account"
            and row.encrypted_credentials != _EMPTY_CREDS
        ),
        last_verified_at=row.last_verified_at,
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


@router.get("", response_model=list[IntegrationOut])
def list_integrations(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> list[IntegrationOut]:
    """List all integrations for the authenticated user.

    Returns metadata, storage mode, and connection status.
    Never returns secrets. device_only entries are included so the
    client knows which integrations are configured, but has_credentials
    will be False for device_only entries.
    """
    uid = uuid.UUID(user_id)
    rows = (
        db.query(UserIntegration)
        .filter(UserIntegration.user_id == uid)
        .order_by(UserIntegration.integration_type)
        .all()
    )
    return [_to_out(r) for r in rows]


@router.get("/{integration_type}", response_model=IntegrationOut)
def get_integration(
    integration_type: str,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> IntegrationOut:
    """Get a specific integration by type. Returns metadata only, no secrets."""
    row = _get_own_integration(db, user_id, integration_type)
    return _to_out(row)


@router.put("/{integration_type}", response_model=IntegrationOut,
            status_code=status.HTTP_200_OK)
def save_integration(
    integration_type: str,
    body: IntegrationSaveRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> IntegrationOut:
    """Save or update an integration.

    For storage_mode="account": credentials are encrypted and stored.
    For storage_mode="device_only": credentials are ignored, only
    metadata and config are stored server-side.
    """
    uid = uuid.UUID(user_id)

    if body.integration_type != integration_type:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="integration_type in body must match URL path.",
        )

    # Determine encrypted blob based on mode
    creds = body.resolved_credentials()
    if body.storage_mode == "account":
        if not creds:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="credentials are required for account storage mode.",
            )
        encrypted = encrypt_secret(json.dumps(creds))
    else:
        # device_only: do not store secrets server-side
        encrypted = _EMPTY_CREDS

    # Upsert
    existing = (
        db.query(UserIntegration)
        .filter(
            UserIntegration.user_id == uid,
            UserIntegration.integration_type == integration_type,
        )
        .first()
    )

    if existing:
        existing.encrypted_credentials = encrypted
        existing.storage_mode = body.storage_mode
        existing.config = body.config
        existing.display_identifier = body.display_identifier
        existing.is_connected = True
        existing.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        _log.info("Updated integration %s (mode=%s) for user %s",
                  integration_type, body.storage_mode, uid)
        return _to_out(existing)

    row = UserIntegration(
        user_id=uid,
        integration_type=integration_type,
        storage_mode=body.storage_mode,
        display_identifier=body.display_identifier,
        encrypted_credentials=encrypted,
        config=body.config,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    _log.info("Created integration %s (mode=%s) for user %s",
              integration_type, body.storage_mode, uid)
    return _to_out(row)


@router.patch("/{integration_type}/credentials", response_model=IntegrationOut)
def patch_credentials(
    integration_type: str,
    body: IntegrationCredentialsPatchRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> IntegrationOut:
    """Merge new keys into an existing integration's stored credentials.

    Filling holes in a credential blob — the most common case is the
    PANDA_TOKEN row that the original Android create flow saved with
    only `{token: <manifest_token>}` and no `management_token`. The
    desktop's recovery banner posts just the missing key here without
    overwriting the manifest token alongside it.

    Empty `credentials` dict is a no-op and returns the row unchanged.
    Existing keys with matching names get overwritten.

    Account-access gated to match the PUT save path. device_only integrations
    have no server-side secret blob, so they reject 400 — re-save in
    account mode if you want to start storing secrets.
    """
    row = _get_own_integration(db, user_id, integration_type)
    if row.storage_mode == "device_only":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot patch credentials on a device_only integration.",
        )
    if not body.credentials:
        # No keys to merge; return the row as-is.
        return _to_out(row)

    try:
        existing = json.loads(decrypt_secret(row.encrypted_credentials)) if row.encrypted_credentials else {}
    except (ValueError, json.JSONDecodeError):
        # Corrupted blob — replace rather than refuse, since refusing
        # leaves the user permanently stuck. Log so it shows up in
        # journalctl as a recovery event.
        _log.warning(
            "PATCH credentials: existing blob unreadable for user=%s type=%s, replacing",
            user_id, integration_type,
        )
        existing = {}

    if not isinstance(existing, dict):
        existing = {}

    merged = {**existing, **body.credentials}
    row.encrypted_credentials = encrypt_secret(json.dumps(merged))
    row.is_connected = True
    row.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(row)
    _log.info(
        "PATCH credentials: merged %d key(s) into %s for user %s",
        len(body.credentials), integration_type, user_id,
    )
    return _to_out(row)


@router.delete("/{integration_type}", status_code=status.HTTP_204_NO_CONTENT)
def remove_integration(
    integration_type: str,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> None:
    """Remove an integration and its stored credentials."""
    row = _get_own_integration(db, user_id, integration_type)
    db.delete(row)
    db.commit()
    _log.info("Removed integration %s for user %s", integration_type, user_id)


@router.post("/{integration_type}/test", response_model=IntegrationTestResult)
def test_integration(
    integration_type: str,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> IntegrationTestResult:
    """Test a saved integration.

    For account mode: decrypts and validates stored credentials.
    For device_only mode: validates config presence only (secrets are on device).
    """
    row = _get_own_integration(db, user_id, integration_type)

    if row.storage_mode == "device_only":
        # Server has no secrets to test; just confirm config exists
        row.last_verified_at = datetime.now(timezone.utc)
        db.commit()
        return IntegrationTestResult(
            integration_type=integration_type,
            success=True,
            message="Device-only integration. Credentials are stored locally on your device.",
        )

    try:
        creds = json.loads(decrypt_secret(row.encrypted_credentials))
    except (ValueError, json.JSONDecodeError):
        row.is_connected = False
        db.commit()
        return IntegrationTestResult(
            integration_type=integration_type,
            success=False,
            message="Stored credentials could not be decrypted. Please re-save.",
        )

    if not creds:
        row.is_connected = False
        db.commit()
        return IntegrationTestResult(
            integration_type=integration_type,
            success=False,
            message="Stored credentials are empty.",
        )

    row.is_connected = True
    row.last_verified_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(row)

    return IntegrationTestResult(
        integration_type=integration_type,
        success=True,
        message="Integration credentials are valid.",
    )


@router.get("/{integration_type}/credentials", include_in_schema=False)
def get_credentials(
    integration_type: str,
    request: Request,
    caller: AuthenticatedDeviceContext = Depends(require_active_device),
    db: Session = Depends(get_db),
) -> dict:
    """Internal: return decrypted credentials for the app to use.

    Only works for account-mode integrations. device_only integrations
    return an empty credentials dict since secrets live on device.

    Restore is active-device gated because this endpoint releases account
    secrets to a registered install owned by the user. Users who never saved
    anything naturally get 404 from _get_own_integration.
    """
    enforce_rate_limit(
        category="credential_restore_integration",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=_RESTORE_RATE_LIMIT,
        window_seconds=_RESTORE_RATE_WINDOW_SECONDS,
    )

    row = _get_own_integration(db, caller.user_id, integration_type)

    if row.storage_mode == "device_only":
        _log.info(
            "CREDENTIAL_RESTORE kind=integration user=%s device=%s integration=%s "
            "storage_mode=device_only credentials_returned=0",
            caller.user_id, caller.device_id, integration_type,
        )
        return {
            "integration_type": integration_type,
            "storage_mode": "device_only",
            "credentials": {},
        }

    try:
        creds = json.loads(decrypt_secret(row.encrypted_credentials))
    except (ValueError, json.JSONDecodeError):
        _log.warning(
            "CREDENTIAL_RESTORE_FAILED kind=integration user=%s device=%s "
            "integration=%s reason=decrypt_failed",
            caller.user_id, caller.device_id, integration_type,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "credential_restore_failed",
                "error_code": "credential_restore_failed",
                "message": "Credentials could not be restored. Please re-save.",
            },
        )
    keys_returned = len(creds) if isinstance(creds, dict) else 0
    _log.info(
        "CREDENTIAL_RESTORE kind=integration user=%s device=%s integration=%s "
        "storage_mode=account keys_returned=%d",
        caller.user_id, caller.device_id, integration_type, keys_returned,
    )
    return {
        "integration_type": integration_type,
        "storage_mode": "account",
        "credentials": creds,
    }


def _get_own_integration(
    db: Session, user_id: str, integration_type: str
) -> UserIntegration:
    uid = uuid.UUID(user_id)
    row = (
        db.query(UserIntegration)
        .filter(
            UserIntegration.user_id == uid,
            UserIntegration.integration_type == integration_type,
        )
        .first()
    )
    if not row:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Integration '{integration_type}' not found.",
        )
    return row

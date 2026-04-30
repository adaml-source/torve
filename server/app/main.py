import secrets
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Annotated
from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.security import OAuth2PasswordBearer
from redis.asyncio import Redis
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from .config import get_settings
from .db import get_session
from .models import AccountSettings, Device, DeviceActivationEvent, Entitlement, EventOutbox, LanHub, PairingCode, Purchase, Session, User, UserPlaylist, WatchStateReport, utcnow
from .realtime import ConnectionRegistry, deliver_pending_events, dispatch_event
from .secret_wrap import WrapUnavailable, wrap as wrap_secret, unwrap as unwrap_secret
from .schemas import (
    AccountSettingsPatchRequest,
    AccountSettingsResponse,
    AccessStateResponse,
    AmazonVerifyRequest,
    AppleVerifyRequest,
    AuthLoginRequest,
    AuthLogoutRequest,
    AuthRefreshRequest,
    AuthRegisterRequest,
    AuthResponse,
    DeviceActivateResponse,
    DeviceLimitResponse,
    DeviceListResponse,
    DeviceRegistration,
    DeviceRemoveResponse,
    DeviceRenameRequest,
    DeviceResponse,
    DeviceStateResponse,
    EntitlementResponse,
    EntitlementStateResponse,
    EventDispatchResponse,
    GoogleVerifyRequest,
    HealthResponse,
    ManagedDeviceResponse,
    MeResponse,
    PasswordResetConfirmPayload,
    PasswordResetRequestPayload,
    PlaybackIntentRequest,
    PairingClaimRequest,
    PairingClaimResponse,
    PairingCodeRequest,
    PairingCodeResponse,
    PairingStatusRequest,
    PairingStatusResponse,
    PremiumStateResponse,
    PurchaseResponse,
    PurchaseVerifyResponse,
    RestorePurchasesRequest,
    SearchPushRequest,
    TokensResponse,
    UserResponse,
    WatchStateLatestResponse,
    WatchStateReportRequest,
    WatchStateReportResponse,
    PlaylistSaveRequest,
    PlaylistResponse,
    LanHubPublishRequest,
    LanHubResponse,
    LanHubListResponse,
    LanHubSecretResponse,
)
from .security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    hash_password,
    hash_token,
    verify_password,
)
from .entitlements import get_user_entitlements, process_verified_purchase, resolve_premium_access
from .store_verification import apple_verifier, google_verifier, amazon_verifier
from .account_settings_policy import is_forbidden_key, strip_forbidden_keys, scrub_stored_settings
from .device_policy import (
    MAX_ACTIVE_DEVICES,
    count_active_devices,
    count_recent_swaps,
    get_active_devices,
    prune_stale_devices,
    remove_device as policy_remove_device,
    rename_device as policy_rename_device,
    try_activate_device,
)


settings = get_settings()
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")
registry = ConnectionRegistry()
logger = logging.getLogger(__name__)


class AppState:
    redis: Redis | None = None


app_state = AppState()


@asynccontextmanager
async def lifespan(_: FastAPI):
    app_state.redis = Redis.from_url(settings.redis_url, decode_responses=True)
    try:
        await app_state.redis.ping()
    except Exception:
        pass
    yield
    if app_state.redis is not None:
        await app_state.redis.close()


app = FastAPI(title=settings.app_name, lifespan=lifespan)


def normalize_code(code: str) -> str:
    return code.strip().replace(" ", "").upper()


def generate_pairing_code() -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(6))


def as_user_response(user: User) -> UserResponse:
    return UserResponse(
        id=user.id,
        email=user.email,
        is_verified=user.is_verified,
        created_at=user.created_at,
    )


def as_device_response(device: Device) -> DeviceResponse:
    return DeviceResponse(
        id=device.id,
        installation_id=device.installation_id,
        device_name=device.device_name,
        device_type=device.device_type,
        platform=device.platform,
        last_seen_at=device.last_seen_at,
        revoked_at=device.revoked_at,
    )


def as_tokens_response(access_token: str, refresh_token: str, expires_in: int) -> TokensResponse:
    return TokensResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        token_type="bearer",
        expires_in=expires_in,
    )


async def get_or_create_device(
    session: AsyncSession,
    registration: DeviceRegistration,
    user_id: str | None,
) -> Device:
    existing_result = await session.execute(
        select(Device).where(Device.installation_id == registration.installation_id),
    )
    device = existing_result.scalar_one_or_none()
    if device is None:
        device = Device(
            installation_id=registration.installation_id,
            user_id=user_id,
            device_name=registration.device_name,
            device_type=registration.device_type,
            platform=registration.platform,
            last_seen_at=utcnow(),
        )
        session.add(device)
        await session.flush()
        return device

    device.device_name = registration.device_name
    device.device_type = registration.device_type
    device.platform = registration.platform
    device.last_seen_at = utcnow()
    if user_id is not None:
        device.user_id = user_id
        device.revoked_at = None
    await session.flush()
    return device


async def issue_tokens_for_device(
    session: AsyncSession,
    user: User,
    device: Device,
) -> TokensResponse:
    access_token, expires_in = create_access_token(user_id=user.id, device_id=device.id)
    refresh_token, refresh_expires_at = create_refresh_token(user_id=user.id, device_id=device.id)
    refresh_hash = hash_token(refresh_token)
    session.add(
        Session(
            user_id=user.id,
            device_id=device.id,
            refresh_token_hash=refresh_hash,
            expires_at=refresh_expires_at,
        ),
    )
    await session.flush()
    return as_tokens_response(access_token, refresh_token, expires_in)


async def get_current_user(
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> User:
    try:
        payload = decode_token(token, expected_type="access")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    user_id = payload["sub"]
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user


async def get_current_device(
    session: AsyncSession,
    token: str,
    user_id: str,
) -> Device | None:
    """Extract the current device from the JWT access token."""
    try:
        payload = decode_token(token, expected_type="access")
    except ValueError:
        return None
    device_id = payload.get("device_id")
    if not device_id:
        return None
    result = await session.execute(
        select(Device).where(Device.id == device_id, Device.user_id == user_id)
    )
    return result.scalar_one_or_none()


def _access_reason(has_entitlement: bool, device_active: bool, reason_override: str | None = None) -> str:
    if reason_override:
        return reason_override
    if not has_entitlement:
        return "no_entitlement"
    if device_active:
        return "active_and_device_active"
    return "device_cap_reached"


def as_managed_device(device: Device, current_device_id: str | None = None) -> ManagedDeviceResponse:
    return ManagedDeviceResponse(
        id=device.id,
        device_name=device.device_name,
        device_type=device.device_type,
        platform=device.platform,
        is_current=(device.id == current_device_id) if current_device_id else False,
        is_active=device.is_premium_active,
        last_seen_at=device.last_seen_at,
        activated_at=device.activated_at,
        removed_at=device.removed_at,
        removal_reason=device.removal_reason,
        first_seen_at=device.first_seen_at,
    )


async def _build_access_state(
    session: AsyncSession,
    user: User,
    device: Device,
    token: str,
) -> AccessStateResponse:
    """Build the full device-aware access state response."""
    entitlements = await get_user_entitlements(session, user.id)
    has_entitlement = resolve_premium_access(entitlements)

    activation = await try_activate_device(session, user.id, device, has_entitlement)
    activated = activation["activated"]
    reason = activation["reason"]
    active_count = activation["active_device_count"]
    pruned = activation["stale_devices_pruned"]
    swaps_remaining = activation["swaps_remaining"]

    # Include active device list for cap-reached screen
    active_device_list = await get_active_devices(session, user.id)
    active_device_responses = [
        as_managed_device(d, current_device_id=device.id) for d in active_device_list
    ]

    return AccessStateResponse(
        user=as_user_response(user),
        premium=PremiumStateResponse(
            has_entitlement=has_entitlement,
            premium_access=activated,
            reason=reason,
            entitlements=[as_entitlement_response(e) for e in entitlements],
        ),
        device=DeviceStateResponse(
            id=device.id,
            name=device.device_name,
            is_active=activated,
            active_device_count=active_count,
            max_active_devices=MAX_ACTIVE_DEVICES,
            platform=device.platform,
            device_type=device.device_type,
        ),
        device_limit=DeviceLimitResponse(
            cap_reached=active_count >= MAX_ACTIVE_DEVICES and not activated,
            swaps_remaining=swaps_remaining,
            stale_devices_pruned=pruned,
            active_devices=active_device_responses,
        ),
    )


async def get_authorized_target_device(
    session: AsyncSession,
    user_id: str,
    target_device_id: str,
) -> Device:
    target_result = await session.execute(
        select(Device).where(
            Device.id == target_device_id,
            Device.user_id == user_id,
            Device.revoked_at.is_(None),
        ),
    )
    target_device = target_result.scalar_one_or_none()
    if target_device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target device not found")
    return target_device


@app.get("/health", response_model=HealthResponse)
async def health(session: Annotated[AsyncSession, Depends(get_session)]) -> HealthResponse:
    db_status = "ok"
    redis_status = "ok"
    try:
        await session.execute(select(User.id).limit(1))
    except Exception:
        db_status = "error"
    try:
        if app_state.redis is None:
            redis_status = "error"
        else:
            await app_state.redis.ping()
    except Exception:
        redis_status = "error"
    overall = "ok" if db_status == "ok" and redis_status == "ok" else "degraded"
    return HealthResponse(status=overall, database=db_status, redis=redis_status)


@app.post("/auth/register", response_model=AuthResponse)
async def auth_register(
    payload: AuthRegisterRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> AuthResponse:
    normalized_email = payload.email.strip().lower()
    existing = await session.execute(select(User).where(User.email == normalized_email))
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")

    user = User(
        email=normalized_email,
        password_hash=hash_password(payload.password),
    )
    session.add(user)
    await session.flush()

    device = await get_or_create_device(session, payload.device, user.id)
    await try_activate_device(session, user.id, device, has_entitlement=True)
    tokens = await issue_tokens_for_device(session, user, device)
    await session.commit()

    return AuthResponse(
        user=as_user_response(user),
        device=as_device_response(device),
        tokens=tokens,
    )


@app.post("/auth/login", response_model=AuthResponse)
async def auth_login(
    payload: AuthLoginRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> AuthResponse:
    normalized_email = payload.email.strip().lower()
    result = await session.execute(select(User).where(User.email == normalized_email))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")

    device = await get_or_create_device(session, payload.device, user.id)
    await try_activate_device(session, user.id, device, has_entitlement=True)
    tokens = await issue_tokens_for_device(session, user, device)
    await session.commit()

    return AuthResponse(
        user=as_user_response(user),
        device=as_device_response(device),
        tokens=tokens,
    )


@app.post("/auth/refresh", response_model=AuthResponse)
async def auth_refresh(
    payload: AuthRefreshRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> AuthResponse:
    try:
        token_payload = decode_token(payload.refresh_token, expected_type="refresh")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    refresh_hash = hash_token(payload.refresh_token)
    refresh_row_result = await session.execute(
        select(Session).where(Session.refresh_token_hash == refresh_hash),
    )
    refresh_row = refresh_row_result.scalar_one_or_none()
    if refresh_row is None or refresh_row.revoked_at is not None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Refresh token revoked")
    expires_at = refresh_row.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if expires_at < utcnow():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Refresh token expired")

    user_result = await session.execute(select(User).where(User.id == token_payload["sub"]))
    user = user_result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")

    device_result = await session.execute(select(Device).where(Device.id == token_payload["device_id"]))
    device = device_result.scalar_one_or_none()
    if device is None or device.revoked_at is not None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Device revoked")

    refresh_row.revoked_at = utcnow()
    device.last_seen_at = utcnow()
    tokens = await issue_tokens_for_device(session, user, device)
    await session.commit()

    return AuthResponse(
        user=as_user_response(user),
        device=as_device_response(device),
        tokens=tokens,
    )


@app.post("/auth/logout")
async def auth_logout(
    payload: AuthLogoutRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> dict:
    revoked_count = 0
    if payload.refresh_token:
        refresh_hash = hash_token(payload.refresh_token)
        refresh_result = await session.execute(
            select(Session).where(
                Session.user_id == user.id,
                Session.refresh_token_hash == refresh_hash,
                Session.revoked_at.is_(None),
            ),
        )
        row = refresh_result.scalar_one_or_none()
        if row is not None:
            row.revoked_at = utcnow()
            revoked_count += 1
    else:
        try:
            access_payload = decode_token(token, expected_type="access")
            device_id = access_payload["device_id"]
            sessions_result = await session.execute(
                select(Session).where(
                    Session.user_id == user.id,
                    Session.device_id == device_id,
                    Session.revoked_at.is_(None),
                ),
            )
            for row in sessions_result.scalars().all():
                row.revoked_at = utcnow()
                revoked_count += 1
        except ValueError:
            pass

    await session.commit()
    return {"status": "ok", "revoked_sessions": revoked_count}


@app.delete("/auth/account")
async def auth_delete_account(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    """Permanently delete the authenticated user's account and all associated data.

    Hard-deletes every per-user row across all tables (Prompt 12 hardening
    extends the original cascade to cover watch reports, playlists, LAN
    hubs, purchases, entitlements, account settings, and outbox rows). The
    user row is removed last so foreign keys never dangle mid-transaction.
    """
    user_id = user.id

    # Revoke active sessions first so any in-flight request from another
    # device gets a clean 401 instead of operating on a half-deleted user.
    sessions_result = await session.execute(
        select(Session).where(Session.user_id == user_id, Session.revoked_at.is_(None)),
    )
    for row in sessions_result.scalars().all():
        row.revoked_at = utcnow()

    # Per-user data cascade. Order is leaf-tables first so a foreign-key
    # constraint never blocks the user delete at the end.
    await session.execute(delete(WatchStateReport).where(WatchStateReport.user_id == user_id))
    await session.execute(delete(UserPlaylist).where(UserPlaylist.user_id == user_id))
    await session.execute(delete(LanHub).where(LanHub.user_id == user_id))
    await session.execute(delete(Purchase).where(Purchase.user_id == user_id))
    await session.execute(delete(Entitlement).where(Entitlement.user_id == user_id))
    await session.execute(delete(AccountSettings).where(AccountSettings.user_id == user_id))
    await session.execute(delete(EventOutbox).where(EventOutbox.user_id == user_id))
    # Pairing codes that the user has claimed reference them via
    # claimed_by_user_id; null those out so we don't drop other users'
    # codes by accident.
    await session.execute(
        PairingCode.__table__.update()
        .where(PairingCode.claimed_by_user_id == user_id)
        .values(claimed_by_user_id=None)
    )

    # Devices and activation events.
    await session.execute(delete(Device).where(Device.user_id == user_id))
    await session.execute(delete(DeviceActivationEvent).where(DeviceActivationEvent.user_id == user_id))
    # Now sessions and finally the user row itself.
    await session.execute(delete(Session).where(Session.user_id == user_id))
    await session.execute(delete(User).where(User.id == user_id))
    await session.commit()
    return {"status": "ok"}


@app.get("/me/export")
async def export_user_data(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    """GDPR-style data export for the authenticated user.

    Returns a JSON blob with everything the server holds for this user,
    minus secrets that the user shouldn't get back in cleartext from the
    backend (LAN auth secrets, refresh-token hashes, etc.). The shape is
    documented in docs/release-hardening.md.
    """
    user_id = user.id

    devices_rows = (await session.execute(
        select(Device).where(Device.user_id == user_id)
    )).scalars().all()
    sessions_rows = (await session.execute(
        select(Session).where(Session.user_id == user_id)
    )).scalars().all()
    watch_rows = (await session.execute(
        select(WatchStateReport).where(WatchStateReport.user_id == user_id)
    )).scalars().all()
    playlists_rows = (await session.execute(
        select(UserPlaylist).where(UserPlaylist.user_id == user_id)
    )).scalars().all()
    hubs_rows = (await session.execute(
        select(LanHub).where(LanHub.user_id == user_id)
    )).scalars().all()
    purchases_rows = (await session.execute(
        select(Purchase).where(Purchase.user_id == user_id)
    )).scalars().all()
    entitlements_rows = (await session.execute(
        select(Entitlement).where(Entitlement.user_id == user_id)
    )).scalars().all()
    settings_rows = (await session.execute(
        select(AccountSettings).where(AccountSettings.user_id == user_id)
    )).scalars().all()

    def _iso(dt) -> str | None:
        return dt.isoformat() if dt is not None else None

    return {
        "export_format_version": 1,
        "exported_at": utcnow().isoformat(),
        "user": {
            "id": user.id,
            "email": user.email,
            "is_verified": getattr(user, "is_verified", False),
            "created_at": _iso(user.created_at),
        },
        "devices": [
            {
                "id": d.id,
                "device_name": d.device_name,
                "device_type": d.device_type,
                "platform": d.platform,
                "installation_id": d.installation_id,
                "first_seen_at": _iso(d.first_seen_at),
                "last_seen_at": _iso(d.last_seen_at),
                "activated_at": _iso(d.activated_at),
                "removed_at": _iso(d.removed_at),
            }
            for d in devices_rows
        ],
        "sessions": [
            {
                "id": s.id,
                "device_id": s.device_id,
                "issued_at": _iso(getattr(s, "issued_at", None)),
                "expires_at": _iso(getattr(s, "expires_at", None)),
                "revoked_at": _iso(getattr(s, "revoked_at", None)),
            }
            for s in sessions_rows
        ],
        "watch_state_reports": [
            {
                "id": w.id,
                "device_id": w.device_id,
                "content_id": w.content_id,
                "provider": w.provider,
                "position_ms": w.position_ms,
                "reported_at": _iso(w.reported_at),
            }
            for w in watch_rows
        ],
        "playlists": [
            {
                "id": p.id,
                "name": p.name,
                "url": p.url,
                "epg_url": p.epg_url,
                "playlist_type": p.playlist_type,
                "server": p.server,
                "username": p.username,
                # password_enc deliberately omitted from export — the user
                # already knows their own password; echoing back ciphertext
                # serves no purpose.
                "created_at": _iso(p.created_at),
                "updated_at": _iso(p.updated_at),
            }
            for p in playlists_rows
        ],
        "lan_hubs": [
            {
                "id": h.id,
                "publisher_id": h.publisher_id,
                "device_label": h.device_label,
                "lan_host": h.lan_host,
                "lan_port": h.lan_port,
                "protocol_version": h.protocol_version,
                # auth_secret deliberately omitted — exporting an at-rest
                # ciphertext is meaningless; the live secret is fetched
                # via the dedicated /me/lan/hubs/{publisher_id}/secret
                # endpoint per session.
                "last_seen_at": _iso(h.last_seen_at),
                "created_at": _iso(h.created_at),
            }
            for h in hubs_rows
        ],
        "purchases": [
            {
                "id": p.id,
                "store": getattr(p, "store", None),
                "product_id": getattr(p, "product_id", None),
                "purchased_at": _iso(getattr(p, "purchased_at", None)),
            }
            for p in purchases_rows
        ],
        "entitlements": [
            {
                "id": e.id,
                "product_id": getattr(e, "product_id", None),
                "granted_at": _iso(getattr(e, "granted_at", None)),
                "expires_at": _iso(getattr(e, "expires_at", None)),
            }
            for e in entitlements_rows
        ],
        "account_settings": [
            {
                "id": s.id,
                "settings_json": getattr(s, "settings_json", None),
                "updated_at": _iso(getattr(s, "updated_at", None)),
            }
            for s in settings_rows
        ],
    }


@app.post("/pairing/code", response_model=PairingCodeResponse)
async def pairing_code(
    payload: PairingCodeRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PairingCodeResponse:
    registration = DeviceRegistration(
        installation_id=payload.installation_id,
        device_name=payload.device_name,
        device_type=payload.device_type,
        platform=payload.platform,
    )
    await get_or_create_device(session, registration, user_id=None)

    expires_at = utcnow() + timedelta(minutes=settings.pairing_code_ttl_minutes)
    pair_code = generate_pairing_code()
    for _ in range(5):
        existing_result = await session.execute(select(PairingCode).where(PairingCode.code == pair_code))
        if existing_result.scalar_one_or_none() is None:
            break
        pair_code = generate_pairing_code()

    code_row = PairingCode(
        code=pair_code,
        device_installation_id=payload.installation_id,
        expires_at=expires_at,
    )
    session.add(code_row)
    await session.commit()
    return PairingCodeResponse(code=pair_code, expires_at=expires_at)


@app.post("/pairing/claim", response_model=PairingClaimResponse)
async def pairing_claim(
    payload: PairingClaimRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PairingClaimResponse:
    code = normalize_code(payload.code)
    code_result = await session.execute(
        select(PairingCode).where(PairingCode.code == code),
    )
    code_row = code_result.scalar_one_or_none()
    if code_row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pairing code is invalid or expired")
    expires = code_row.expires_at.replace(tzinfo=timezone.utc) if code_row.expires_at.tzinfo is None else code_row.expires_at
    if expires < utcnow():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pairing code is invalid or expired")
    if code_row.claimed_at is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Pairing code already claimed")

    device_result = await session.execute(
        select(Device).where(Device.installation_id == code_row.device_installation_id),
    )
    device = device_result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target device not found")
    if device.user_id is not None and device.user_id != user.id:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Device is already assigned")

    device.user_id = user.id
    device.revoked_at = None
    device.last_seen_at = utcnow()
    code_row.claimed_by_user_id = user.id
    code_row.claimed_at = utcnow()

    await dispatch_event(
        session=session,
        registry=registry,
        user_id=user.id,
        target_device_id=device.id,
        event_type="PAIRING_CLAIMED",
        payload={"installation_id": device.installation_id, "device_name": device.device_name},
    )
    await session.commit()
    return PairingClaimResponse(status="claimed", device=as_device_response(device))


@app.post("/pairing/status", response_model=PairingStatusResponse)
async def pairing_status(
    payload: PairingStatusRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PairingStatusResponse:
    code = normalize_code(payload.code)
    code_result = await session.execute(select(PairingCode).where(PairingCode.code == code))
    code_row = code_result.scalar_one_or_none()
    if code_row is None or code_row.device_installation_id != payload.installation_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pairing code not found")

    expires = code_row.expires_at.replace(tzinfo=timezone.utc) if code_row.expires_at.tzinfo is None else code_row.expires_at
    if expires < utcnow():
        return PairingStatusResponse(status="expired")

    if code_row.claimed_at is None:
        return PairingStatusResponse(status="pending")

    device_result = await session.execute(
        select(Device).where(Device.installation_id == code_row.device_installation_id),
    )
    device = device_result.scalar_one_or_none()
    if device is None or device.user_id is None:
        return PairingStatusResponse(status="pending")

    user_result = await session.execute(select(User).where(User.id == device.user_id))
    user = user_result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    if code_row.consumed_at is None:
        code_row.consumed_at = utcnow()
        tokens = await issue_tokens_for_device(session, user, device)
        await session.commit()
        return PairingStatusResponse(
            status="claimed",
            paired_device=as_device_response(device),
            user=as_user_response(user),
            tokens=tokens,
        )

    return PairingStatusResponse(
        status="claimed",
        paired_device=as_device_response(device),
        user=as_user_response(user),
        tokens=None,
    )


@app.get("/me/pairings")
async def list_pairings(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
):
    """List paired devices for the current user."""
    current_device_id = None
    try:
        payload = decode_token(token, expected_type="access")
        current_device_id = payload.get("device_id")
    except ValueError:
        pass

    result = await session.execute(
        select(Device)
        .where(Device.user_id == user.id)
        .order_by(Device.last_seen_at.desc())
    )
    devices = list(result.scalars().all())

    pairings = []
    for d in devices:
        if d.id == current_device_id:
            continue
        pairings.append({
            "pairing_id": d.id,
            "device_id": d.id,
            "installation_id": d.installation_id,
            "device_name": d.device_name,
            "device_type": d.device_type,
            "platform": d.platform,
            "last_seen_at": d.last_seen_at.isoformat() if d.last_seen_at else None,
            "pairing_state": "revoked" if d.revoked_at else "paired",
            "revoked_at": d.revoked_at.isoformat() if d.revoked_at else None,
        })

    return {"pairings": pairings}


@app.post("/me/pairings/{pairing_id}/revoke")
async def revoke_pairing(
    pairing_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    """Revoke a device pairing."""
    result = await session.execute(
        select(Device).where(
            Device.id == pairing_id,
            Device.user_id == user.id,
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Pairing not found")

    if device.revoked_at is not None:
        return {"status": "already_revoked"}

    device.revoked_at = utcnow()

    # Revoke all sessions for this device
    session_result = await session.execute(
        select(Session).where(
            Session.device_id == device.id,
            Session.revoked_at.is_(None),
        )
    )
    for s in session_result.scalars().all():
        s.revoked_at = utcnow()

    await session.commit()
    return {"status": "ok"}


@app.get("/devices", response_model=list[DeviceResponse])
async def get_devices(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> list[DeviceResponse]:
    result = await session.execute(
        select(Device)
        .where(Device.user_id == user.id)
        .order_by(Device.last_seen_at.desc()),
    )
    return [as_device_response(device) for device in result.scalars().all()]


@app.post("/devices/{device_id}/revoke")
async def revoke_device(
    device_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    device_result = await session.execute(
        select(Device).where(Device.id == device_id, Device.user_id == user.id),
    )
    device = device_result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    device.revoked_at = utcnow()
    session_result = await session.execute(
        select(Session).where(Session.device_id == device.id, Session.revoked_at.is_(None)),
    )
    for row in session_result.scalars().all():
        row.revoked_at = utcnow()
    await session.commit()
    return {"status": "ok"}


@app.post("/events/search_push", response_model=EventDispatchResponse)
async def send_search_push(
    payload: SearchPushRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> EventDispatchResponse:
    try:
        token_payload = decode_token(token, expected_type="access")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    source_device_id = token_payload["device_id"]
    target_device = await get_authorized_target_device(
        session=session,
        user_id=user.id,
        target_device_id=payload.target_device_id,
    )
    event_payload = payload.payload.model_dump()
    if not event_payload.get("issued_by_device_id"):
        event_payload["issued_by_device_id"] = source_device_id

    outbox_entry = await dispatch_event(
        session=session,
        registry=registry,
        user_id=user.id,
        target_device_id=target_device.id,
        event_type="SEARCH_PUSH",
        payload=event_payload,
    )
    await session.commit()
    return EventDispatchResponse(
        status="queued",
        event_id=outbox_entry.id,
        target_device_id=target_device.id,
        event_type="SEARCH_PUSH",
    )


@app.post("/events/playback_intent", response_model=EventDispatchResponse)
async def send_playback_intent(
    payload: PlaybackIntentRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> EventDispatchResponse:
    try:
        token_payload = decode_token(token, expected_type="access")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    source_device_id = token_payload["device_id"]
    target_device = await get_authorized_target_device(
        session=session,
        user_id=user.id,
        target_device_id=payload.target_device_id,
    )
    event_payload = payload.payload.model_dump()
    if not event_payload.get("issued_by_device_id"):
        event_payload["issued_by_device_id"] = source_device_id

    outbox_entry = await dispatch_event(
        session=session,
        registry=registry,
        user_id=user.id,
        target_device_id=target_device.id,
        event_type="PLAYBACK_INTENT",
        payload=event_payload,
    )
    await session.commit()
    return EventDispatchResponse(
        status="queued",
        event_id=outbox_entry.id,
        target_device_id=target_device.id,
        event_type="PLAYBACK_INTENT",
    )


@app.post("/watch_state/report", response_model=WatchStateReportResponse)
async def report_watch_state(
    payload: WatchStateReportRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> WatchStateReportResponse:
    try:
        token_payload = decode_token(token, expected_type="access")
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    source_device_id = token_payload["device_id"]
    source_device_result = await session.execute(
        select(Device).where(
            Device.id == source_device_id,
            Device.user_id == user.id,
            Device.revoked_at.is_(None),
        ),
    )
    source_device = source_device_result.scalar_one_or_none()
    if source_device is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Source device not authorized")

    report_row = WatchStateReport(
        user_id=user.id,
        device_id=source_device.id,
        content_id=payload.content_id,
        provider=payload.provider,
        position_ms=payload.position_ms,
        reported_at=utcnow(),
    )
    session.add(report_row)
    await session.commit()
    return WatchStateReportResponse(status="ok", reported_at=report_row.reported_at)


@app.get("/me/watch_state/latest", response_model=WatchStateLatestResponse)
async def get_latest_watch_state(
    content_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> WatchStateLatestResponse:
    # Cross-device resume read. Returns the newest WatchStateReport row for
    # (user, content_id) across all of this user's devices. Callers merge
    # this against their local SQLDelight row and prefer whichever has the
    # greater reported_at / updatedAt.
    if not content_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="content_id is required")
    result = await session.execute(
        select(WatchStateReport)
        .where(WatchStateReport.user_id == user.id, WatchStateReport.content_id == content_id)
        .order_by(WatchStateReport.reported_at.desc())
        .limit(1),
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No watch state for content")
    return WatchStateLatestResponse(
        content_id=row.content_id,
        provider=row.provider,
        position_ms=row.position_ms,
        reported_at=row.reported_at,
        device_id=row.device_id,
    )


# ── Entitlement helpers ──


def as_entitlement_response(e: Entitlement) -> EntitlementResponse:
    return EntitlementResponse(
        key=e.entitlement_key,
        status=e.status,
        source_store=e.source_store,
        starts_at=e.starts_at,
        ends_at=e.ends_at,
    )


def as_purchase_response(p: Purchase) -> PurchaseResponse:
    return PurchaseResponse(
        id=p.id,
        store=p.store,
        product_id=p.product_id,
        purchase_type=p.purchase_type,
        verification_status=p.verification_status,
        purchased_at=p.purchased_at,
        created_at=p.created_at,
    )


# ── Account & Entitlement Endpoints ──


@app.get("/me", response_model=MeResponse)
async def get_me(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> MeResponse:
    entitlements = await get_user_entitlements(session, user.id)
    has_entitlement = resolve_premium_access(entitlements)
    # Device-aware: check if current device is activated
    device = await get_current_device(session, token, user.id)
    device_active = device is not None and device.is_premium_active if has_entitlement else False
    return MeResponse(
        user=as_user_response(user),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=has_entitlement and device_active,
    )


@app.get("/me/entitlements", response_model=EntitlementStateResponse)
async def get_my_entitlements(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> EntitlementStateResponse:
    entitlements = await get_user_entitlements(session, user.id)
    has_entitlement = resolve_premium_access(entitlements)
    device = await get_current_device(session, token, user.id)
    device_active = device is not None and device.is_premium_active if has_entitlement else False
    return EntitlementStateResponse(
        user=as_user_response(user),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=has_entitlement and device_active,
    )


@app.post("/devices/register", response_model=DeviceResponse)
async def register_device(
    payload: DeviceRegistration,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> DeviceResponse:
    device = await get_or_create_device(session, payload, user.id)
    await try_activate_device(session, user.id, device, has_entitlement=True)
    await session.commit()
    return as_device_response(device)


@app.post("/purchases/apple/verify", response_model=PurchaseVerifyResponse)
async def verify_apple_purchase(
    payload: AppleVerifyRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> PurchaseVerifyResponse:
    result = await apple_verifier.verify(
        transaction_jws=payload.transaction_jws,
        product_id=payload.product_id,
    )
    if not result.valid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Apple verification failed: {result.error}")

    try:
        purchase, entitlements = await process_verified_purchase(
            session=session,
            user_id=user.id,
            store="apple",
            platform=payload.platform,
            result=result,
        )
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e)) from e

    # Attempt device activation after purchase
    has_entitlement = resolve_premium_access(entitlements)
    device = await get_current_device(session, token, user.id)
    device_premium = False
    if device and has_entitlement:
        activation = await try_activate_device(session, user.id, device, has_entitlement)
        device_premium = activation["activated"]

    await session.commit()
    return PurchaseVerifyResponse(
        status="verified",
        purchase=as_purchase_response(purchase),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=device_premium,
    )


@app.post("/purchases/google/verify", response_model=PurchaseVerifyResponse)
async def verify_google_purchase(
    payload: GoogleVerifyRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> PurchaseVerifyResponse:
    result = await google_verifier.verify(
        product_id=payload.product_id,
        purchase_token=payload.purchase_token,
    )
    if not result.valid:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Google verification failed: {result.error}")

    try:
        purchase, entitlements = await process_verified_purchase(
            session=session,
            user_id=user.id,
            store="google_play",
            platform=payload.platform,
            result=result,
        )
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e)) from e

    has_entitlement = resolve_premium_access(entitlements)
    device = await get_current_device(session, token, user.id)
    device_premium = False
    if device and has_entitlement:
        activation = await try_activate_device(session, user.id, device, has_entitlement)
        device_premium = activation["activated"]

    await session.commit()
    return PurchaseVerifyResponse(
        status="verified",
        purchase=as_purchase_response(purchase),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=device_premium,
    )


@app.post("/purchases/amazon/verify", response_model=PurchaseVerifyResponse)
async def verify_amazon_purchase(
    payload: AmazonVerifyRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> PurchaseVerifyResponse:
    receipt_prefix = payload.receipt_id[:8]
    logger.info(
        "AMAZON_VERIFY: request user_id=%s platform=%s product_id=%s receipt_prefix=%s",
        user.id,
        payload.platform,
        payload.product_id,
        receipt_prefix,
    )
    result = await amazon_verifier.verify(
        receipt_id=payload.receipt_id,
        amazon_user_id=payload.amazon_user_id,
        product_id=payload.product_id,
    )
    if not result.valid:
        logger.warning(
            "AMAZON_VERIFY: failed user_id=%s product_id=%s receipt_prefix=%s error=%s",
            user.id,
            payload.product_id,
            receipt_prefix,
            result.error,
        )
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Amazon verification failed: {result.error}")

    try:
        purchase, entitlements = await process_verified_purchase(
            session=session,
            user_id=user.id,
            store="amazon",
            platform=payload.platform,
            result=result,
        )
    except ValueError as e:
        logger.warning(
            "AMAZON_VERIFY: conflict user_id=%s product_id=%s receipt_prefix=%s detail=%s",
            user.id,
            payload.product_id,
            receipt_prefix,
            str(e),
        )
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e)) from e

    has_entitlement = resolve_premium_access(entitlements)
    device = await get_current_device(session, token, user.id)
    device_premium = False
    if device and has_entitlement:
        activation = await try_activate_device(session, user.id, device, has_entitlement)
        device_premium = activation["activated"]

    await session.commit()
    logger.info(
        "AMAZON_VERIFY: success user_id=%s purchase_id=%s entitlement_count=%d premium_access=%s",
        user.id,
        purchase.id,
        len(entitlements),
        device_premium,
    )
    return PurchaseVerifyResponse(
        status="verified",
        purchase=as_purchase_response(purchase),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=device_premium,
    )


@app.post("/purchases/restore", response_model=EntitlementStateResponse)
async def restore_purchases(
    payload: RestorePurchasesRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> EntitlementStateResponse:
    """
    Restore: looks up existing verified purchases for this user.
    If none found, returns current entitlements (which may be empty).
    Device-aware: attempts device activation if entitlement exists.
    """
    logger.info(
        "PURCHASE_RESTORE: request user_id=%s store=%s platform=%s",
        user.id,
        payload.store,
        payload.platform,
    )
    entitlements = await get_user_entitlements(session, user.id)
    has_entitlement = resolve_premium_access(entitlements)

    # Device-aware restore
    device = await get_current_device(session, token, user.id)
    device_premium = False
    if device and has_entitlement:
        activation = await try_activate_device(session, user.id, device, has_entitlement)
        device_premium = activation["activated"]
        await session.commit()
    elif has_entitlement:
        device_premium = has_entitlement  # No device context, fall back to entitlement-only

    if entitlements:
        logger.info(
            "PURCHASE_RESTORE: success user_id=%s store=%s entitlement_count=%d premium_access=%s",
            user.id,
            payload.store,
            len(entitlements),
            device_premium,
        )
    else:
        logger.info(
            "PURCHASE_RESTORE: empty user_id=%s store=%s platform=%s",
            user.id,
            payload.store,
            payload.platform,
        )

    return EntitlementStateResponse(
        user=as_user_response(user),
        entitlements=[as_entitlement_response(e) for e in entitlements],
        premium_access=device_premium,
    )


@app.get("/purchases/history", response_model=list[PurchaseResponse])
async def purchase_history(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> list[PurchaseResponse]:
    result = await session.execute(
        select(Purchase)
        .where(Purchase.user_id == user.id)
        .order_by(Purchase.created_at.desc())
    )
    return [as_purchase_response(p) for p in result.scalars().all()]


@app.post("/auth/password-reset/request")
async def password_reset_request(
    payload: PasswordResetRequestPayload,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    """
    Scaffold for password reset. In production, this sends an email with a reset token.
    For v1, we log the intent and return success regardless (to not leak email existence).
    """
    normalized_email = payload.email.strip().lower()
    result = await session.execute(select(User).where(User.email == normalized_email))
    user = result.scalar_one_or_none()
    if user:
        # TODO: generate a time-limited reset token, store it, and email it
        logger.info("Password reset requested for user %s", user.id)
    return {"status": "ok", "message": "If that email exists, a reset link will be sent."}


@app.post("/auth/password-reset/confirm")
async def password_reset_confirm(
    payload: PasswordResetConfirmPayload,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    """
    Scaffold: validate the reset token and update the password.
    Full implementation requires a password_reset_tokens table.
    """
    # TODO: look up reset token, verify it's not expired, find user, update password
    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail="Password reset confirmation requires email service integration",
    )


# ── Device Governance Endpoints ──


@app.get("/me/access-state", response_model=AccessStateResponse)
async def get_access_state(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> AccessStateResponse:
    """Primary startup gating endpoint. Returns entitlement + device activation state."""
    device = await get_current_device(session, token, user.id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Device not found for current session")
    result = await _build_access_state(session, user, device, token)
    await session.commit()
    return result


@app.get("/me/devices", response_model=DeviceListResponse)
async def get_my_devices(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
    include_removed: Annotated[bool, Query()] = False,
) -> DeviceListResponse:
    """Returns user's devices with activation state. Current device identifiable."""
    current_device = await get_current_device(session, token, user.id)
    current_device_id = current_device.id if current_device else None

    if include_removed:
        result = await session.execute(
            select(Device).where(Device.user_id == user.id).order_by(Device.last_seen_at.desc())
        )
    else:
        result = await session.execute(
            select(Device).where(
                Device.user_id == user.id,
                Device.activated_at.is_not(None),
                Device.removed_at.is_(None),
                Device.revoked_at.is_(None),
            ).order_by(Device.last_seen_at.desc())
        )
    devices = list(result.scalars().all())

    active_count = sum(1 for d in devices if d.is_premium_active)
    swaps_used = await count_recent_swaps(session, user.id)

    return DeviceListResponse(
        devices=[as_managed_device(d, current_device_id) for d in devices],
        active_count=active_count,
        max_active=MAX_ACTIVE_DEVICES,
        swaps_remaining=max(0, 3 - swaps_used),
    )


@app.post("/me/devices/activate-current", response_model=DeviceActivateResponse)
async def activate_current_device(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> DeviceActivateResponse:
    """Attempt to activate the current device for premium access."""
    device = await get_current_device(session, token, user.id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Device not found for current session")

    entitlements = await get_user_entitlements(session, user.id)
    has_entitlement = resolve_premium_access(entitlements)
    result = await try_activate_device(session, user.id, device, has_entitlement)
    await session.commit()

    return DeviceActivateResponse(
        activated=result["activated"],
        reason=result["reason"],
        active_device_count=result["active_device_count"],
        stale_devices_pruned=result["stale_devices_pruned"],
        swaps_remaining=result["swaps_remaining"],
    )


@app.post("/me/devices/{device_id}/remove", response_model=DeviceRemoveResponse)
async def remove_managed_device(
    device_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> DeviceRemoveResponse:
    """Remove a device, freeing its premium slot."""
    result = await policy_remove_device(session, user.id, device_id)
    await session.commit()

    if result["reason"] == "not_found":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    return DeviceRemoveResponse(
        removed=result["removed"],
        reason=result["reason"],
        swaps_remaining=result["swaps_remaining"],
    )


@app.patch("/me/devices/{device_id}", response_model=ManagedDeviceResponse)
async def rename_managed_device(
    device_id: str,
    payload: DeviceRenameRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ManagedDeviceResponse:
    """Rename a device."""
    device = await policy_rename_device(session, user.id, device_id, payload.device_name)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
    await session.commit()
    return as_managed_device(device)


# ── Account settings sync ──


@app.get("/me/account-settings", response_model=AccountSettingsResponse)
async def get_account_settings(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> AccountSettingsResponse:
    """Get shared account settings for the current user."""
    result = await session.execute(
        select(AccountSettings).where(AccountSettings.user_id == user.id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        return AccountSettingsResponse(settings={}, updated_at=None)
    # Defense in depth: strip forbidden keys from response even if legacy data contains them.
    safe = {k: v for k, v in row.settings_json.items() if v is not None and not is_forbidden_key(k)}
    return AccountSettingsResponse(
        settings=safe,
        updated_at=row.updated_at.isoformat() if row.updated_at else None,
        updated_by_device_id=row.updated_by_device_id,
    )


@app.patch("/me/account-settings", response_model=AccountSettingsResponse)
async def patch_account_settings(
    payload: AccountSettingsPatchRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
    token: Annotated[str, Depends(oauth2_scheme)],
) -> AccountSettingsResponse:
    """Merge-patch shared account settings. Forbidden secret keys are silently stripped."""
    device_id = None
    try:
        token_payload = decode_token(token, expected_type="access")
        device_id = token_payload.get("device_id")
    except ValueError:
        pass

    # Write-time enforcement: strip forbidden keys before persistence.
    safe_settings = strip_forbidden_keys(payload.settings)

    result = await session.execute(
        select(AccountSettings).where(AccountSettings.user_id == user.id)
    )
    row = result.scalar_one_or_none()

    if row is None:
        current = {k: v for k, v in safe_settings.items() if v is not None}
        row = AccountSettings(
            user_id=user.id,
            settings_json=current,
            updated_by_device_id=device_id,
        )
        session.add(row)
    else:
        current = dict(row.settings_json) if row.settings_json else {}
        # Also scrub any forbidden keys already in the stored blob (opportunistic cleanup).
        current = {k: v for k, v in current.items() if not is_forbidden_key(k)}
        for key, value in safe_settings.items():
            if value is None:
                current.pop(key, None)
            else:
                current[key] = value
        row.settings_json = {k: v for k, v in current.items() if v is not None}
        row.updated_at = utcnow()
        row.updated_by_device_id = device_id
    await session.commit()
    await session.refresh(row)
    # Response also strips forbidden keys for defense in depth.
    safe_response = {k: str(v) for k, v in (row.settings_json or {}).items() if v is not None and not is_forbidden_key(k)}
    return AccountSettingsResponse(
        settings=safe_response,
        updated_at=row.updated_at.isoformat() if row.updated_at else None,
        updated_by_device_id=row.updated_by_device_id,
    )


@app.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    token: Annotated[str | None, Query()] = None,
    session: Annotated[AsyncSession, Depends(get_session)] = None,
):
    if token is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    try:
        payload = decode_token(token, expected_type="access")
    except ValueError:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    user_id = payload["sub"]
    token_device_id = payload["device_id"]
    await websocket.accept()

    registered_device_id: str | None = None
    try:
        register_message = await websocket.receive_json()
        if register_message.get("type") != "register":
            await websocket.send_json({"type": "error", "message": "Expected register message"})
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        requested_device_id = str(register_message.get("device_id", "")).strip()
        if requested_device_id != token_device_id:
            await websocket.send_json({"type": "error", "message": "Device mismatch"})
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        device_result = await session.execute(
            select(Device).where(Device.id == requested_device_id, Device.user_id == user_id),
        )
        device = device_result.scalar_one_or_none()
        if device is None or device.revoked_at is not None:
            await websocket.send_json({"type": "error", "message": "Device not authorized"})
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

        registered_device_id = requested_device_id
        device.last_seen_at = utcnow()
        await registry.register(user_id=user_id, device_id=registered_device_id, websocket=websocket)
        await deliver_pending_events(
            session=session,
            registry=registry,
            user_id=user_id,
            target_device_id=registered_device_id,
        )
        await session.commit()
        await websocket.send_json({"type": "ready"})

        while True:
            incoming = await websocket.receive_json()
            msg_type = incoming.get("type")
            if msg_type == "ping":
                await websocket.send_json({"type": "pong"})
            elif msg_type == "ack":
                event_id = incoming.get("event_id")
                if event_id:
                    outbox_result = await session.execute(
                        select(EventOutbox).where(EventOutbox.id == str(event_id)),
                    )
                    outbox_entry = outbox_result.scalar_one_or_none()
                    if outbox_entry is not None and outbox_entry.delivered_at is None:
                        outbox_entry.delivered_at = utcnow()
                    await session.commit()
            else:
                await websocket.send_json({"type": "error", "message": "Unknown message type"})

    except WebSocketDisconnect:
        pass
    finally:
        if registered_device_id is not None:
            await registry.unregister(user_id=user_id, device_id=registered_device_id)


# ── Playlist CRUD ──────────────────────────────────────────────


def _playlist_response(p: UserPlaylist) -> PlaylistResponse:
    return PlaylistResponse(
        id=p.id,
        name=p.name,
        url=p.url,
        epg_url=p.epg_url,
        playlist_type=p.playlist_type,
        server=p.server,
        username=p.username,
        has_password=bool(p.password_enc),
        created_at=p.created_at,
        updated_at=p.updated_at,
    )


@app.get("/me/playlists", response_model=list[PlaylistResponse])
async def get_playlists(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    result = await session.execute(
        select(UserPlaylist).where(UserPlaylist.user_id == user.id).order_by(UserPlaylist.name)
    )
    return [_playlist_response(p) for p in result.scalars().all()]


@app.put("/me/playlists/{playlist_id}", response_model=PlaylistResponse)
async def save_playlist(
    playlist_id: str,
    body: PlaylistSaveRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    result = await session.execute(
        select(UserPlaylist).where(UserPlaylist.id == playlist_id, UserPlaylist.user_id == user.id)
    )
    existing = result.scalar_one_or_none()
    now = utcnow()
    if existing:
        existing.name = body.name
        existing.url = body.url
        existing.epg_url = body.epg_url
        existing.playlist_type = body.playlist_type
        existing.server = body.server
        existing.username = body.username
        if body.password is not None:
            existing.password_enc = body.password  # TODO: encrypt at rest
        existing.updated_at = now
    else:
        existing = UserPlaylist(
            id=playlist_id,
            user_id=user.id,
            name=body.name,
            url=body.url,
            epg_url=body.epg_url,
            playlist_type=body.playlist_type,
            server=body.server,
            username=body.username,
            password_enc=body.password,
            created_at=now,
            updated_at=now,
        )
        session.add(existing)
    await session.commit()
    await session.refresh(existing)
    return _playlist_response(existing)


@app.delete("/me/playlists/{playlist_id}")
async def delete_playlist(
    playlist_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    await session.execute(
        delete(UserPlaylist).where(UserPlaylist.id == playlist_id, UserPlaylist.user_id == user.id)
    )
    await session.commit()
    return {"status": "ok"}


@app.get("/me/playlists/{playlist_id}/credentials")
async def get_playlist_credentials(
    playlist_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    result = await session.execute(
        select(UserPlaylist).where(UserPlaylist.id == playlist_id, UserPlaylist.user_id == user.id)
    )
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404)
    return {
        "username": p.username,
        "password": p.password_enc,  # TODO: decrypt at rest
    }


# ── LAN hub registry (Prompt 9B) ──────────────────────────────────────
#
# Same-user only. The publisher (desktop) calls POST to advertise its
# host:port + LAN auth secret; consumers (TV / mobile) call GET to
# discover hubs + GET /secret to fetch the auth secret. Hubs whose
# `last_seen_at` is older than LAN_HUB_STALE_SECONDS are filtered out
# of listings and the secret endpoint so a crashed publisher cannot
# strand authentication material in a "looks online" state.

LAN_HUB_STALE_SECONDS: int = 15 * 60


def _hub_is_fresh(hub) -> bool:  # type: ignore[no-untyped-def]
    # SQLite (used in tests) returns naive datetimes. Coerce both sides
    # to naive UTC so the subtraction never raises on offset mismatch.
    last_seen = hub.last_seen_at
    if last_seen.tzinfo is not None:
        last_seen = last_seen.replace(tzinfo=None)
    age = (datetime.utcnow() - last_seen).total_seconds()
    return age <= LAN_HUB_STALE_SECONDS


def _as_lan_hub_response(hub) -> LanHubResponse:  # type: ignore[no-untyped-def]
    last_seen = hub.last_seen_at
    if last_seen.tzinfo is None:
        # Treat naive timestamps as UTC. SQLite-backed test runs hit
        # this path; PostgreSQL keeps tz-aware rows untouched.
        last_seen = last_seen.replace(tzinfo=timezone.utc)
    return LanHubResponse(
        publisher_id=hub.publisher_id,
        device_label=hub.device_label,
        lan_host=hub.lan_host,
        lan_port=hub.lan_port,
        protocol_version=hub.protocol_version,
        published_at_epoch_ms=int(last_seen.timestamp() * 1000),
    )


@app.post("/me/lan/hubs", response_model=LanHubResponse)
async def publish_lan_hub(
    payload: LanHubPublishRequest,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LanHubResponse:
    """Insert or refresh the caller's hub. Same (user, publisher_id)
    rows are updated in-place so re-publishes are idempotent.
    """
    result = await session.execute(
        select(LanHub).where(
            LanHub.user_id == user.id,
            LanHub.publisher_id == payload.publisher_id,
        )
    )
    existing = result.scalar_one_or_none()
    now = utcnow()
    # Wrap the auth_secret before persistence. In production with no
    # wrap key configured, surface 503 so the operator sees the broken
    # config rather than silently storing plaintext.
    try:
        wrapped_secret = wrap_secret(payload.auth_secret)
    except WrapUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LAN secret wrapping not configured on this deployment",
        ) from exc
    if existing is None:
        hub = LanHub(
            user_id=user.id,
            publisher_id=payload.publisher_id,
            device_label=payload.device_label,
            lan_host=payload.lan_host,
            lan_port=payload.lan_port,
            protocol_version=payload.protocol_version,
            auth_secret=wrapped_secret,
            last_seen_at=now,
        )
        session.add(hub)
    else:
        existing.device_label = payload.device_label
        existing.lan_host = payload.lan_host
        existing.lan_port = payload.lan_port
        existing.protocol_version = payload.protocol_version
        existing.auth_secret = wrapped_secret
        existing.last_seen_at = now
        hub = existing
    await session.commit()
    await session.refresh(hub)
    return _as_lan_hub_response(hub)


@app.get("/me/lan/hubs", response_model=LanHubListResponse)
async def list_lan_hubs(
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LanHubListResponse:
    """List the caller's currently-fresh hubs. Cross-user listings are
    impossible by construction — the WHERE clause is fixed to the
    caller's user_id and stale rows are filtered out.
    """
    result = await session.execute(
        select(LanHub).where(LanHub.user_id == user.id)
    )
    rows = result.scalars().all()
    fresh = [r for r in rows if _hub_is_fresh(r)]
    return LanHubListResponse(hubs=[_as_lan_hub_response(r) for r in fresh])


@app.get("/me/lan/hubs/{publisher_id}/secret", response_model=LanHubSecretResponse)
async def get_lan_hub_secret(
    publisher_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> LanHubSecretResponse:
    """Return the auth secret for the caller's hub. Same-user gate is
    enforced via the WHERE clause. Stale rows return 410 so the
    consumer doesn't try to authenticate against a dead publisher.
    """
    result = await session.execute(
        select(LanHub).where(
            LanHub.user_id == user.id,
            LanHub.publisher_id == publisher_id,
        )
    )
    hub = result.scalar_one_or_none()
    if hub is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="hub not found")
    if not _hub_is_fresh(hub):
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="hub stale")
    try:
        secret_value = unwrap_secret(hub.auth_secret) or ""
    except WrapUnavailable as exc:
        # The DB has a wrapped value but this process has no key — config
        # bug. Surface explicitly rather than letting the consumer
        # 401-loop forever against a corrupt secret.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LAN secret wrap key missing or invalid",
        ) from exc
    return LanHubSecretResponse(publisher_id=hub.publisher_id, auth_secret=secret_value)


@app.delete("/me/lan/hubs/{publisher_id}")
async def delete_lan_hub(
    publisher_id: str,
    user: Annotated[User, Depends(get_current_user)],
    session: Annotated[AsyncSession, Depends(get_session)],
):
    """Remove the caller's hub. No-op (and 204) when the row doesn't
    exist — keeps shutdown handlers and sign-out hooks idempotent.
    """
    await session.execute(
        delete(LanHub).where(
            LanHub.user_id == user.id,
            LanHub.publisher_id == publisher_id,
        )
    )
    await session.commit()
    return {"ok": True}

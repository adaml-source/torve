from datetime import datetime
from pydantic import BaseModel, EmailStr, Field


class DeviceRegistration(BaseModel):
    installation_id: str = Field(min_length=8, max_length=128)
    device_name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(min_length=1, max_length=40)
    platform: str = Field(min_length=1, max_length=40)


class AuthRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=256)
    device: DeviceRegistration


class AuthLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=256)
    device: DeviceRegistration


class AuthRefreshRequest(BaseModel):
    refresh_token: str


class AuthLogoutRequest(BaseModel):
    refresh_token: str | None = None


class PairingCodeRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    installation_id: str = Field(min_length=8, max_length=128)
    device_name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(min_length=1, max_length=40)
    platform: str = Field(min_length=1, max_length=40)


class PairingClaimRequest(BaseModel):
    code: str = Field(min_length=4, max_length=12)
    device_id: str = Field(min_length=8, max_length=128)
    installation_id: str | None = Field(default=None, min_length=8, max_length=128)
    device_name: str | None = Field(default=None, min_length=1, max_length=120)
    device_type: str | None = Field(default=None, min_length=1, max_length=40)
    platform: str | None = Field(default=None, min_length=1, max_length=40)


class PairingStatusRequest(BaseModel):
    code: str = Field(min_length=4, max_length=12)
    installation_id: str = Field(min_length=8, max_length=128)


class UserResponse(BaseModel):
    id: str
    email: str
    is_verified: bool
    created_at: datetime


class DeviceResponse(BaseModel):
    id: str
    installation_id: str
    device_name: str
    device_type: str
    platform: str
    last_seen_at: datetime
    revoked_at: datetime | None = None


class TokensResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class AuthResponse(BaseModel):
    user: UserResponse
    device: DeviceResponse
    tokens: TokensResponse


class PairingCodeResponse(BaseModel):
    code: str
    expires_at: datetime


class PairingStatusResponse(BaseModel):
    status: str
    paired_device: DeviceResponse | None = None
    user: UserResponse | None = None
    tokens: TokensResponse | None = None


class PairingClaimResponse(BaseModel):
    status: str
    device: DeviceResponse


class HealthResponse(BaseModel):
    status: str
    database: str
    redis: str


class SearchPushPayload(BaseModel):
    query: str = Field(min_length=1, max_length=300)
    filters: dict[str, str] = Field(default_factory=dict)
    issued_by_device_id: str | None = None


class SearchPushRequest(BaseModel):
    target_device_id: str = Field(min_length=8, max_length=64)
    payload: SearchPushPayload


class PlaybackIntentPayload(BaseModel):
    content_id: str = Field(min_length=1, max_length=128)
    provider_target: str = Field(min_length=1, max_length=80)
    position_ms: int = Field(ge=0)
    media_type: str | None = Field(default=None, max_length=16)
    audio: str | None = Field(default=None, max_length=80)
    subtitles: str | None = Field(default=None, max_length=80)
    issued_by_device_id: str | None = None


class PlaybackIntentRequest(BaseModel):
    target_device_id: str = Field(min_length=8, max_length=64)
    payload: PlaybackIntentPayload


class EventDispatchResponse(BaseModel):
    status: str
    event_id: str
    target_device_id: str
    event_type: str


class WatchStateReportRequest(BaseModel):
    content_id: str = Field(min_length=1, max_length=128)
    provider: str = Field(min_length=1, max_length=80)
    position_ms: int = Field(ge=0)


class WatchStateReportResponse(BaseModel):
    status: str
    reported_at: datetime


# ── Purchase & Entitlement Schemas ──


class AppleVerifyRequest(BaseModel):
    transaction_jws: str = Field(min_length=10, description="StoreKit 2 JWS signed transaction")
    product_id: str = Field(min_length=1, max_length=128)
    platform: str = Field(default="ios", max_length=40)


class GoogleVerifyRequest(BaseModel):
    product_id: str = Field(min_length=1, max_length=128)
    purchase_token: str = Field(min_length=1)
    platform: str = Field(default="google_play_mobile", max_length=40)


class AmazonVerifyRequest(BaseModel):
    receipt_id: str = Field(min_length=1, max_length=512)
    amazon_user_id: str = Field(min_length=1, max_length=256)
    product_id: str = Field(min_length=1, max_length=128)
    platform: str = Field(default="amazon_fire_tv", max_length=40)


class RestorePurchasesRequest(BaseModel):
    store: str = Field(pattern="^(apple|google_play|amazon)$")
    platform: str = Field(max_length=40)
    receipt_data: str | None = None
    purchase_token: str | None = None
    amazon_user_id: str | None = None


class EntitlementResponse(BaseModel):
    key: str
    status: str
    source_store: str
    starts_at: datetime
    ends_at: datetime | None = None


class EntitlementStateResponse(BaseModel):
    user: UserResponse
    entitlements: list[EntitlementResponse]
    premium_access: bool


class PurchaseResponse(BaseModel):
    id: str
    store: str
    product_id: str
    purchase_type: str
    verification_status: str
    purchased_at: datetime | None = None
    created_at: datetime


class PurchaseVerifyResponse(BaseModel):
    status: str
    purchase: PurchaseResponse
    entitlements: list[EntitlementResponse]
    premium_access: bool


class PasswordResetRequestPayload(BaseModel):
    email: EmailStr


class PasswordResetConfirmPayload(BaseModel):
    token: str = Field(min_length=1)
    new_password: str = Field(min_length=8, max_length=256)


class MeResponse(BaseModel):
    user: UserResponse
    entitlements: list[EntitlementResponse]
    premium_access: bool


# ── Device Governance Schemas ──


class ManagedDeviceResponse(BaseModel):
    id: str
    device_name: str
    device_type: str
    platform: str
    is_current: bool = False
    is_active: bool = False
    last_seen_at: datetime
    activated_at: datetime | None = None
    removed_at: datetime | None = None
    removal_reason: str | None = None
    first_seen_at: datetime


class PremiumStateResponse(BaseModel):
    has_entitlement: bool
    premium_access: bool
    reason: str
    entitlements: list[EntitlementResponse]


class DeviceStateResponse(BaseModel):
    id: str
    name: str
    is_active: bool
    active_device_count: int
    max_active_devices: int
    platform: str
    device_type: str


class DeviceLimitResponse(BaseModel):
    cap_reached: bool
    swaps_remaining: int
    stale_devices_pruned: int
    active_devices: list[ManagedDeviceResponse] = []


class AccessStateResponse(BaseModel):
    user: UserResponse
    premium: PremiumStateResponse
    device: DeviceStateResponse
    device_limit: DeviceLimitResponse


class DeviceRemoveResponse(BaseModel):
    removed: bool
    reason: str
    swaps_remaining: int


class DeviceActivateResponse(BaseModel):
    activated: bool
    reason: str
    active_device_count: int
    stale_devices_pruned: int
    swaps_remaining: int


class DeviceRenameRequest(BaseModel):
    device_name: str = Field(min_length=1, max_length=120)


class DeviceListResponse(BaseModel):
    devices: list[ManagedDeviceResponse]
    active_count: int
    max_active: int
    swaps_remaining: int


class AccountSettingsResponse(BaseModel):
    settings: dict[str, str] = {}
    updated_at: str | None = None
    updated_by_device_id: str | None = None


class AccountSettingsPatchRequest(BaseModel):
    settings: dict[str, str | None]


# ── Playlist Schemas ──


class PlaylistSaveRequest(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    url: str = Field(default="", max_length=2000)
    epg_url: str | None = Field(default=None, max_length=2000)
    playlist_type: str = Field(default="m3u", pattern="^(m3u|xtream)$")
    server: str | None = Field(default=None, max_length=500)
    username: str | None = Field(default=None, max_length=200)
    password: str | None = Field(default=None, max_length=500)


class PlaylistResponse(BaseModel):
    id: str
    name: str
    url: str
    epg_url: str | None = None
    playlist_type: str
    server: str | None = None
    username: str | None = None
    has_password: bool = False
    created_at: datetime
    updated_at: datetime

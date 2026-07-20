import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, EmailStr, Field


# ── User ──────────────────────────────────────────────────────────────────────

class UserOut(BaseModel):
    id: uuid.UUID
    email: str
    display_name: str | None
    is_active: bool
    is_verified: bool
    has_lifetime_access: bool
    has_premium_access: bool
    created_at: datetime

    model_config = {"from_attributes": True}


class AccessStateOut(BaseModel):
    has_premium_access: bool
    access_tier: str  # "free"; paid tiers are historical only
    entitlement_type: str | None = None
    source: str | None = None
    granted_at: str | None = None
    expires_at: str | None = None
    auto_renew: bool | None = None
    is_device_activated: bool | None = None
    device_block_reason: str | None = None
    device_limit: int | None = None
    device_cap_override: int | None = None
    active_device_count: int | None = None
    # Legacy compatibility field; no paid entitlement can hide access.
    needs_verification: bool = False
    beta_access: dict | None = None


class MeResponse(BaseModel):
    """Canonical /me response: user profile + inline access state.

    Product access is free by default. Legacy paid fields are retained for
    backward compatibility only and are not an access source of truth.
    """
    # User profile fields
    id: uuid.UUID
    email: str
    display_name: str | None
    is_active: bool
    is_verified: bool
    created_at: datetime

    # Legacy boolean flags retained for backward compatibility.
    has_lifetime_access: bool
    has_premium_access: bool

    # Informational access state. Product access is free/default for active accounts.
    access_tier: str  # "free"; paid tiers are historical only
    entitlement_source: str | None = None
    entitlement_expires_at: str | None = None
    auto_renew: bool | None = None


# ── Profile ──────────────────────────────────────────────────────────────────

class ProfileUpdateRequest(BaseModel):
    display_name: str | None = Field(default=None, max_length=100)


class PasswordChangeRequest(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8)


# ── Auth requests ─────────────────────────────────────────────────────────────

class DeviceInfo(BaseModel):
    """Nested device object as sent by Android/iOS clients."""
    device_type: str | None = Field(default=None, max_length=20)
    device_name: str | None = Field(default=None, max_length=200)
    platform: str | None = Field(default=None, max_length=50)
    installation_id: str | None = Field(default=None, max_length=255)
    stable_device_id: str | None = Field(default=None, max_length=255)
    app_version: str | None = Field(default=None, max_length=50)


class SignupRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8)
    display_name: str | None = Field(default=None, max_length=100)
    # Flat device fields (backward compat)
    device_name: str | None = Field(default=None, max_length=200)
    platform: str | None = Field(default=None, max_length=50)
    device_type: str | None = Field(default=None, max_length=20)
    installation_id: str | None = Field(default=None, max_length=255)
    stable_device_id: str | None = Field(default=None, max_length=255)
    app_version: str | None = Field(default=None, max_length=50)
    # Nested device object (Android/iOS clients)
    device: DeviceInfo | None = None

    def resolved_device_type(self) -> str | None:
        return self.device_type or (self.device.device_type if self.device else None)

    def resolved_platform(self) -> str | None:
        return self.platform or (self.device.platform if self.device else None)

    def resolved_device_name(self) -> str | None:
        return self.device_name or (self.device.device_name if self.device else None)

    def resolved_installation_id(self) -> str | None:
        return self.installation_id or (self.device.installation_id if self.device else None)

    def resolved_stable_device_id(self) -> str | None:
        return self.stable_device_id or (self.device.stable_device_id if self.device else None)

    def resolved_app_version(self) -> str | None:
        return self.app_version or (self.device.app_version if self.device else None)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str
    # Flat device fields (backward compat)
    device_name: str | None = Field(default=None, max_length=200)
    platform: str | None = Field(default=None, max_length=50)
    device_type: str | None = Field(default=None, max_length=20)
    installation_id: str | None = Field(default=None, max_length=255)
    stable_device_id: str | None = Field(default=None, max_length=255)
    app_version: str | None = Field(default=None, max_length=50)
    # Nested device object (Android/iOS clients)
    device: DeviceInfo | None = None

    def resolved_device_type(self) -> str | None:
        return self.device_type or (self.device.device_type if self.device else None)

    def resolved_platform(self) -> str | None:
        return self.platform or (self.device.platform if self.device else None)

    def resolved_device_name(self) -> str | None:
        return self.device_name or (self.device.device_name if self.device else None)

    def resolved_installation_id(self) -> str | None:
        return self.installation_id or (self.device.installation_id if self.device else None)

    def resolved_stable_device_id(self) -> str | None:
        return self.stable_device_id or (self.device.stable_device_id if self.device else None)

    def resolved_app_version(self) -> str | None:
        return self.app_version or (self.device.app_version if self.device else None)


class RefreshRequest(BaseModel):
    refresh_token: str
    # Optional device fields so refresh can register/upsert a device
    device_name: str | None = Field(default=None, max_length=200)
    platform: str | None = Field(default=None, max_length=50)
    device_type: str | None = Field(default=None, max_length=20)
    installation_id: str | None = Field(default=None, max_length=255)
    stable_device_id: str | None = Field(default=None, max_length=255)
    app_version: str | None = Field(default=None, max_length=50)
    # Nested device object (Android/iOS clients)
    device: DeviceInfo | None = None

    def resolved_device_type(self) -> str | None:
        return self.device_type or (self.device.device_type if self.device else None)

    def resolved_platform(self) -> str | None:
        return self.platform or (self.device.platform if self.device else None)

    def resolved_device_name(self) -> str | None:
        return self.device_name or (self.device.device_name if self.device else None)

    def resolved_installation_id(self) -> str | None:
        return self.installation_id or (self.device.installation_id if self.device else None)

    def resolved_stable_device_id(self) -> str | None:
        return self.stable_device_id or (self.device.stable_device_id if self.device else None)

    def resolved_app_version(self) -> str | None:
        return self.app_version or (self.device.app_version if self.device else None)


# ── Auth responses ────────────────────────────────────────────────────────────

class TokensOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class AuthResponse(BaseModel):
    tokens: TokensOut
    user: UserOut
    device: "DeviceOut | None" = None


class RefreshTokensOut(BaseModel):
    access_token: str
    refresh_token: str | None = None  # New refresh token (rotation)
    token_type: str = "bearer"


class RefreshResponse(BaseModel):
    tokens: RefreshTokensOut
    user: UserOut
    device: "DeviceOut | None" = None


# ── Password reset ────────────────────────────────────────────────────────────

class PasswordResetRequest(BaseModel):
    email: EmailStr


class PasswordResetConfirm(BaseModel):
    token: str
    new_password: str = Field(min_length=8)


class MessageResponse(BaseModel):
    message: str


# ── Email verification ────────────────────────────────────────────────────────

class ResendVerificationRequest(BaseModel):
    email: EmailStr


# ── Devices ──────────────────────────────────────────────────────────────────

class DeviceRegisterRequest(BaseModel):
    device_type: str = Field(max_length=20)  # phone, tablet, tv, desktop
    platform: str | None = Field(default=None, max_length=50)
    display_name: str | None = Field(default=None, max_length=200)
    installation_id: str | None = Field(default=None, max_length=255)
    stable_device_id: str | None = Field(default=None, max_length=255)
    app_version: str | None = Field(default=None, max_length=50)


class DeviceOut(BaseModel):
    id: uuid.UUID
    device_type: str
    platform: str | None
    display_name: str | None
    installation_id: str | None
    stable_device_id: str | None = None
    app_version: str | None
    last_seen_at: datetime
    is_active: bool
    revoked_at: datetime | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class DeviceRenameRequest(BaseModel):
    display_name: str = Field(max_length=200)


class DeviceHeartbeatRequest(BaseModel):
    app_version: str | None = Field(default=None, max_length=50)


class ApiError(BaseModel):
    """Structured error contract for all user-facing API failures.

    Clients should switch on `code` (machine-readable, stable) and display
    `message` (safe, localizable). `detail` is optional internal context
    that should NOT be shown to end users.
    """
    code: str       # Stable machine-readable code (e.g. "device_cap_reached")
    message: str    # Safe user-facing message
    detail: str | None = None  # Internal debug context (never show to users)


class DeviceLimitError(BaseModel):
    code: str = "device_cap_reached"
    message: str = "You have reached your device limit."
    active_devices: list[DeviceOut]
    max_devices: int = 5


# ── Pairings ─────────────────────────────────────────────────────────────────

class PairingCreateRequest(BaseModel):
    controller_device_id: uuid.UUID
    target_device_id: uuid.UUID


class PairingOut(BaseModel):
    id: uuid.UUID
    controller_device_id: uuid.UUID
    target_device_id: uuid.UUID
    status: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


# ── Pairing codes ───────────────────────────────────────────────────────────

class PairingCodeRequest(BaseModel):
    device_id: uuid.UUID  # TV device generating the code


class PairingCodeOut(BaseModel):
    code: str
    expires_at: datetime
    target_device_id: uuid.UUID

    model_config = {"from_attributes": True}


class PairingClaimRequest(BaseModel):
    code: str = Field(max_length=10)
    device_id: uuid.UUID  # Phone device claiming the code


# ── Account settings ────────────────────────────────────────────────────────

class AccountSettingsOut(BaseModel):
    settings: dict
    version: int
    updated_at: datetime
    updated_by_device_id: uuid.UUID | None

    model_config = {"from_attributes": True}


class AccountSettingsPatch(BaseModel):
    settings: dict
    device_id: uuid.UUID | None = None


# ── Integrations ─────────────────────────────────────────────────────────

class IntegrationSaveRequest(BaseModel):
    """Save or update an integration.

    storage_mode:
      - "account": credentials encrypted and stored server-side, restored on login
      - "device_only": only metadata/config stored server-side, secrets stay on device

    credentials can be a dict (e.g. {"api_key": "abc"}) or a plain string
    (e.g. "abc"). If a string is provided, it is wrapped as {"value": "..."}.
    """
    integration_type: str = Field(max_length=50)
    credentials: dict | str = Field(default_factory=dict)
    config: dict = Field(default_factory=dict)
    display_identifier: str | None = Field(default=None, max_length=255)
    storage_mode: str = Field(default="account", pattern="^(account|device_only)$")

    def resolved_credentials(self) -> dict:
        if isinstance(self.credentials, str):
            return {"value": self.credentials} if self.credentials else {}
        return self.credentials


class IntegrationCredentialsPatchRequest(BaseModel):
    """Merge new keys into an existing integration's credentials.

    Use case: a recovery flow that wants to fill in a single missing
    field (e.g. Panda's management_token, when the original create flow
    only stored the manifest token) without nuking the rest of the
    blob, which a full PUT replacement would do.

    The new keys overwrite existing keys with the same name. Keys not
    in the patch are preserved. Empty patch is a no-op.
    """
    credentials: dict = Field(default_factory=dict)


class IntegrationOut(BaseModel):
    """Integration response. Never returns raw secrets."""
    id: uuid.UUID
    integration_type: str
    storage_mode: str
    display_identifier: str | None
    config: dict
    is_connected: bool
    has_credentials: bool  # True if server holds encrypted credentials (account mode)
    last_verified_at: datetime | None
    created_at: datetime
    updated_at: datetime


class IntegrationTestResult(BaseModel):
    integration_type: str
    success: bool
    message: str


# ── Playlists ────────────────────────────────────────────────────────────

class PlaylistSaveRequest(BaseModel):
    """Save or update a playlist backup. Xtream passwords are encrypted at rest."""
    playlist_id: str = Field(max_length=255)
    name: str = Field(max_length=255)
    playlist_type: str = Field(pattern="^(m3u|xtream)$")
    # M3U fields
    url: str | None = None
    # Optional XMLTV guide URL for M3U or Xtream sources
    epg_url: str | None = None
    # Xtream fields
    server: str | None = None
    username: str | None = None
    password: str | None = None  # Encrypted before storage, never returned


class PlaylistOut(BaseModel):
    """Playlist metadata response. Never returns raw Xtream password."""
    id: uuid.UUID
    playlist_id: str
    name: str
    playlist_type: str
    url: str | None
    epg_url: str | None
    server: str | None
    username: str | None
    has_password: bool  # True if Xtream password is stored
    created_at: datetime
    updated_at: datetime


class EpgValidateRequest(BaseModel):
    """Validate that an EPG URL is reachable and contains XMLTV programme data."""
    epg_url: str = Field(min_length=1, max_length=2048)


class EpgValidateResult(BaseModel):
    success: bool
    status: str
    message: str
    http_status: int | None = None
    content_type: str | None = None
    channel_count: int | None = None
    programme_count: int | None = None
    bytes_checked: int = 0


# ── Media Favorites ──────────────────────────────────────────────────────

class MediaFavoriteSaveRequest(BaseModel):
    media_type: Literal["movie", "series"]
    tmdb_id: int | None = None
    imdb_id: str | None = Field(default=None, max_length=32)
    title: str = Field(min_length=1, max_length=512)
    poster_url: str | None = Field(default=None, max_length=4000)
    backdrop_url: str | None = Field(default=None, max_length=4000)
    rating: float | None = None
    year: int | None = Field(default=None, ge=1800, le=3000)
    source_device_id: uuid.UUID | None = None


class MediaFavoriteOut(BaseModel):
    id: uuid.UUID
    media_key: str
    media_type: str
    tmdb_id: int | None
    imdb_id: str | None
    title: str
    poster_url: str | None
    backdrop_url: str | None
    rating: float | None
    year: int | None
    added_at: datetime
    updated_at: datetime
    source_device_id: uuid.UUID | None

    model_config = {"from_attributes": True}


class MediaFavoritesListResponse(BaseModel):
    favorites: list[MediaFavoriteOut]
    version: str | None
    updated_at: datetime | None


class MediaFavoriteMutationResponse(BaseModel):
    favorite: MediaFavoriteOut
    version: str
    updated_at: datetime


class MediaFavoriteDeleteResponse(BaseModel):
    media_key: str
    deleted: bool
    version: str
    updated_at: datetime


# ── Addons / Extensions ──────────────────────────────────────────────────

class AddonInstallRequest(BaseModel):
    """Install an addon by manifest URL."""
    manifest_url: str = Field(max_length=2000)
    # Optional: client can send parsed manifest metadata
    addon_id: str | None = Field(default=None, max_length=255)
    name: str | None = Field(default=None, max_length=255)
    description: str | None = Field(default=None, max_length=1000)
    version: str | None = Field(default=None, max_length=50)
    has_catalog: bool = False
    has_streams: bool = False
    installed_from: str = Field(default="app", pattern="^(app|web|sync)$")


class AddonUpdateRequest(BaseModel):
    """Update addon metadata or state."""
    name: str | None = Field(default=None, max_length=255)
    description: str | None = Field(default=None, max_length=1000)
    version: str | None = Field(default=None, max_length=50)
    has_catalog: bool | None = None
    has_streams: bool | None = None
    is_enabled: bool | None = None
    sort_order: int | None = None


class AddonOut(BaseModel):
    id: uuid.UUID
    manifest_url: str
    addon_id: str | None
    name: str | None
    description: str | None
    version: str | None
    logo_url: str | None = None
    has_catalog: bool
    has_streams: bool
    is_enabled: bool
    sort_order: int
    installed_from: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}

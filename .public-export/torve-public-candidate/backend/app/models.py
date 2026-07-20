import hashlib
import uuid
from datetime import datetime, timezone

from sqlalchemy import BigInteger, Boolean, CheckConstraint, Date, DateTime, Float, ForeignKey, Index, Integer, String, Text, event
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def _now() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    display_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    has_lifetime_access: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    has_premium_access: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # Per-user device cap override (null = use global MAX_DEVICES_PER_ACCOUNT)
    device_cap_override: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    refresh_tokens: Mapped[list["RefreshToken"]] = relationship(
        "RefreshToken", back_populates="user", cascade="all, delete-orphan"
    )


class UserContentPolicy(Base):
    """Per-user content policy state for Google Play compliance.

    Tracks adult eligibility, sensitive material enablement, and policy
    version acceptance. The backend is the source of truth — client
    toggles are validated server-side.
    """
    __tablename__ = "user_content_policies"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, unique=True,
    )
    # Age band: "unknown", "under_18", "adult"
    date_of_birth: Mapped[datetime | None] = mapped_column(Date, nullable=True)
    age_band: Mapped[str] = mapped_column(
        String(20), default="unknown", nullable=False
    )
    adult_eligible: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # Sensitive material access (explicit user opt-in, default OFF)
    sensitive_material_enabled: Mapped[bool] = mapped_column(
        Boolean, default=False, nullable=False
    )
    sensitive_material_enabled_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    sensitive_material_policy_version: Mapped[str | None] = mapped_column(
        String(50), nullable=True  # e.g. "2026-04-01-v1"
    )
    # Policy mode is NOT stored here — it's resolved per-request from
    # the X-Torve-Channel header. This prevents cross-device leakage.

    # Monotonic version counter — incremented on every policy-relevant change.
    # Clients use this to invalidate cached content, posters, and search results.
    policy_state_version: Mapped[int] = mapped_column(
        Integer, default=1, nullable=False
    )
    # Audit
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_ucp_user_id", "user_id", unique=True),
    )


class ContentOverride(Base):
    """Manual allowlist/denylist overrides for content or addon classification.

    Allows production support to override automatic classification for
    specific items by external provider ID or addon URL.
    """
    __tablename__ = "content_overrides"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    # What is being overridden: "content" or "addon"
    override_type: Mapped[str] = mapped_column(String(20), nullable=False)
    # The external key: provider content ID, addon manifest URL, etc.
    external_key: Mapped[str] = mapped_column(String(1000), nullable=False)
    # "allow" or "deny"
    action: Mapped[str] = mapped_column(String(10), nullable=False)
    # Optional human-readable note
    note: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    updated_by: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("uq_content_override_key", "override_type", "external_key", unique=True),
    )


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    platform: Mapped[str | None] = mapped_column(String(50), nullable=True)
    is_revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    # Token family for rotation: all tokens in a family share the same family_id.
    # On refresh, the old token is revoked and a new one is created with the same family_id.
    # If a revoked token is reused, ALL tokens in the family are revoked (compromise detection).
    family_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), nullable=True, index=True
    )

    user: Mapped["User"] = relationship("User", back_populates="refresh_tokens")

    __table_args__ = (
        Index("ix_refresh_tokens_user_id", "user_id"),
    )


class PasswordResetToken(Base):
    __tablename__ = "password_reset_tokens"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_password_reset_tokens_user_id", "user_id"),
    )


class EmailVerificationToken(Base):
    __tablename__ = "email_verification_tokens"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_email_verification_tokens_user_id", "user_id"),
    )


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    device_type: Mapped[str] = mapped_column(String(20), nullable=False)  # phone, tablet, tv, desktop
    platform: Mapped[str | None] = mapped_column(String(50), nullable=True)
    display_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    installation_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Hardware-stable identifier (ANDROID_ID / IDFV). Survives reinstalls.
    # Used as primary dedup key before falling back to installation_id.
    stable_device_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    user: Mapped["User"] = relationship("User")

    __table_args__ = (
        Index("ix_devices_user_id", "user_id"),
        Index("ix_devices_installation_id", "user_id", "installation_id"),
        Index("ix_devices_stable_device_id", "user_id", "stable_device_id"),
    )


class DevicePairing(Base):
    __tablename__ = "device_pairings"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    controller_device_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False
    )
    target_device_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False
    )
    status: Mapped[str] = mapped_column(
        String(20), default="active", nullable=False
    )  # active, revoked
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    controller_device: Mapped["Device"] = relationship("Device", foreign_keys=[controller_device_id])
    target_device: Mapped["Device"] = relationship("Device", foreign_keys=[target_device_id])

    __table_args__ = (
        Index("ix_device_pairings_user_id", "user_id"),
    )


class PairingCode(Base):
    __tablename__ = "pairing_codes"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    target_device_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False
    )
    code: Mapped[str] = mapped_column(String(10), nullable=False, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    claimed_by_device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    target_device: Mapped["Device"] = relationship("Device", foreign_keys=[target_device_id])
    claimed_by_device: Mapped["Device | None"] = relationship("Device", foreign_keys=[claimed_by_device_id])

    __table_args__ = (
        Index("ix_pairing_codes_user_id", "user_id"),
        Index("ix_pairing_codes_code", "code"),
    )


class UserIntegration(Base):
    """Account-scoped integration credentials and configuration.

    Stores API keys, service logins, OAuth tokens, and provider configs
    linked to the user account. Secrets are encrypted at rest via Fernet.
    """
    __tablename__ = "user_integrations"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # Integration type key, e.g. "real_debrid", "trakt", "tmdb", "orion", "simkl"
    integration_type: Mapped[str] = mapped_column(String(50), nullable=False)
    # "account" = encrypted secrets stored server-side, restored on login
    # "device_only" = metadata/config only, secrets remain on device
    storage_mode: Mapped[str] = mapped_column(String(20), default="account", nullable=False)
    # Human-readable label or identifier (e.g. username, email, masked key)
    display_identifier: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Encrypted credentials blob (JSON string, encrypted via Fernet)
    # Empty string for device_only mode
    encrypted_credentials: Mapped[str] = mapped_column(Text, nullable=False)
    # Non-secret config (e.g. preferred quality, region, feature flags)
    config: Mapped[dict] = mapped_column(JSONB, default=dict, nullable=False)
    # Connection status
    is_connected: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    last_verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    user: Mapped["User"] = relationship("User")

    __table_args__ = (
        Index("ix_user_integrations_user_id", "user_id"),
        Index("ix_user_integrations_user_type", "user_id", "integration_type", unique=True),
    )


class UserPlaylist(Base):
    """Account-scoped playlist backup for restore across sign-ins.

    Supports M3U (url-based) and Xtream (server/username/password).
    Xtream passwords are encrypted at rest via Fernet.
    """
    __tablename__ = "user_playlists"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # Client-assigned playlist ID for stable round-trip matching
    playlist_id: Mapped[str] = mapped_column(String(255), nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    # "m3u" or "xtream"
    playlist_type: Mapped[str] = mapped_column(String(20), nullable=False)
    # M3U fields
    url: Mapped[str | None] = mapped_column(Text, nullable=True)
    epg_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Xtream fields (password encrypted at rest)
    server: Mapped[str | None] = mapped_column(Text, nullable=True)
    username: Mapped[str | None] = mapped_column(String(255), nullable=True)
    encrypted_password: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    user: Mapped["User"] = relationship("User")

    __table_args__ = (
        Index("ix_user_playlists_user_id", "user_id"),
        Index("ix_user_playlists_user_playlist", "user_id", "playlist_id", unique=True),
    )


class UserMediaFavorite(Base):
    """Account-backed movie/series favorite synced across signed-in devices."""
    __tablename__ = "user_media_favorites"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    media_key: Mapped[str] = mapped_column(String(255), nullable=False)
    media_type: Mapped[str] = mapped_column(String(20), nullable=False)
    tmdb_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    imdb_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    title: Mapped[str] = mapped_column(String(512), nullable=False)
    poster_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    backdrop_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    rating: Mapped[float | None] = mapped_column(Float, nullable=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    added_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )
    source_device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
    )

    user: Mapped["User"] = relationship("User")
    source_device: Mapped["Device | None"] = relationship("Device")

    __table_args__ = (
        CheckConstraint(
            "media_type IN ('movie', 'series')",
            name="ck_user_media_favorites_media_type",
        ),
        Index("ix_user_media_favorites_user_id", "user_id"),
        Index("uq_user_media_favorite_key", "user_id", "media_key", unique=True),
    )


class AccountSettings(Base):
    __tablename__ = "account_settings"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, unique=True
    )
    settings: Mapped[dict] = mapped_column(JSONB, default=dict, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    updated_by_device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    user: Mapped["User"] = relationship("User")

    __table_args__ = (
        Index("ix_account_settings_user_id", "user_id", unique=True),
    )


class WebPayment(Base):
    """Records payments processed through Paddle web checkout."""
    __tablename__ = "web_payments"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    paddle_transaction_id: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    paddle_customer_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    product_id: Mapped[str] = mapped_column(String(255), nullable=False)
    price_id: Mapped[str] = mapped_column(String(255), nullable=False)
    amount: Mapped[str] = mapped_column(String(50), nullable=False)
    currency: Mapped[str] = mapped_column(String(10), nullable=False)
    discount_code: Mapped[str | None] = mapped_column(String(255), nullable=True)
    status: Mapped[str] = mapped_column(String(50), nullable=False)  # completed, refunded
    entitlement_granted: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    paddle_event_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    paddle_event_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    purchase_intent_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    refunded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_event_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    last_event_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    raw_payload: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_web_payments_user_id", "user_id"),
    )


class PromoCodeAudit(Base):
    """Internal audit record for issued Paddle discount codes."""
    __tablename__ = "promo_code_audit"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    paddle_discount_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    code: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    discount_type: Mapped[str] = mapped_column(String(20), nullable=False)  # percentage, flat
    discount_amount: Mapped[str] = mapped_column(String(50), nullable=False)
    usage_limit: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    intended_for: Mapped[str | None] = mapped_column(String(255), nullable=True)
    internal_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )


class UserEntitlement(Base):
    """Historical entitlement record retained for audit/support.

    entitlement_type values:
      - "lifetime_access": historical permanent purchase/support metadata
      - "subscription_monthly": historical subscription metadata
    """
    __tablename__ = "user_entitlements"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    entitlement_type: Mapped[str] = mapped_column(
        String(50), nullable=False  # "lifetime_access", "subscription_monthly"
    )
    source: Mapped[str] = mapped_column(
        String(50), nullable=False  # "paddle_web", "google_play", "amazon", "admin_grant"
    )
    source_ref: Mapped[str] = mapped_column(
        String(255), nullable=False  # Paddle txn ID, GP order ID, Amazon receipt, etc.
    )
    product_id: Mapped[str | None] = mapped_column(
        String(255), nullable=True  # Store SKU / product identifier
    )
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="active"
        # active, expired, revoked, paused, grace_period
    )
    granted_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True  # null for lifetime, set for subscriptions
    )
    auto_renew: Mapped[bool | None] = mapped_column(
        Boolean, nullable=True  # null for lifetime, true/false for subscriptions
    )
    last_verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # Historical Pattern B metadata: which device originated this grant. This
    # no longer gates product access, but remains useful for support/audit
    # history. NULL means the source device was unknown or predates the column.
    originating_device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_user_entitlements_user_id", "user_id"),
        Index("uq_entitlement_source_ref", "source", "source_ref", "entitlement_type", unique=True),
        Index("ix_user_entitlements_originating_device", "originating_device_id"),
    )


class DiscordAccountLink(Base):
    """One active Discord account link per Torve user."""

    __tablename__ = "discord_account_links"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    torve_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    discord_user_id: Mapped[str] = mapped_column(String(32), nullable=False)
    discord_username: Mapped[str] = mapped_column(String(255), nullable=False)
    discord_discriminator_or_global_name: Mapped[str | None] = mapped_column(
        String(255), nullable=True
    )
    linked_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    unlinked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_discord_account_links_torve_user_id", "torve_user_id"),
        Index("ix_discord_account_links_discord_user_id", "discord_user_id"),
        Index(
            "uq_discord_account_links_active_discord_user_id",
            "discord_user_id",
            unique=True,
            postgresql_where=unlinked_at.is_(None),
        ),
        Index(
            "uq_discord_account_links_active_torve_user_id",
            "torve_user_id",
            unique=True,
            postgresql_where=unlinked_at.is_(None),
        ),
    )


class DiscordBetaLinkCode(Base):
    """Short-lived one-time code that links a Discord modal to a Torve user."""

    __tablename__ = "discord_beta_link_codes"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    torve_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    code_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    consumed_by_discord_user_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_discord_beta_link_codes_torve_user_id", "torve_user_id"),
        Index("ix_discord_beta_link_codes_expires_at", "expires_at"),
        Index("ix_discord_beta_link_codes_consumed_at", "consumed_at"),
    )


class DiscordBetaApplication(Base):
    """Discord-submitted beta tester application."""

    __tablename__ = "discord_beta_applications"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    torve_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    discord_user_id: Mapped[str] = mapped_column(String(32), nullable=False)
    discord_username: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="submitted", nullable=False)
    devices_json: Mapped[list[str]] = mapped_column(JSONB, default=list, nullable=False)
    integrations_json: Mapped[list[str]] = mapped_column(JSONB, default=list, nullable=False)
    stability_preference: Mapped[str | None] = mapped_column(String(40), nullable=True)
    motivation: Mapped[str] = mapped_column(Text, nullable=False)
    accepted_beta_terms: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    accepted_no_credentials: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    staff_reviewer_discord_user_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    staff_reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    rejection_reason: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        CheckConstraint(
            "status IN ('submitted', 'approved', 'rejected', 'expired', 'revoked')",
            name="ck_discord_beta_applications_status",
        ),
        Index("ix_discord_beta_applications_discord_user_id", "discord_user_id"),
        Index("ix_discord_beta_applications_torve_user_id", "torve_user_id"),
        Index("ix_discord_beta_applications_status", "status"),
        Index("ix_discord_beta_applications_created_at", "created_at"),
        Index("ix_discord_beta_applications_stability_preference", "stability_preference"),
    )


class DiscordBetaApplicationDraft(Base):
    """Short-lived Discord application selections persisted across workers."""

    __tablename__ = "discord_beta_application_drafts"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    discord_user_id: Mapped[str] = mapped_column(String(32), nullable=False)
    discord_username: Mapped[str] = mapped_column(String(255), nullable=False)
    interaction_token_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    selected_devices_json: Mapped[list[str]] = mapped_column(JSONB, default=list, nullable=False)
    selected_integrations_json: Mapped[list[str]] = mapped_column(JSONB, default=list, nullable=False)
    stability_preference: Mapped[str | None] = mapped_column(String(40), nullable=True)
    current_step: Mapped[str] = mapped_column(String(40), default="devices", nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        CheckConstraint(
            "current_step IN ('devices', 'features', 'stability', 'modal', 'submitted')",
            name="ck_discord_beta_application_drafts_current_step",
        ),
        Index("ix_discord_beta_application_drafts_discord_user_id", "discord_user_id"),
        Index("ix_discord_beta_application_drafts_expires_at", "expires_at"),
        Index("ix_discord_beta_application_drafts_consumed_at", "consumed_at"),
        Index(
            "uq_discord_beta_application_drafts_active_discord_user",
            "discord_user_id",
            unique=True,
            postgresql_where=consumed_at.is_(None),
        ),
    )


class BetaAccessGrant(Base):
    """Limited beta/community metadata, separate from historical paid rows."""

    __tablename__ = "beta_access_grants"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    torve_user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    discord_user_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    source: Mapped[str] = mapped_column(String(30), default="discord_beta", nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        CheckConstraint("source IN ('discord_beta')", name="ck_beta_access_grants_source"),
        CheckConstraint(
            "status IN ('active', 'expired', 'revoked')",
            name="ck_beta_access_grants_status",
        ),
        Index("ix_beta_access_grants_torve_user_id", "torve_user_id"),
        Index("ix_beta_access_grants_discord_user_id", "discord_user_id"),
        Index("ix_beta_access_grants_status", "status"),
        Index("ix_beta_access_grants_expires_at", "expires_at"),
        Index(
            "uq_beta_access_grants_active_discord_beta_user",
            "torve_user_id",
            unique=True,
            postgresql_where=status == "active",
        ),
    )


class LifetimeGrantRecord(Base):
    """Persistent ledger of historical lifetime grants keyed by email.

    Survives account deletion for support/refund reconciliation. It is NOT
    linked to users.id — the email is the only identifier, so the cascade that
    wipes `user_entitlements` on delete doesn't touch this table.

    A row is written by every grant path (Paddle / Google Play / Amazon /
    rebate / admin) and marked `revoked_at` when a purchase is refunded
    or an admin revokes. Purge for GDPR is a separate hard-delete
    operation; normal deletion does not remove rows here.
    """
    __tablename__ = "lifetime_grants"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    email: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    source: Mapped[str] = mapped_column(
        String(50), nullable=False  # same values as UserEntitlement.source
    )
    source_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    product_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    granted_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    revoke_reason: Mapped[str | None] = mapped_column(String(255), nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)

    __table_args__ = (
        Index("ix_lifetime_grants_email", "email"),
        Index("uq_lifetime_grant_source_ref", "source", "source_ref", unique=True),
    )


class PurchaseIntent(Base):
    """Server-created purchase intent for safe account binding."""
    __tablename__ = "purchase_intents"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    product_id: Mapped[str] = mapped_column(String(255), nullable=False)
    price_id: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="pending"  # pending, completed, expired
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )


class StripeCustomer(Base):
    __tablename__ = "stripe_customers"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    stripe_customer_id: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    email_snapshot: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_stripe_customers_user_id", "user_id"),
    )


class StripeCheckoutSession(Base):
    __tablename__ = "stripe_checkout_sessions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    stripe_session_id: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    stripe_customer_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    price_id: Mapped[str] = mapped_column(String(255), nullable=False)
    purchase_type: Mapped[str] = mapped_column(String(20), nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(50), nullable=False)
    payment_status: Mapped[str | None] = mapped_column(String(50), nullable=True)
    stripe_subscription_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    stripe_payment_intent_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )


class StripeSubscription(Base):
    __tablename__ = "stripe_subscriptions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    stripe_customer_id: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    stripe_subscription_id: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    stripe_price_id: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(50), nullable=False)
    current_period_start: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    current_period_end: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    cancel_at_period_end: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    canceled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    latest_invoice_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )


class StripeWebhookEvent(Base):
    __tablename__ = "stripe_webhook_events"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    stripe_event_id: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    processing_status: Mapped[str] = mapped_column(String(20), nullable=False)
    failure_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )


class StripeLifetimePurchase(Base):
    __tablename__ = "stripe_lifetime_purchases"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    stripe_customer_id: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    stripe_checkout_session_id: Mapped[str | None] = mapped_column(
        String(255), unique=True, nullable=True, index=True
    )
    stripe_payment_intent_id: Mapped[str | None] = mapped_column(
        String(255), unique=True, nullable=True, index=True
    )
    stripe_charge_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    price_id: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    purchased_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    refunded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )


class StripeRefundRequest(Base):
    __tablename__ = "stripe_refund_requests"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, index=True
    )
    stripe_customer_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    purchase_type: Mapped[str] = mapped_column(String(20), nullable=False)
    target_kind: Mapped[str | None] = mapped_column(String(30), nullable=True)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    policy_reason: Mapped[str] = mapped_column(String(80), nullable=False)
    request_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    stripe_refund_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    stripe_payment_intent_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    stripe_charge_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    stripe_subscription_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    stripe_invoice_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    payment_fingerprint_hash: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    payment_method_type: Mapped[str | None] = mapped_column(String(50), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_stripe_refund_requests_user_status", "user_id", "status"),
        Index("ix_stripe_refund_requests_customer_status", "stripe_customer_id", "status"),
        Index("ix_stripe_refund_requests_fingerprint_status", "payment_fingerprint_hash", "status"),
    )


class RebateCode(Base):
    """Torve-controlled rebate code metadata; redemption does not grant access."""
    __tablename__ = "rebate_codes"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    code_hash: Mapped[str] = mapped_column(
        String(128), unique=True, nullable=False, index=True
    )
    code_prefix: Mapped[str] = mapped_column(
        String(20), nullable=False  # e.g. "TRV-LIFE-AB12" for admin display
    )
    campaign_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_by: Mapped[str | None] = mapped_column(String(255), nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    max_redemptions: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    redeemed_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    # Historical campaign duration metadata only. Redemption no longer grants
    # lifetime or subscription product access.
    grant_duration_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    allowed_email: Mapped[str | None] = mapped_column(String(255), nullable=True)
    allowed_email_domain: Mapped[str | None] = mapped_column(String(255), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    revoked_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    metadata_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )


class RebateRedemption(Base):
    """Record of a rebate code redemption attempt."""
    __tablename__ = "rebate_redemptions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    rebate_code_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("rebate_codes.id", ondelete="CASCADE"), nullable=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    redeemed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    source_ip_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_agent_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    result_status: Mapped[str] = mapped_column(
        String(50), nullable=False  # "success", "already_redeemed", etc.
    )

    __table_args__ = (
        Index("ix_rebate_redemptions_user_id", "user_id"),
        Index("ix_rebate_redemptions_code_id", "rebate_code_id"),
        Index("uq_rebate_user_success", "user_id", "result_status",
              unique=True, postgresql_where=Text("result_status = 'success'")),
    )


class UserAddon(Base):
    """User-installed extension/addon. Syncs across devices via account."""
    __tablename__ = "user_addons"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    manifest_url: Mapped[str] = mapped_column(Text, nullable=False)
    # Cached manifest metadata (populated on add/refresh)
    addon_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    description: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    logo_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Capabilities
    has_catalog: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    has_streams: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # User control
    is_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    # Content policy classification (cached on install/update)
    content_classification: Mapped[str | None] = mapped_column(
        String(30), nullable=True  # "safe", "sensitive_catalog", "blocked_illegal"
    )
    # Lifecycle
    installed_from: Mapped[str] = mapped_column(
        String(20), default="app", nullable=False  # "app", "web", "sync"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_user_addons_user_id", "user_id"),
        Index("uq_user_addon_manifest", "user_id", "manifest_url", unique=True),
    )


# ── Source Acceleration ─────────────────────────────────────────────────


class ResolveSuccessMemory(Base):
    """Per-user memory of successful source resolutions.

    Tracks which sources worked for a given content+provider combination,
    enabling fast-path replay and provider ranking. Rows are upserted on
    each successful playback and expire after retention_days.
    """
    __tablename__ = "resolve_success_memory"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # Content identification
    content_id: Mapped[str] = mapped_column(String(255), nullable=False)  # e.g. "tmdb:12345"
    season: Mapped[int | None] = mapped_column(Integer, nullable=True)
    episode: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Source identification
    provider_type: Mapped[str] = mapped_column(String(50), nullable=False)  # e.g. "real_debrid", "torrentio"
    source_key: Mapped[str] = mapped_column(Text, nullable=False)  # canonical source identifier
    source_key_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    infohash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    file_name: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    # Quality metadata
    quality: Mapped[str | None] = mapped_column(String(20), nullable=True)  # "4k", "1080p", "720p", etc.
    audio_flags: Mapped[str | None] = mapped_column(String(200), nullable=True)  # "atmos,dts-hd" etc.
    file_size: Mapped[int | None] = mapped_column(BigInteger, nullable=True)  # bytes
    # Success tracking
    success_count: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    last_success_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    last_failure_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_device_type: Mapped[str | None] = mapped_column(String(20), nullable=True)
    # Retention
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False  # set on write: now + retention_days
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )
    # Provenance discriminator — identifies the upstream emission path.
    # "USENET_NZBDAV" for NzbDAV rows. Future values: "DEBRID_CACHED",
    # "ADDON_DIRECT". None for legacy/baseline rows.
    provenance_kind: Mapped[str | None] = mapped_column(String(40), nullable=True)
    # NzbDAV-specific payload (only populated when provenance_kind=USENET_NZBDAV).
    nzbdav_candidate_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    nzbdav_hash_key: Mapped[str | None] = mapped_column(String(255), nullable=True)
    nzbdav_nzb_url: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    __table_args__ = (
        Index("ix_rsm_user_id", "user_id"),
        # Hot path: lookup by user + content (optionally filtered by season/episode)
        Index("ix_rsm_user_content", "user_id", "content_id"),
        # Hot path: lookup by user + content + provider
        Index("ix_rsm_user_content_provider", "user_id", "content_id", "provider_type"),
        # Upsert dedup key
        Index(
            "uq_rsm_user_content_source", "user_id", "content_id",
            "season", "episode", "provider_type", "source_key_hash",
            unique=True,
        ),
        # Expiry cleanup
        Index("ix_rsm_expires_at", "expires_at"),
    )


def _hash_source_key(source_key: str) -> str:
    return hashlib.sha256(source_key.encode("utf-8")).hexdigest()


@event.listens_for(ResolveSuccessMemory, "before_insert")
@event.listens_for(ResolveSuccessMemory, "before_update")
def _set_resolve_success_source_key_hash(_mapper, _connection, target: ResolveSuccessMemory) -> None:
    target.source_key_hash = _hash_source_key(target.source_key)


class StreamHandoffReference(Base):
    """Shared short-lived generic stream handoff reference.

    Uvicorn workers do not share process memory. Generic handoff tokens use
    this table as a ~300 second shared TTL cache so a URL minted by one worker
    can be played through another. The upstream URL is encrypted at rest and
    never encoded into the signed handoff token.
    """
    __tablename__ = "stream_handoff_references"

    stream_id: Mapped[str] = mapped_column(String(100), primary_key=True)
    upstream_url_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    device_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="CASCADE"), nullable=False
    )
    content_id: Mapped[str] = mapped_column(String(255), nullable=False)
    provider_type: Mapped[str] = mapped_column(String(50), nullable=False)
    source_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        Index("ix_stream_handoff_refs_user_id", "user_id"),
        Index("ix_stream_handoff_refs_expires_at", "expires_at"),
    )


class StreamPathTelemetry(Base):
    """Privacy-preserving client stream path adoption telemetry.

    Stores only coarse playback path categories and anonymized identifiers.
    Raw playback URLs, memory ids, source keys, tokens, credentials, and query
    payloads are intentionally not modeled here.
    """
    __tablename__ = "stream_path_telemetry"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    device_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    path_type: Mapped[str] = mapped_column(String(40), nullable=False)
    platform: Mapped[str | None] = mapped_column(String(50), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    distribution_channel: Mapped[str | None] = mapped_column(String(80), nullable=True)
    content_type: Mapped[str | None] = mapped_column(String(40), nullable=True)
    provider_category: Mapped[str | None] = mapped_column(String(80), nullable=True)
    source_category: Mapped[str | None] = mapped_column(String(80), nullable=True)
    device_category: Mapped[str | None] = mapped_column(String(40), nullable=True)
    generated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_stream_path_telemetry_created_at", "created_at"),
        Index("ix_stream_path_telemetry_path_type", "path_type"),
        Index("ix_stream_path_telemetry_platform", "platform"),
        Index("ix_stream_path_telemetry_provider", "provider_category"),
    )


class StreamMemoryCoverageSnapshot(Base):
    """Structured memory-id coverage samples for acceleration emissions."""
    __tablename__ = "stream_memory_coverage_snapshots"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    endpoint: Mapped[str] = mapped_column(String(40), nullable=False)
    user_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    device_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    content_type: Mapped[str | None] = mapped_column(String(40), nullable=True)
    eligible_candidate_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    memory_id_emitted_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    memory_id_missing_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    memory_id_coverage_ratio: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    missing_reason_counts: Mapped[dict] = mapped_column(JSONB, default=dict, nullable=False)
    provider_category_counts: Mapped[dict] = mapped_column(JSONB, default=dict, nullable=False)
    source_category_counts: Mapped[dict] = mapped_column(JSONB, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_stream_memory_coverage_created_at", "created_at"),
        Index("ix_stream_memory_coverage_endpoint", "endpoint"),
    )


class HashAvailabilityMemory(Base):
    """Per-user cache of hash availability observations from providers.

    Records whether a given infohash was observed as cached/available on a
    provider at a point in time. Short TTL (hours). Used to skip availability
    checks on known-cached hashes during resolve.
    """
    __tablename__ = "hash_availability_memory"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    provider_type: Mapped[str] = mapped_column(String(50), nullable=False)
    infohash: Mapped[str] = mapped_column(String(64), nullable=False)
    is_cached: Mapped[bool] = mapped_column(Boolean, nullable=False)
    # Observation metadata
    observed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    observation_source: Mapped[str] = mapped_column(
        String(50), default="resolve", nullable=False  # "resolve", "playback", "inventory_sync"
    )
    confidence: Mapped[str] = mapped_column(
        String(20), default="observed", nullable=False  # "observed", "inferred", "verified"
    )
    # Expiry
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False  # short TTL, e.g. now + 4 hours
    )

    __table_args__ = (
        Index("ix_ham_user_id", "user_id"),
        # Hot path: check if a hash is cached for a user+provider
        Index("ix_ham_user_provider_hash", "user_id", "provider_type", "infohash"),
        # Batch check: all cached hashes for a user+provider (non-expired)
        Index("ix_ham_user_provider_cached", "user_id", "provider_type", "is_cached"),
        # Upsert dedup
        Index(
            "uq_ham_user_provider_hash", "user_id", "provider_type", "infohash",
            unique=True,
        ),
        # Expiry cleanup
        Index("ix_ham_expires_at", "expires_at"),
    )


class ProviderInventorySnapshot(Base):
    """Per-user snapshot of items available in a provider's cloud/library.

    Captures what a user has in their Real-Debrid cloud, Premiumize transfers,
    AllDebrid downloads, etc. Synced periodically. Used to match content
    against known available items for instant playback.
    """
    __tablename__ = "provider_inventory_snapshots"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    provider_type: Mapped[str] = mapped_column(String(50), nullable=False)
    # Remote item identification
    remote_item_id: Mapped[str] = mapped_column(String(500), nullable=False)
    # Content metadata (normalized from file/folder names)
    normalized_title: Mapped[str | None] = mapped_column(String(500), nullable=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    season: Mapped[int | None] = mapped_column(Integer, nullable=True)
    episode: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Source identification
    infohash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    file_size: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    file_name: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    display_path: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    # Classification
    inventory_class: Mapped[str] = mapped_column(
        String(30), nullable=False  # "cloud", "download", "history", "library"
    )
    # Quality metadata
    quality: Mapped[str | None] = mapped_column(String(20), nullable=True)
    # Content policy classification (cached on ingest)
    content_classification: Mapped[str | None] = mapped_column(
        String(30), nullable=True  # "safe", "sensitive_catalog", "blocked_illegal"
    )
    # Lifecycle
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False  # e.g. now + 24 hours
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_pis_user_id", "user_id"),
        # Hot path: all items for user+provider
        Index("ix_pis_user_provider", "user_id", "provider_type"),
        # Hot path: match by infohash
        Index("ix_pis_user_provider_hash", "user_id", "provider_type", "infohash"),
        # Hot path: match by normalized title (content matching)
        Index("ix_pis_user_provider_title", "user_id", "provider_type", "normalized_title"),
        # Upsert dedup
        Index(
            "uq_pis_user_provider_item", "user_id", "provider_type", "remote_item_id",
            unique=True,
        ),
        # Expiry cleanup
        Index("ix_pis_expires_at", "expires_at"),
    )


# ── NzbDAV upstream integration ──────────────────────────────────────────


class NzbdavConfig(Base):
    """Per-user NzbDAV upstream configuration.

    Stores the base URL and encrypted API key for the user's NzbDAV server.
    Upstream semantics are NEVER leaked to clients — this model is internal
    plumbing only. API key is Fernet-encrypted at rest via encrypt_secret.

    Unique on user_id for this sprint. Follow-up TODO: support multiple
    NzbDAV accounts per user.
    """
    __tablename__ = "nzbdav_configs"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False, unique=True,
    )
    base_url: Mapped[str] = mapped_column(String(2000), nullable=False)
    api_key_encrypted: Mapped[str] = mapped_column(Text, nullable=False)
    is_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    last_tested_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_healthy_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    version_string: Mapped[str | None] = mapped_column(String(64), nullable=True)
    capabilities: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_nzbdav_configs_user_id", "user_id", unique=True),
    )


class WarmJob(Base):
    """Speculative warm job for a single (user, content, candidate hash) tuple.

    Tracks the upstream job through canonical states. Public API surfaces
    only the simplified ready|warming|failed projection.
    """
    __tablename__ = "nzbdav_warm_jobs"

    job_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    content_id: Mapped[str] = mapped_column(String(255), nullable=False)
    candidate_id: Mapped[str] = mapped_column(String(255), nullable=False)
    hash_key: Mapped[str] = mapped_column(String(255), nullable=False)
    state: Mapped[str] = mapped_column(String(30), nullable=False)
    phase: Mapped[str | None] = mapped_column(String(30), nullable=True)
    failure_code: Mapped[str | None] = mapped_column(String(50), nullable=True)
    failure_detail: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now, nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    stream_ready_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    __table_args__ = (
        Index("ix_nzbdav_warm_user_content_hash", "user_id", "content_id", "hash_key"),
        Index("ix_nzbdav_warm_user_state", "user_id", "state"),
        Index("ix_nzbdav_warm_expires_at", "expires_at"),
    )


class WatchStateReport(Base):
    """Per-device playback position reports for cross-device resume.

    Rows are append-only: each report is a fresh row, and `GET latest`
    returns the newest row for (user, content). device_id is optional
    on write; when omitted, the report endpoint may infer the device
    from the caller's installation_id. Null device_id is acceptable; it
    just means the response can't tell the client which device recorded
    the position.
    """
    __tablename__ = "watch_state_reports"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("devices.id", ondelete="SET NULL"), nullable=True
    )
    content_id: Mapped[str] = mapped_column(String(255), nullable=False)
    provider: Mapped[str] = mapped_column(String(80), nullable=False)
    position_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    reported_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        # Hot path: look up latest row for a given (user, content).
        Index("ix_watch_state_user_content_reported_at", "user_id", "content_id", "reported_at"),
        # Cleanup / device audit paths.
        Index("ix_watch_state_user_reported_at", "user_id", "reported_at"),
        Index("ix_watch_state_device_reported_at", "device_id", "reported_at"),
    )


class TransferSession(Base):
    """Sealed-envelope relay for intra-account device-to-device secret
    transfer.

    Sender (e.g. desktop B) and receiver (desktop A) authenticate as the
    same Torve user. Receiver creates a session with their X25519 public
    key + a TTL <= 10 minutes. Sender posts a SealedSecretsEnvelope JSON
    blob (encrypted client-side); receiver polls GET until state=delivered,
    then consumes (envelope is cleared on consume). The server treats
    envelope_json as opaque ciphertext — never decrypts, never inspects,
    never logs.

    State is derived (not stored as a column):
      - consumed_at IS NOT NULL              → consumed
      - expires_at < now() AND not consumed  → expired
      - envelope_json IS NOT NULL            → delivered
      - otherwise                            → pending
    """
    __tablename__ = "transfer_sessions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    receiver_device_id: Mapped[str] = mapped_column(String(128), nullable=False)
    receiver_device_name: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # Base64url-encoded X25519 public key (32 raw bytes → 43 chars without
    # padding; we accept up to 64 to be lenient about padding).
    receiver_public_key: Mapped[str] = mapped_column(String(64), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    envelope_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )
    delivered_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    consumed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    __table_args__ = (
        Index("ix_transfer_sessions_user_id", "user_id"),
        Index("ix_transfer_sessions_expires_at", "expires_at"),
    )


class PairingSigninCode(Base):
    """Anonymous code row for TV-QR sign-in.

    The TV creates the row anonymously with its installation_id; the
    signed-in phone scans the QR and claims it; the TV polls status and
    receives access+refresh tokens on the first claimed-poll. After the
    first claimed-poll, consumed_at is stamped and subsequent polls
    return "expired" — single-shot delivery.

    Lifecycle: pending → claimed → consumed (terminal, surfaces as
    "expired" to the client). Or pending → expired by TTL.

    Tokens are encrypted at rest via app.crypto.encrypt_secret. Decrypted
    only at the moment the receiving TV's poll fetches them.
    """
    __tablename__ = "pairing_signin_codes"

    code: Mapped[str] = mapped_column(String(10), primary_key=True)
    device_installation_id: Mapped[str] = mapped_column(String(255), nullable=False)
    device_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    device_type: Mapped[str] = mapped_column(String(20), nullable=False)
    platform: Mapped[str | None] = mapped_column(String(50), nullable=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    claimed_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=True,
    )
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    access_token_encrypted: Mapped[str | None] = mapped_column(Text, nullable=True)
    refresh_token_encrypted: Mapped[str | None] = mapped_column(Text, nullable=True)
    access_token_expires_in_s: Mapped[int | None] = mapped_column(Integer, nullable=True)
    claimed_device_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("devices.id", ondelete="SET NULL"),
        nullable=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

    __table_args__ = (
        Index("ix_pairing_signin_codes_expires_at", "expires_at"),
        Index("ix_pairing_signin_codes_installation", "device_installation_id"),
    )


class DesktopRelease(Base):
    """One row per published desktop build, consumed by GET /releases/appcast.xml."""
    __tablename__ = "desktop_releases"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    version: Mapped[str] = mapped_column(String(40), nullable=False, unique=True, index=True)
    msi_url: Mapped[str] = mapped_column(Text, nullable=False)
    sha256_hex: Mapped[str] = mapped_column(String(64), nullable=False)
    msi_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    release_notes_html: Mapped[str] = mapped_column(Text, nullable=False, default="")
    published_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    is_published: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, nullable=False
    )

import uuid
from datetime import datetime, timezone

from sqlalchemy import BigInteger, Boolean, Date, DateTime, ForeignKey, Index, Integer, String, Text
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
    """Canonical entitlement record. Source of truth for access grants.

    entitlement_type values:
      - "lifetime_access": permanent premium, never expires
      - "subscription_monthly": premium while active, has expires_at
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
    # Pattern B: which device originated this grant. Used to gate
    # cross-device access for unverified users — until they verify the
    # email, only the originating device can use the entitlement. NULL
    # for entitlements granted before this column existed (grandfathered
    # to all devices) and for sources where the device isn't knowable
    # (e.g. paddle_webhook from a server-to-server call).
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


class LifetimeGrantRecord(Base):
    """Persistent ledger of lifetime-access grants keyed by email.

    Survives account deletion so a user who re-signs up with the same
    email can have their lifetime entitlement auto-restored. NOT linked
    to users.id — the email is the only identifier, so the cascade that
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


class RebateCode(Base):
    """Torve-controlled rebate code for granting lifetime access."""
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
    # Grant duration: NULL → lifetime grant on redemption (existing default).
    # Positive integer N → subscription grant for N days from redemption time.
    # Admins pick this at code-creation time; redeem branches accordingly.
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
    source_key: Mapped[str] = mapped_column(String(500), nullable=False)  # canonical source identifier
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
            "season", "episode", "provider_type", "source_key",
            unique=True,
        ),
        # Expiry cleanup
        Index("ix_rsm_expires_at", "expires_at"),
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
    on write — the production JWT does not carry a device claim, so
    the client may or may not attach it. Null device_id is acceptable;
    it just means the response can't tell the client which device
    recorded the position.
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

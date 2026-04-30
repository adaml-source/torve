import uuid
from datetime import datetime, timezone
from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship
from .db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    email: Mapped[str] = mapped_column(String(320), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

    devices = relationship("Device", back_populates="user")
    sessions = relationship("Session", back_populates="user")


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (
        UniqueConstraint("installation_id", name="uq_devices_installation_id"),
        Index("ix_devices_user_id", "user_id"),
        Index("ix_devices_active", "user_id", "activated_at", "removed_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str | None] = mapped_column(String(36), ForeignKey("users.id"), nullable=True)
    installation_id: Mapped[str] = mapped_column(String(128), nullable=False)
    device_name: Mapped[str] = mapped_column(String(120), nullable=False)
    device_type: Mapped[str] = mapped_column(String(40), nullable=False)
    platform: Mapped[str] = mapped_column(String(40), nullable=False)
    app_version: Mapped[str | None] = mapped_column(String(40), nullable=True)
    os_version: Mapped[str | None] = mapped_column(String(80), nullable=True)
    first_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    activated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    removed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    removal_reason: Mapped[str | None] = mapped_column(String(40), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    metadata_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

    user = relationship("User", back_populates="devices")
    sessions = relationship("Session", back_populates="device")

    @property
    def is_premium_active(self) -> bool:
        return self.activated_at is not None and self.removed_at is None


class PairingCode(Base):
    __tablename__ = "pairing_codes"
    __table_args__ = (
        UniqueConstraint("code", name="uq_pairing_codes_code"),
        Index("ix_pairing_codes_device_installation_id", "device_installation_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    code: Mapped[str] = mapped_column(String(12), nullable=False)
    device_installation_id: Mapped[str] = mapped_column(String(128), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    claimed_by_user_id: Mapped[str | None] = mapped_column(String(36), ForeignKey("users.id"), nullable=True)
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)


class Session(Base):
    __tablename__ = "sessions"
    __table_args__ = (
        Index("ix_sessions_user_id", "user_id"),
        Index("ix_sessions_device_id", "device_id"),
        UniqueConstraint("refresh_token_hash", name="uq_sessions_refresh_token_hash"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False)
    refresh_token_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

    user = relationship("User", back_populates="sessions")
    device = relationship("Device", back_populates="sessions")


class EventOutbox(Base):
    __tablename__ = "event_outbox"
    __table_args__ = (
        Index("ix_event_outbox_user_target", "user_id", "target_device_id"),
        Index("ix_event_outbox_target_pending", "target_device_id", "delivered_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    target_device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False)
    type: Mapped[str] = mapped_column(String(80), nullable=False)
    payload_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class DeviceActivationEvent(Base):
    __tablename__ = "device_activation_events"
    __table_args__ = (
        Index("ix_device_activation_events_user_id", "user_id"),
        Index("ix_device_activation_events_device_id", "device_id"),
        Index("ix_device_activation_events_user_type_created", "user_id", "event_type", "created_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False)
    event_type: Mapped[str] = mapped_column(String(40), nullable=False)
    # event_type values: registered, activated, removed, auto_expired,
    #                    reactivated, denied_cap_reached, denied_swap_limit
    metadata_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)


class Purchase(Base):
    __tablename__ = "purchases"
    __table_args__ = (
        Index("ix_purchases_user_id", "user_id"),
        Index("ix_purchases_store_transaction", "store", "transaction_id"),
        UniqueConstraint("store", "original_transaction_id", name="uq_purchases_store_orig_txn"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    store: Mapped[str] = mapped_column(String(20), nullable=False)  # apple, google_play, amazon
    platform: Mapped[str] = mapped_column(String(40), nullable=False)
    product_id: Mapped[str] = mapped_column(String(128), nullable=False)
    purchase_type: Mapped[str] = mapped_column(String(20), nullable=False)  # lifetime, subscription
    transaction_id: Mapped[str | None] = mapped_column(String(256), nullable=True)
    original_transaction_id: Mapped[str | None] = mapped_column(String(256), nullable=True)
    purchase_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    receipt_reference: Mapped[str | None] = mapped_column(Text, nullable=True)
    raw_payload_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    currency: Mapped[str | None] = mapped_column(String(8), nullable=True)
    price_amount: Mapped[int | None] = mapped_column(BigInteger, nullable=True)  # cents
    purchased_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    verification_status: Mapped[str] = mapped_column(String(20), nullable=False, default="pending")
    revocation_reason: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)

    user = relationship("User")
    entitlements = relationship("Entitlement", back_populates="source_purchase")


class Entitlement(Base):
    __tablename__ = "entitlements"
    __table_args__ = (
        Index("ix_entitlements_user_id", "user_id"),
        UniqueConstraint("user_id", "entitlement_key", "source_purchase_id", name="uq_entitlements_user_key_purchase"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    entitlement_key: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="active")
    source_store: Mapped[str] = mapped_column(String(20), nullable=False)
    source_purchase_id: Mapped[str] = mapped_column(String(36), ForeignKey("purchases.id"), nullable=False)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)

    user = relationship("User")
    source_purchase = relationship("Purchase", back_populates="entitlements")


class EntitlementEvent(Base):
    __tablename__ = "entitlement_events"
    __table_args__ = (
        Index("ix_entitlement_events_user_id", "user_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    entitlement_key: Mapped[str] = mapped_column(String(64), nullable=False)
    event_type: Mapped[str] = mapped_column(String(20), nullable=False)
    source_purchase_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    metadata_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)


class AccountSettings(Base):
    __tablename__ = "account_settings"
    __table_args__ = (
        UniqueConstraint("user_id", name="uq_account_settings_user_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    settings_json: Mapped[dict] = mapped_column(JSON, nullable=False, default=dict)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)
    updated_by_device_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

    user = relationship("User")


class WatchStateReport(Base):
    __tablename__ = "watch_state_reports"
    __table_args__ = (
        Index("ix_watch_state_reports_user_reported_at", "user_id", "reported_at"),
        Index("ix_watch_state_reports_device_reported_at", "device_id", "reported_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False)
    content_id: Mapped[str] = mapped_column(String(128), nullable=False)
    provider: Mapped[str] = mapped_column(String(80), nullable=False)
    position_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    reported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)


class LanHub(Base):
    """LAN-library hub announced by a desktop instance.

    Prompt 9B contract:
      * One row per (user, publisher_id) so re-publishing updates rather
        than creates.
      * `auth_secret` is the in-process value the desktop's LAN HTTP
        server expects in `X-Torve-Lan-Auth`. Stored plaintext for
        same-user retrieval; production deployments should layer
        encryption-at-rest (TODO: KMS / fernet wrap).
      * `last_seen_at` updates on every publish — the listing endpoint
        filters out rows older than [STALE_THRESHOLD_SECONDS] so
        crashed-publisher entries fall out without manual cleanup.
      * Cross-account isolation is enforced at the query level — every
        endpoint filters by `user_id == current_user.id`.
    """

    __tablename__ = "lan_hubs"
    __table_args__ = (
        UniqueConstraint("user_id", "publisher_id", name="uq_lan_hubs_user_publisher"),
        Index("ix_lan_hubs_user_id", "user_id"),
        Index("ix_lan_hubs_user_last_seen", "user_id", "last_seen_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    publisher_id: Mapped[str] = mapped_column(String(64), nullable=False)
    device_label: Mapped[str] = mapped_column(String(120), nullable=False)
    lan_host: Mapped[str] = mapped_column(String(64), nullable=False)
    lan_port: Mapped[int] = mapped_column(Integer, nullable=False)
    protocol_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    # TODO(prompt-9b-prod): wrap with KMS / fernet before deploying.
    auth_secret: Mapped[str] = mapped_column(Text, nullable=False)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

    user = relationship("User")


class UserPlaylist(Base):
    """IPTV playlist saved to a user's Torve account for cross-device restore."""
    __tablename__ = "user_playlists"
    __table_args__ = (
        Index("ix_user_playlists_user_id", "user_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    url: Mapped[str] = mapped_column(Text, nullable=False, default="")
    epg_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    playlist_type: Mapped[str] = mapped_column(String(20), nullable=False, default="m3u")  # m3u or xtream
    server: Mapped[str | None] = mapped_column(Text, nullable=True)
    username: Mapped[str | None] = mapped_column(Text, nullable=True)
    password_enc: Mapped[str | None] = mapped_column(Text, nullable=True)  # encrypted Xtream password
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utcnow)

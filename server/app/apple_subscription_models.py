"""Apple-only subscription ledger; independent of free Android/Fire TV access."""
import uuid
from sqlalchemy import BigInteger, Boolean, ForeignKey, ForeignKeyConstraint, Index, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base


class AppleSubscriptionOwner(Base):
    __tablename__ = "apple_subscription_owners"
    environment: Mapped[str] = mapped_column(String(20), primary_key=True)
    original_transaction_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)


class AppleSubscriptionTransaction(Base):
    __tablename__ = "apple_subscription_transactions"
    environment: Mapped[str] = mapped_column(String(20), primary_key=True)
    transaction_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    original_transaction_id: Mapped[str] = mapped_column(String(64), nullable=False)
    product_id: Mapped[str] = mapped_column(String(255), nullable=False)
    expires_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    signed_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    revoked_at_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    is_upgraded: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    __table_args__ = (ForeignKeyConstraint(
        ["environment", "original_transaction_id"],
        ["apple_subscription_owners.environment", "apple_subscription_owners.original_transaction_id"],
        ondelete="CASCADE"),)


class AppleSubscriptionDevice(Base):
    __tablename__ = "apple_subscription_devices"
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    platform: Mapped[str] = mapped_column(String(10), nullable=False)
    display_name: Mapped[str] = mapped_column(String(100), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    last_seen_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    __table_args__ = (Index("ix_apple_subscription_devices_active", "user_id", "active"),)

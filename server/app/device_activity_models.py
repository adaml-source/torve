"""Account-owned, bounded usage counters. No content, credentials or network addresses."""
import uuid
from datetime import date, datetime
from sqlalchemy import BigInteger, Date, DateTime, ForeignKey, ForeignKeyConstraint, Integer, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base


class DeviceActivityTotal(Base):
    __tablename__ = "device_activity_totals"
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    device_key: Mapped[str] = mapped_column(String(80), primary_key=True)
    device_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("devices.id", ondelete="CASCADE"), nullable=True)
    platform: Mapped[str] = mapped_column(String(30), nullable=False)
    device_model: Mapped[str | None] = mapped_column(String(120), nullable=True)
    manufacturer: Mapped[str | None] = mapped_column(String(80), nullable=True)
    os_version: Mapped[str | None] = mapped_column(String(80), nullable=True)
    app_version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    app_build: Mapped[str | None] = mapped_column(String(50), nullable=True)
    first_activity_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_activity_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    foreground_ms: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    playback_ms: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    session_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class DeviceActivitySession(Base):
    __tablename__ = "device_activity_sessions"
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    device_key: Mapped[str] = mapped_column(String(80), primary_key=True)
    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    reported_foreground_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    reported_playback_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_report_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    __table_args__ = (ForeignKeyConstraint(["user_id", "device_key"], ["device_activity_totals.user_id", "device_activity_totals.device_key"], ondelete="CASCADE"),)


class DeviceActivityDay(Base):
    __tablename__ = "device_activity_days"
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    device_key: Mapped[str] = mapped_column(String(80), primary_key=True)
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    foreground_ms: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    playback_ms: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    __table_args__ = (ForeignKeyConstraint(["user_id", "device_key"], ["device_activity_totals.user_id", "device_activity_totals.device_key"], ondelete="CASCADE"),)

"""Measured device usage and non-sensitive device metadata."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0035"
down_revision = "0034"
branch_labels = None
depends_on = None


def keys():
    return [sa.Column("user_id", UUID(as_uuid=True), primary_key=True), sa.Column("device_key", sa.String(80), primary_key=True)]


def parent():
    return sa.ForeignKeyConstraint(["user_id", "device_key"], ["device_activity_totals.user_id", "device_activity_totals.device_key"], ondelete="CASCADE")


def upgrade():
    op.create_table("device_activity_totals", *keys(),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.Column("device_id", UUID(as_uuid=True), sa.ForeignKey("devices.id", ondelete="CASCADE"), nullable=True),
        sa.Column("platform", sa.String(30), nullable=False),
        sa.Column("device_model", sa.String(120), nullable=True),
        sa.Column("manufacturer", sa.String(80), nullable=True),
        sa.Column("os_version", sa.String(80), nullable=True),
        sa.Column("app_version", sa.String(50), nullable=True),
        sa.Column("app_build", sa.String(50), nullable=True),
        sa.Column("first_activity_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_activity_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("foreground_ms", sa.BigInteger(), nullable=False),
        sa.Column("playback_ms", sa.BigInteger(), nullable=False),
        sa.Column("session_count", sa.Integer(), nullable=False))
    op.create_table("device_activity_sessions", *keys(), parent(),
        sa.Column("session_id", UUID(as_uuid=True), primary_key=True),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("reported_foreground_ms", sa.BigInteger(), nullable=False),
        sa.Column("reported_playback_ms", sa.BigInteger(), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_report_at", sa.DateTime(timezone=True), nullable=False))
    op.create_table("device_activity_days", *keys(), parent(),
        sa.Column("day", sa.Date(), primary_key=True),
        sa.Column("foreground_ms", sa.BigInteger(), nullable=False),
        sa.Column("playback_ms", sa.BigInteger(), nullable=False))


def downgrade():
    op.drop_table("device_activity_days")
    op.drop_table("device_activity_sessions")
    op.drop_table("device_activity_totals")

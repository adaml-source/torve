"""Add device governance: activation columns on devices, device_activation_events table.

Revision ID: 0004_device_governance
Revises: 0003_purchases_entitlements
Create Date: 2026-03-07
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0004_device_governance"
down_revision = "0003_purchases_entitlements"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Add new columns to devices table
    op.add_column("devices", sa.Column("app_version", sa.String(40), nullable=True))
    op.add_column("devices", sa.Column("os_version", sa.String(80), nullable=True))
    op.add_column("devices", sa.Column("first_seen_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("activated_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("removed_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("removal_reason", sa.String(40), nullable=True))
    op.add_column(
        "devices",
        sa.Column("metadata_json", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
    )

    # Backfill first_seen_at from created_at
    op.execute("UPDATE devices SET first_seen_at = created_at WHERE first_seen_at IS NULL")
    op.alter_column("devices", "first_seen_at", nullable=False)

    # Create index for efficient active device queries
    op.create_index(
        "ix_devices_active",
        "devices",
        ["user_id", "activated_at", "removed_at"],
    )

    # Create device_activation_events table
    op.create_table(
        "device_activation_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("device_id", sa.String(36), sa.ForeignKey("devices.id"), nullable=False),
        sa.Column("event_type", sa.String(40), nullable=False),
        sa.Column("metadata_json", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_device_activation_events_user_id", "device_activation_events", ["user_id"])
    op.create_index("ix_device_activation_events_device_id", "device_activation_events", ["device_id"])
    op.create_index(
        "ix_device_activation_events_user_type_created",
        "device_activation_events",
        ["user_id", "event_type", "created_at"],
    )


def downgrade() -> None:
    op.drop_table("device_activation_events")
    op.drop_index("ix_devices_active", table_name="devices")
    op.drop_column("devices", "metadata_json")
    op.drop_column("devices", "removal_reason")
    op.drop_column("devices", "removed_at")
    op.drop_column("devices", "activated_at")
    op.drop_column("devices", "first_seen_at")
    op.drop_column("devices", "os_version")
    op.drop_column("devices", "app_version")

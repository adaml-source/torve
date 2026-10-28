"""Add stable_device_id to devices for hardware-stable deduplication.

ANDROID_ID (Android/Fire TV) and identifierForVendor (iOS) survive
app reinstalls, preventing duplicate device rows from consuming slots.

Revision ID: 0016
Revises: 0015
"""
from alembic import op
import sqlalchemy as sa

revision = "0016"
down_revision = "0015"


def upgrade() -> None:
    op.add_column("devices", sa.Column("stable_device_id", sa.String(255), nullable=True))
    op.create_index(
        "ix_devices_stable_device_id", "devices",
        ["user_id", "stable_device_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_devices_stable_device_id", table_name="devices")
    op.drop_column("devices", "stable_device_id")

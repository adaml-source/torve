"""Add originating_device_id to user_entitlements (Pattern B device-aware verification gate).

Revision ID: 0026
Revises: 0025

Pattern B for unverified-account-protection: when an unverified user
buys premium, the entitlement is bound to the device that originated
the purchase. Other devices can't use it until the user verifies their
email — at which point all devices get access.

Migration is purely additive: nullable column + index. Existing rows
get NULL, which the service layer interprets as "grandfathered — works
on any device regardless of verification". No backfill, no downtime.

DEPLOYMENT NOTES
----------------
Apply during normal deploys. Adds one nullable column + one index.
Safe against running production. `alembic upgrade head` from 0025
applies cleanly.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0026"
down_revision = "0025"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "user_entitlements",
        sa.Column(
            "originating_device_id",
            UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index(
        "ix_user_entitlements_originating_device",
        "user_entitlements",
        ["originating_device_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_user_entitlements_originating_device",
        table_name="user_entitlements",
    )
    op.drop_column("user_entitlements", "originating_device_id")

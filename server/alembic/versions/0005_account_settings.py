"""Add account_settings table for shared cross-device settings sync.

Revision ID: 0005_account_settings
Revises: 0004_device_governance
Create Date: 2026-03-18
"""
from alembic import op
import sqlalchemy as sa

revision = "0005_account_settings"
down_revision = "0004_device_governance"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "account_settings",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("settings_json", sa.JSON, nullable=False, server_default="{}"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_by_device_id", sa.String(36), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("user_id", name="uq_account_settings_user_id"),
    )


def downgrade() -> None:
    op.drop_table("account_settings")

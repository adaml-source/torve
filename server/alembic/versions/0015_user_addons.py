"""Add user_addons table for account-synced extension management.

Revision ID: 0014
Revises: 0013
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0015"
down_revision = "0014"


def upgrade() -> None:
    op.create_table(
        "user_addons",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("manifest_url", sa.Text, nullable=False),
        sa.Column("addon_id", sa.String(255), nullable=True),
        sa.Column("name", sa.String(255), nullable=True),
        sa.Column("description", sa.String(1000), nullable=True),
        sa.Column("version", sa.String(50), nullable=True),
        sa.Column("has_catalog", sa.Boolean, nullable=False, server_default=sa.text("false")),
        sa.Column("has_streams", sa.Boolean, nullable=False, server_default=sa.text("false")),
        sa.Column("is_enabled", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column("sort_order", sa.Integer, nullable=False, server_default=sa.text("0")),
        sa.Column("installed_from", sa.String(20), nullable=False, server_default="app"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_user_addons_user_id", "user_addons", ["user_id"])
    op.create_index("uq_user_addon_manifest", "user_addons", ["user_id", "manifest_url"], unique=True)


def downgrade() -> None:
    op.drop_index("uq_user_addon_manifest", table_name="user_addons")
    op.drop_index("ix_user_addons_user_id", table_name="user_addons")
    op.drop_table("user_addons")

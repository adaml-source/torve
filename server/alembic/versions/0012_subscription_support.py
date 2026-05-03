"""Add subscription support: has_premium_access, entitlement subscription fields.

Revision ID: 0012
Revises: 0011
"""
from alembic import op
import sqlalchemy as sa

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Add has_premium_access to users, default matching has_lifetime_access
    op.add_column("users", sa.Column("has_premium_access", sa.Boolean(), nullable=False, server_default="false"))

    # Sync has_premium_access with existing has_lifetime_access values
    op.execute("UPDATE users SET has_premium_access = has_lifetime_access")

    # Add subscription fields to user_entitlements
    op.add_column("user_entitlements", sa.Column("product_id", sa.String(255), nullable=True))
    op.add_column("user_entitlements", sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("user_entitlements", sa.Column("auto_renew", sa.Boolean(), nullable=True))
    op.add_column("user_entitlements", sa.Column("last_verified_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("user_entitlements", "last_verified_at")
    op.drop_column("user_entitlements", "auto_renew")
    op.drop_column("user_entitlements", "expires_at")
    op.drop_column("user_entitlements", "product_id")
    op.drop_column("users", "has_premium_access")

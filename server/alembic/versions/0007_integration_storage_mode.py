"""Add storage_mode column to user_integrations.

Revision ID: 0007
Revises: 0006
Create Date: 2026-03-22
"""

from alembic import op
import sqlalchemy as sa

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "user_integrations",
        sa.Column("storage_mode", sa.String(20), server_default="account", nullable=False),
    )


def downgrade() -> None:
    op.drop_column("user_integrations", "storage_mode")

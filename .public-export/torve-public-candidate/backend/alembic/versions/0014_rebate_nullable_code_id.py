"""Make rebate_redemptions.rebate_code_id nullable for failed attempt tracking.

Revision ID: 0014
Revises: 0013
"""
from alembic import op
import sqlalchemy as sa

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.alter_column("rebate_redemptions", "rebate_code_id", nullable=True)


def downgrade() -> None:
    op.alter_column("rebate_redemptions", "rebate_code_id", nullable=False)

"""Add grant_duration_days to rebate_codes for time-limited rebate grants.

NULL = lifetime grant (existing behaviour preserved for all pre-2026-04-27
codes). Positive integer N = subscription grant for N days from redemption
time. Admins now pick lifetime or duration when creating a code from the
/app/admin-rebate.html portal; redeem logic branches on this column.
"""
from alembic import op
import sqlalchemy as sa


revision = "0027"
down_revision = "0026"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "rebate_codes",
        sa.Column("grant_duration_days", sa.Integer(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("rebate_codes", "grant_duration_days")

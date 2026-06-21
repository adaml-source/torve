"""Add per-user device cap override column.

Revision ID: 0020
Revises: 0019
"""
from alembic import op
import sqlalchemy as sa

revision = "0020"
down_revision = "0019"


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column("device_cap_override", sa.Integer, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("users", "device_cap_override")

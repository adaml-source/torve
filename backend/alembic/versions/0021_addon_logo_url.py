"""Add logo_url column to user_addons.

Revision ID: 0021
Revises: 0020
"""
from alembic import op
import sqlalchemy as sa

revision = "0021"
down_revision = "0020"


def upgrade() -> None:
    op.add_column("user_addons", sa.Column("logo_url", sa.Text, nullable=True))


def downgrade() -> None:
    op.drop_column("user_addons", "logo_url")

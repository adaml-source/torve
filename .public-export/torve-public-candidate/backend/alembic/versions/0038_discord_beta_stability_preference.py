"""Add Discord beta stability preference.

Revision ID: 0038
Revises: 0037
"""
from alembic import op
import sqlalchemy as sa


revision = "0038"
down_revision = "0037"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "discord_beta_applications",
        sa.Column("stability_preference", sa.String(40), nullable=True),
    )
    op.create_index(
        "ix_discord_beta_applications_stability_preference",
        "discord_beta_applications",
        ["stability_preference"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_discord_beta_applications_stability_preference",
        table_name="discord_beta_applications",
    )
    op.drop_column("discord_beta_applications", "stability_preference")

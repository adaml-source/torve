"""Add Discord beta application drafts.

Revision ID: 0039
Revises: 0038
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0039"
down_revision = "0038"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "discord_beta_application_drafts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("discord_user_id", sa.String(length=32), nullable=False),
        sa.Column("discord_username", sa.String(length=255), nullable=False),
        sa.Column("interaction_token_hash", sa.String(length=64), nullable=True),
        sa.Column(
            "selected_devices_json",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "selected_integrations_json",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("stability_preference", sa.String(length=40), nullable=True),
        sa.Column("current_step", sa.String(length=40), nullable=False, server_default="devices"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "current_step IN ('devices', 'features', 'stability', 'modal', 'submitted')",
            name="ck_discord_beta_application_drafts_current_step",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_discord_beta_application_drafts_discord_user_id",
        "discord_beta_application_drafts",
        ["discord_user_id"],
    )
    op.create_index(
        "ix_discord_beta_application_drafts_expires_at",
        "discord_beta_application_drafts",
        ["expires_at"],
    )
    op.create_index(
        "ix_discord_beta_application_drafts_consumed_at",
        "discord_beta_application_drafts",
        ["consumed_at"],
    )
    op.create_index(
        "uq_discord_beta_application_drafts_active_discord_user",
        "discord_beta_application_drafts",
        ["discord_user_id"],
        unique=True,
        postgresql_where=sa.text("consumed_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_discord_beta_application_drafts_active_discord_user",
        table_name="discord_beta_application_drafts",
    )
    op.drop_index(
        "ix_discord_beta_application_drafts_consumed_at",
        table_name="discord_beta_application_drafts",
    )
    op.drop_index(
        "ix_discord_beta_application_drafts_expires_at",
        table_name="discord_beta_application_drafts",
    )
    op.drop_index(
        "ix_discord_beta_application_drafts_discord_user_id",
        table_name="discord_beta_application_drafts",
    )
    op.drop_table("discord_beta_application_drafts")

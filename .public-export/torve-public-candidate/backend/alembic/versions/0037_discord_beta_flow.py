"""Add Discord beta application and beta access grant tables.

Revision ID: 0037
Revises: 0036
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0037"
down_revision = "0036"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "discord_account_links",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("torve_user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("discord_user_id", sa.String(32), nullable=False),
        sa.Column("discord_username", sa.String(255), nullable=False),
        sa.Column("discord_discriminator_or_global_name", sa.String(255), nullable=True),
        sa.Column("linked_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("unlinked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_discord_account_links_torve_user_id", "discord_account_links", ["torve_user_id"])
    op.create_index("ix_discord_account_links_discord_user_id", "discord_account_links", ["discord_user_id"])
    op.create_index(
        "uq_discord_account_links_active_discord_user_id",
        "discord_account_links",
        ["discord_user_id"],
        unique=True,
        postgresql_where=sa.text("unlinked_at IS NULL"),
    )
    op.create_index(
        "uq_discord_account_links_active_torve_user_id",
        "discord_account_links",
        ["torve_user_id"],
        unique=True,
        postgresql_where=sa.text("unlinked_at IS NULL"),
    )

    op.create_table(
        "discord_beta_link_codes",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("torve_user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("code_hash", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("consumed_by_discord_user_id", sa.String(32), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_discord_beta_link_codes_torve_user_id", "discord_beta_link_codes", ["torve_user_id"])
    op.create_index("ix_discord_beta_link_codes_expires_at", "discord_beta_link_codes", ["expires_at"])
    op.create_index("ix_discord_beta_link_codes_consumed_at", "discord_beta_link_codes", ["consumed_at"])
    op.create_index("uq_discord_beta_link_codes_code_hash", "discord_beta_link_codes", ["code_hash"], unique=True)

    op.create_table(
        "discord_beta_applications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("torve_user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True),
        sa.Column("discord_user_id", sa.String(32), nullable=False),
        sa.Column("discord_username", sa.String(255), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("devices_json", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("integrations_json", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("motivation", sa.Text(), nullable=False),
        sa.Column("accepted_beta_terms", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("accepted_no_credentials", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("staff_reviewer_discord_user_id", sa.String(32), nullable=True),
        sa.Column("staff_reviewed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("rejection_reason", sa.String(1000), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "status IN ('submitted', 'approved', 'rejected', 'expired', 'revoked')",
            name="ck_discord_beta_applications_status",
        ),
    )
    op.create_index("ix_discord_beta_applications_discord_user_id", "discord_beta_applications", ["discord_user_id"])
    op.create_index("ix_discord_beta_applications_torve_user_id", "discord_beta_applications", ["torve_user_id"])
    op.create_index("ix_discord_beta_applications_status", "discord_beta_applications", ["status"])
    op.create_index("ix_discord_beta_applications_created_at", "discord_beta_applications", ["created_at"])

    op.create_table(
        "beta_access_grants",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("torve_user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("discord_user_id", sa.String(32), nullable=True),
        sa.Column("source", sa.String(30), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("starts_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("source IN ('discord_beta')", name="ck_beta_access_grants_source"),
        sa.CheckConstraint("status IN ('active', 'expired', 'revoked')", name="ck_beta_access_grants_status"),
    )
    op.create_index("ix_beta_access_grants_torve_user_id", "beta_access_grants", ["torve_user_id"])
    op.create_index("ix_beta_access_grants_discord_user_id", "beta_access_grants", ["discord_user_id"])
    op.create_index("ix_beta_access_grants_status", "beta_access_grants", ["status"])
    op.create_index("ix_beta_access_grants_expires_at", "beta_access_grants", ["expires_at"])
    op.create_index(
        "uq_beta_access_grants_active_discord_beta_user",
        "beta_access_grants",
        ["torve_user_id"],
        unique=True,
        postgresql_where=sa.text("status = 'active'"),
    )


def downgrade() -> None:
    op.drop_index("uq_beta_access_grants_active_discord_beta_user", table_name="beta_access_grants")
    op.drop_index("ix_beta_access_grants_expires_at", table_name="beta_access_grants")
    op.drop_index("ix_beta_access_grants_status", table_name="beta_access_grants")
    op.drop_index("ix_beta_access_grants_discord_user_id", table_name="beta_access_grants")
    op.drop_index("ix_beta_access_grants_torve_user_id", table_name="beta_access_grants")
    op.drop_table("beta_access_grants")

    op.drop_index("ix_discord_beta_applications_created_at", table_name="discord_beta_applications")
    op.drop_index("ix_discord_beta_applications_status", table_name="discord_beta_applications")
    op.drop_index("ix_discord_beta_applications_torve_user_id", table_name="discord_beta_applications")
    op.drop_index("ix_discord_beta_applications_discord_user_id", table_name="discord_beta_applications")
    op.drop_table("discord_beta_applications")

    op.drop_index("uq_discord_beta_link_codes_code_hash", table_name="discord_beta_link_codes")
    op.drop_index("ix_discord_beta_link_codes_consumed_at", table_name="discord_beta_link_codes")
    op.drop_index("ix_discord_beta_link_codes_expires_at", table_name="discord_beta_link_codes")
    op.drop_index("ix_discord_beta_link_codes_torve_user_id", table_name="discord_beta_link_codes")
    op.drop_table("discord_beta_link_codes")

    op.drop_index("uq_discord_account_links_active_torve_user_id", table_name="discord_account_links")
    op.drop_index("uq_discord_account_links_active_discord_user_id", table_name="discord_account_links")
    op.drop_index("ix_discord_account_links_discord_user_id", table_name="discord_account_links")
    op.drop_index("ix_discord_account_links_torve_user_id", table_name="discord_account_links")
    op.drop_table("discord_account_links")

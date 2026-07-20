"""Add content policy tables and classification columns.

- user_content_policies: per-user adult eligibility and sensitive material state
- content_overrides: manual allowlist/denylist for content and addon classification
- content_classification column on provider_inventory_snapshots
- content_classification column on user_addons

Revision ID: 0018
Revises: 0017
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0018"
down_revision = "0017"


def upgrade() -> None:
    # ── UserContentPolicy ───────────────────────────────────────────────
    op.create_table(
        "user_content_policies",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("date_of_birth", sa.Date, nullable=True),
        sa.Column("age_band", sa.String(20), nullable=False, server_default="unknown"),
        sa.Column("adult_eligible", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("sensitive_material_enabled", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("sensitive_material_enabled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("sensitive_material_policy_version", sa.String(50), nullable=True),
        sa.Column("content_policy_mode", sa.String(30), nullable=False, server_default="google_play"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_ucp_user_id", "user_content_policies", ["user_id"], unique=True)

    # ── ContentOverride ─────────────────────────────────────────────────
    op.create_table(
        "content_overrides",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("override_type", sa.String(20), nullable=False),
        sa.Column("external_key", sa.String(1000), nullable=False),
        sa.Column("action", sa.String(10), nullable=False),
        sa.Column("note", sa.String(1000), nullable=True),
        sa.Column("updated_by", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index(
        "uq_content_override_key", "content_overrides",
        ["override_type", "external_key"], unique=True,
    )

    # ── Classification column on inventory snapshots ────────────────────
    op.add_column(
        "provider_inventory_snapshots",
        sa.Column("content_classification", sa.String(30), nullable=True),
    )

    # ── Classification column on user addons ────────────────────────────
    op.add_column(
        "user_addons",
        sa.Column("content_classification", sa.String(30), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("user_addons", "content_classification")
    op.drop_column("provider_inventory_snapshots", "content_classification")
    op.drop_table("content_overrides")
    op.drop_table("user_content_policies")

"""Add rebate codes and redemptions tables.

Revision ID: 0013
Revises: 0012
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID, JSONB

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "rebate_codes",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("code_hash", sa.String(128), unique=True, nullable=False, index=True),
        sa.Column("code_prefix", sa.String(20), nullable=False),
        sa.Column("campaign_name", sa.String(255), nullable=True),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("created_by", sa.String(255), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("max_redemptions", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("redeemed_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("allowed_email", sa.String(255), nullable=True),
        sa.Column("allowed_email_domain", sa.String(255), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_reason", sa.String(500), nullable=True),
        sa.Column("metadata_json", JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
    )

    op.create_table(
        "rebate_redemptions",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("rebate_code_id", UUID(as_uuid=True), sa.ForeignKey("rebate_codes.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("redeemed_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("source_ip_hash", sa.String(64), nullable=True),
        sa.Column("user_agent_hash", sa.String(64), nullable=True),
        sa.Column("result_status", sa.String(50), nullable=False),
    )
    op.create_index("ix_rebate_redemptions_user_id", "rebate_redemptions", ["user_id"])
    op.create_index("ix_rebate_redemptions_code_id", "rebate_redemptions", ["rebate_code_id"])
    # Partial unique index: one successful redemption per user
    op.execute(
        "CREATE UNIQUE INDEX uq_rebate_user_success "
        "ON rebate_redemptions (user_id) "
        "WHERE result_status = 'success'"
    )


def downgrade() -> None:
    op.drop_table("rebate_redemptions")
    op.drop_table("rebate_codes")

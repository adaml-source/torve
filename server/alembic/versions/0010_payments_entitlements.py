"""Add web payments, promo code audit, and user lifetime access.

Revision ID: 0010
Revises: 0009
Create Date: 2026-03-23
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID, JSONB

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Add has_lifetime_access to users
    op.add_column("users", sa.Column("has_lifetime_access", sa.Boolean(), nullable=False, server_default="false"))

    # Web payments table
    op.create_table(
        "web_payments",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("paddle_transaction_id", sa.String(255), unique=True, nullable=False),
        sa.Column("paddle_customer_id", sa.String(255), nullable=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True),
        sa.Column("product_id", sa.String(255), nullable=False),
        sa.Column("price_id", sa.String(255), nullable=False),
        sa.Column("amount", sa.String(50), nullable=False),
        sa.Column("currency", sa.String(10), nullable=False),
        sa.Column("discount_code", sa.String(255), nullable=True),
        sa.Column("status", sa.String(50), nullable=False),
        sa.Column("entitlement_granted", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("paddle_event_type", sa.String(100), nullable=True),
        sa.Column("raw_payload", JSONB, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_web_payments_paddle_transaction_id", "web_payments", ["paddle_transaction_id"], unique=True)
    op.create_index("ix_web_payments_user_id", "web_payments", ["user_id"])

    # Promo code audit table
    op.create_table(
        "promo_code_audit",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("paddle_discount_id", sa.String(255), nullable=True),
        sa.Column("code", sa.String(100), nullable=False),
        sa.Column("discount_type", sa.String(20), nullable=False),
        sa.Column("discount_amount", sa.String(50), nullable=False),
        sa.Column("usage_limit", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("intended_for", sa.String(255), nullable=True),
        sa.Column("internal_note", sa.Text(), nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_promo_code_audit_code", "promo_code_audit", ["code"])


def downgrade() -> None:
    op.drop_table("promo_code_audit")
    op.drop_table("web_payments")
    op.drop_column("users", "has_lifetime_access")

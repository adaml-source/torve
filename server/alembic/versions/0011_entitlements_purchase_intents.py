"""Add entitlements, purchase intents, and payment status fields.

Revision ID: 0011
Revises: 0010
Create Date: 2026-03-23
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Add refund/reversal fields to web_payments
    op.add_column("web_payments", sa.Column("refunded_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("web_payments", sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("web_payments", sa.Column("last_event_type", sa.String(100), nullable=True))
    op.add_column("web_payments", sa.Column("last_event_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("web_payments", sa.Column("purchase_intent_id", UUID(as_uuid=True), nullable=True))
    op.add_column("web_payments", sa.Column("paddle_event_id", sa.String(255), nullable=True))

    # Entitlements table
    op.create_table(
        "user_entitlements",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("entitlement_type", sa.String(50), nullable=False),
        sa.Column("source", sa.String(50), nullable=False),
        sa.Column("source_ref", sa.String(255), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="active"),
        sa.Column("granted_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_user_entitlements_user_id", "user_entitlements", ["user_id"])
    op.create_index("uq_entitlement_source_ref", "user_entitlements", ["source", "source_ref", "entitlement_type"], unique=True)

    # Purchase intents table
    op.create_table(
        "purchase_intents",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("product_id", sa.String(255), nullable=False),
        sa.Column("price_id", sa.String(255), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    # Add disabled_at to promo_code_audit
    op.add_column("promo_code_audit", sa.Column("disabled_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("promo_code_audit", "disabled_at")
    op.drop_table("purchase_intents")
    op.drop_table("user_entitlements")
    op.drop_column("web_payments", "paddle_event_id")
    op.drop_column("web_payments", "purchase_intent_id")
    op.drop_column("web_payments", "last_event_at")
    op.drop_column("web_payments", "last_event_type")
    op.drop_column("web_payments", "revoked_at")
    op.drop_column("web_payments", "refunded_at")

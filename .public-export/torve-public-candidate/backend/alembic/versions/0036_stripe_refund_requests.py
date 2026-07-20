"""Add Stripe refund request ledger.

Revision ID: 0036
Revises: 0035
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0036"
down_revision = "0035"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "stripe_refund_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("stripe_customer_id", sa.String(255), nullable=True),
        sa.Column("purchase_type", sa.String(20), nullable=False),
        sa.Column("target_kind", sa.String(30), nullable=True),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("policy_reason", sa.String(80), nullable=False),
        sa.Column("request_reason", sa.String(500), nullable=True),
        sa.Column("stripe_refund_id", sa.String(255), nullable=True),
        sa.Column("stripe_payment_intent_id", sa.String(255), nullable=True),
        sa.Column("stripe_charge_id", sa.String(255), nullable=True),
        sa.Column("stripe_subscription_id", sa.String(255), nullable=True),
        sa.Column("stripe_invoice_id", sa.String(255), nullable=True),
        sa.Column("payment_fingerprint_hash", sa.String(128), nullable=True),
        sa.Column("payment_method_type", sa.String(50), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_refund_requests_user_id", "stripe_refund_requests", ["user_id"])
    op.create_index("ix_stripe_refund_requests_stripe_customer_id", "stripe_refund_requests", ["stripe_customer_id"])
    op.create_index("ix_stripe_refund_requests_stripe_refund_id", "stripe_refund_requests", ["stripe_refund_id"])
    op.create_index("ix_stripe_refund_requests_stripe_payment_intent_id", "stripe_refund_requests", ["stripe_payment_intent_id"])
    op.create_index("ix_stripe_refund_requests_stripe_charge_id", "stripe_refund_requests", ["stripe_charge_id"])
    op.create_index("ix_stripe_refund_requests_stripe_subscription_id", "stripe_refund_requests", ["stripe_subscription_id"])
    op.create_index("ix_stripe_refund_requests_stripe_invoice_id", "stripe_refund_requests", ["stripe_invoice_id"])
    op.create_index("ix_stripe_refund_requests_payment_fingerprint_hash", "stripe_refund_requests", ["payment_fingerprint_hash"])
    op.create_index("ix_stripe_refund_requests_user_status", "stripe_refund_requests", ["user_id", "status"])
    op.create_index("ix_stripe_refund_requests_customer_status", "stripe_refund_requests", ["stripe_customer_id", "status"])
    op.create_index("ix_stripe_refund_requests_fingerprint_status", "stripe_refund_requests", ["payment_fingerprint_hash", "status"])


def downgrade() -> None:
    op.drop_index("ix_stripe_refund_requests_fingerprint_status", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_customer_status", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_user_status", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_payment_fingerprint_hash", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_invoice_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_subscription_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_charge_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_payment_intent_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_refund_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_stripe_customer_id", table_name="stripe_refund_requests")
    op.drop_index("ix_stripe_refund_requests_user_id", table_name="stripe_refund_requests")
    op.drop_table("stripe_refund_requests")

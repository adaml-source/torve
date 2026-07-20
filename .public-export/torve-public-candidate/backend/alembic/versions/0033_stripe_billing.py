"""Add Stripe billing tables.

Revision ID: 0033
Revises: 0032
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0033"
down_revision = "0032"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "stripe_customers",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("stripe_customer_id", sa.String(255), nullable=False),
        sa.Column("email_snapshot", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_customers_user_id", "stripe_customers", ["user_id"])
    op.create_index("ix_stripe_customers_stripe_customer_id", "stripe_customers", ["stripe_customer_id"], unique=True)

    op.create_table(
        "stripe_checkout_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("stripe_session_id", sa.String(255), nullable=False),
        sa.Column("stripe_customer_id", sa.String(255), nullable=True),
        sa.Column("price_id", sa.String(255), nullable=False),
        sa.Column("purchase_type", sa.String(20), nullable=False),
        sa.Column("mode", sa.String(20), nullable=False),
        sa.Column("status", sa.String(50), nullable=False),
        sa.Column("payment_status", sa.String(50), nullable=True),
        sa.Column("stripe_subscription_id", sa.String(255), nullable=True),
        sa.Column("stripe_payment_intent_id", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_checkout_sessions_user_id", "stripe_checkout_sessions", ["user_id"])
    op.create_index("ix_stripe_checkout_sessions_stripe_session_id", "stripe_checkout_sessions", ["stripe_session_id"], unique=True)
    op.create_index("ix_stripe_checkout_sessions_stripe_customer_id", "stripe_checkout_sessions", ["stripe_customer_id"])
    op.create_index("ix_stripe_checkout_sessions_stripe_subscription_id", "stripe_checkout_sessions", ["stripe_subscription_id"])
    op.create_index("ix_stripe_checkout_sessions_stripe_payment_intent_id", "stripe_checkout_sessions", ["stripe_payment_intent_id"])

    op.create_table(
        "stripe_subscriptions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("stripe_customer_id", sa.String(255), nullable=False),
        sa.Column("stripe_subscription_id", sa.String(255), nullable=False),
        sa.Column("stripe_price_id", sa.String(255), nullable=False),
        sa.Column("status", sa.String(50), nullable=False),
        sa.Column("current_period_start", sa.DateTime(timezone=True), nullable=True),
        sa.Column("current_period_end", sa.DateTime(timezone=True), nullable=True),
        sa.Column("cancel_at_period_end", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("canceled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("latest_invoice_id", sa.String(255), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_subscriptions_user_id", "stripe_subscriptions", ["user_id"])
    op.create_index("ix_stripe_subscriptions_stripe_customer_id", "stripe_subscriptions", ["stripe_customer_id"])
    op.create_index("ix_stripe_subscriptions_stripe_subscription_id", "stripe_subscriptions", ["stripe_subscription_id"], unique=True)

    op.create_table(
        "stripe_webhook_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("stripe_event_id", sa.String(255), nullable=False),
        sa.Column("event_type", sa.String(100), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("processing_status", sa.String(20), nullable=False),
        sa.Column("failure_reason", sa.String(500), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_webhook_events_stripe_event_id", "stripe_webhook_events", ["stripe_event_id"], unique=True)

    op.create_table(
        "stripe_lifetime_purchases",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("stripe_customer_id", sa.String(255), nullable=False),
        sa.Column("stripe_checkout_session_id", sa.String(255), nullable=True),
        sa.Column("stripe_payment_intent_id", sa.String(255), nullable=True),
        sa.Column("stripe_charge_id", sa.String(255), nullable=True),
        sa.Column("price_id", sa.String(255), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("purchased_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("refunded_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stripe_lifetime_purchases_user_id", "stripe_lifetime_purchases", ["user_id"])
    op.create_index("ix_stripe_lifetime_purchases_stripe_customer_id", "stripe_lifetime_purchases", ["stripe_customer_id"])
    op.create_index("ix_stripe_lifetime_purchases_stripe_checkout_session_id", "stripe_lifetime_purchases", ["stripe_checkout_session_id"], unique=True)
    op.create_index("ix_stripe_lifetime_purchases_stripe_payment_intent_id", "stripe_lifetime_purchases", ["stripe_payment_intent_id"], unique=True)
    op.create_index("ix_stripe_lifetime_purchases_stripe_charge_id", "stripe_lifetime_purchases", ["stripe_charge_id"])


def downgrade() -> None:
    op.drop_index("ix_stripe_lifetime_purchases_stripe_charge_id", table_name="stripe_lifetime_purchases")
    op.drop_index("ix_stripe_lifetime_purchases_stripe_payment_intent_id", table_name="stripe_lifetime_purchases")
    op.drop_index("ix_stripe_lifetime_purchases_stripe_checkout_session_id", table_name="stripe_lifetime_purchases")
    op.drop_index("ix_stripe_lifetime_purchases_stripe_customer_id", table_name="stripe_lifetime_purchases")
    op.drop_index("ix_stripe_lifetime_purchases_user_id", table_name="stripe_lifetime_purchases")
    op.drop_table("stripe_lifetime_purchases")

    op.drop_index("ix_stripe_webhook_events_stripe_event_id", table_name="stripe_webhook_events")
    op.drop_table("stripe_webhook_events")

    op.drop_index("ix_stripe_subscriptions_stripe_subscription_id", table_name="stripe_subscriptions")
    op.drop_index("ix_stripe_subscriptions_stripe_customer_id", table_name="stripe_subscriptions")
    op.drop_index("ix_stripe_subscriptions_user_id", table_name="stripe_subscriptions")
    op.drop_table("stripe_subscriptions")

    op.drop_index("ix_stripe_checkout_sessions_stripe_payment_intent_id", table_name="stripe_checkout_sessions")
    op.drop_index("ix_stripe_checkout_sessions_stripe_subscription_id", table_name="stripe_checkout_sessions")
    op.drop_index("ix_stripe_checkout_sessions_stripe_customer_id", table_name="stripe_checkout_sessions")
    op.drop_index("ix_stripe_checkout_sessions_stripe_session_id", table_name="stripe_checkout_sessions")
    op.drop_index("ix_stripe_checkout_sessions_user_id", table_name="stripe_checkout_sessions")
    op.drop_table("stripe_checkout_sessions")

    op.drop_index("ix_stripe_customers_stripe_customer_id", table_name="stripe_customers")
    op.drop_index("ix_stripe_customers_user_id", table_name="stripe_customers")
    op.drop_table("stripe_customers")

"""Add purchases, entitlements, and entitlement_events tables

Revision ID: 0003_purchases_entitlements
Revises: 0002_phase3_watch_state_reports
Create Date: 2026-03-07 00:00:00.000000
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0003_purchases_entitlements"
down_revision = "0002_phase3_watch_state_reports"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "purchases",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("store", sa.String(length=20), nullable=False),
        sa.Column("platform", sa.String(length=40), nullable=False),
        sa.Column("product_id", sa.String(length=128), nullable=False),
        sa.Column("purchase_type", sa.String(length=20), nullable=False),
        sa.Column("transaction_id", sa.String(length=256), nullable=True),
        sa.Column("original_transaction_id", sa.String(length=256), nullable=True),
        sa.Column("purchase_token", sa.Text(), nullable=True),
        sa.Column("receipt_reference", sa.Text(), nullable=True),
        sa.Column("raw_payload_json", postgresql.JSONB(), nullable=True),
        sa.Column("currency", sa.String(length=8), nullable=True),
        sa.Column("price_amount", sa.BigInteger(), nullable=True),
        sa.Column("purchased_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("verification_status", sa.String(length=20), nullable=False, server_default="pending"),
        sa.Column("revocation_reason", sa.String(length=128), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("store", "original_transaction_id", name="uq_purchases_store_orig_txn"),
    )
    op.create_index("ix_purchases_user_id", "purchases", ["user_id"])
    op.create_index("ix_purchases_store_transaction", "purchases", ["store", "transaction_id"])

    op.create_table(
        "entitlements",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("entitlement_key", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
        sa.Column("source_store", sa.String(length=20), nullable=False),
        sa.Column("source_purchase_id", sa.String(length=36), nullable=False),
        sa.Column("starts_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ends_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("granted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
        sa.ForeignKeyConstraint(["source_purchase_id"], ["purchases.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("user_id", "entitlement_key", "source_purchase_id", name="uq_entitlements_user_key_purchase"),
    )
    op.create_index("ix_entitlements_user_id", "entitlements", ["user_id"])

    op.create_table(
        "entitlement_events",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("entitlement_key", sa.String(length=64), nullable=False),
        sa.Column("event_type", sa.String(length=20), nullable=False),
        sa.Column("source_purchase_id", sa.String(length=36), nullable=True),
        sa.Column("metadata_json", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_entitlement_events_user_id", "entitlement_events", ["user_id"])


def downgrade() -> None:
    op.drop_table("entitlement_events")
    op.drop_table("entitlements")
    op.drop_table("purchases")

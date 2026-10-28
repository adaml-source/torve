"""Apple monthly subscriptions and their independent five-device allowance."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0034"
down_revision = "0033"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("apple_subscription_owners",
        sa.Column("environment", sa.String(20), primary_key=True),
        sa.Column("original_transaction_id", sa.String(64), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False))
    op.create_index("ix_apple_subscription_owners_user_id", "apple_subscription_owners", ["user_id"])
    op.create_table("apple_subscription_transactions",
        sa.Column("environment", sa.String(20), primary_key=True),
        sa.Column("transaction_id", sa.String(64), primary_key=True),
        sa.Column("original_transaction_id", sa.String(64), nullable=False),
        sa.Column("product_id", sa.String(255), nullable=False),
        sa.Column("expires_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("signed_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("revoked_at_ms", sa.BigInteger(), nullable=True),
        sa.Column("is_upgraded", sa.Boolean(), nullable=False),
        sa.ForeignKeyConstraint(["environment", "original_transaction_id"],
            ["apple_subscription_owners.environment", "apple_subscription_owners.original_transaction_id"], ondelete="CASCADE"))
    op.create_table("apple_subscription_devices",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("platform", sa.String(10), nullable=False),
        sa.Column("display_name", sa.String(100), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("last_seen_ms", sa.BigInteger(), nullable=False))
    op.create_index("ix_apple_subscription_devices_active", "apple_subscription_devices", ["user_id", "active"])


def downgrade():
    op.drop_table("apple_subscription_devices")
    op.drop_table("apple_subscription_transactions")
    op.drop_table("apple_subscription_owners")

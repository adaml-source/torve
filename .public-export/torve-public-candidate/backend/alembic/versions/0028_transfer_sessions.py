"""Sealed-envelope relay sessions for intra-account device-to-device secret
transfer.

Use case: user signed in on desktop A wants to push their hydrated
IntegrationSecretStore (Panda creds, indexer keys, etc.) to desktop B
without round-tripping through the sync server in plaintext. The desktop
client encrypts everything with a per-session X25519 keypair and posts
the sealed envelope to this relay. The receiver polls until delivered,
decrypts client-side, then consumes (server clears the envelope).

The server treats `envelope_json` as opaque — it never decrypts, never
inspects, never logs it. Storage is short-lived: TTL <= 10 min, single
delivery, single consume. Both sender and receiver authenticate as the
same Torve user (this is intra-account transfer, not cross-user).
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0028"
down_revision = "0027"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "transfer_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("receiver_device_id", sa.String(128), nullable=False),
        sa.Column("receiver_device_name", sa.String(64), nullable=True),
        sa.Column("receiver_public_key", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("envelope_json", postgresql.JSONB, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("delivered_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_transfer_sessions_user_id",
        "transfer_sessions",
        ["user_id"],
    )
    op.create_index(
        "ix_transfer_sessions_expires_at",
        "transfer_sessions",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_transfer_sessions_expires_at", table_name="transfer_sessions")
    op.drop_index("ix_transfer_sessions_user_id", table_name="transfer_sessions")
    op.drop_table("transfer_sessions")

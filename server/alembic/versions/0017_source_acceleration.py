"""Add source acceleration tables: resolve_success_memory,
hash_availability_memory, provider_inventory_snapshots.

Revision ID: 0017
Revises: 0016
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0017"
down_revision = "0016"


def upgrade() -> None:
    # ── ResolveSuccessMemory ────────────────────────────────────────────
    op.create_table(
        "resolve_success_memory",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("content_id", sa.String(255), nullable=False),
        sa.Column("season", sa.Integer, nullable=True),
        sa.Column("episode", sa.Integer, nullable=True),
        sa.Column("provider_type", sa.String(50), nullable=False),
        sa.Column("source_key", sa.String(500), nullable=False),
        sa.Column("infohash", sa.String(64), nullable=True),
        sa.Column("file_name", sa.String(1000), nullable=True),
        sa.Column("quality", sa.String(20), nullable=True),
        sa.Column("audio_flags", sa.String(200), nullable=True),
        sa.Column("file_size", sa.BigInteger, nullable=True),
        sa.Column("success_count", sa.Integer, nullable=False, server_default="1"),
        sa.Column("last_success_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_failure_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_device_type", sa.String(20), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_rsm_user_id", "resolve_success_memory", ["user_id"])
    op.create_index("ix_rsm_user_content", "resolve_success_memory", ["user_id", "content_id"])
    op.create_index("ix_rsm_user_content_provider", "resolve_success_memory", ["user_id", "content_id", "provider_type"])
    op.create_index(
        "uq_rsm_user_content_source", "resolve_success_memory",
        ["user_id", "content_id", "season", "episode", "provider_type", "source_key"],
        unique=True,
    )
    op.create_index("ix_rsm_expires_at", "resolve_success_memory", ["expires_at"])

    # ── HashAvailabilityMemory ──────────────────────────────────────────
    op.create_table(
        "hash_availability_memory",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("provider_type", sa.String(50), nullable=False),
        sa.Column("infohash", sa.String(64), nullable=False),
        sa.Column("is_cached", sa.Boolean, nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("observation_source", sa.String(50), nullable=False, server_default="resolve"),
        sa.Column("confidence", sa.String(20), nullable=False, server_default="observed"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_ham_user_id", "hash_availability_memory", ["user_id"])
    op.create_index("ix_ham_user_provider_hash", "hash_availability_memory", ["user_id", "provider_type", "infohash"])
    op.create_index("ix_ham_user_provider_cached", "hash_availability_memory", ["user_id", "provider_type", "is_cached"])
    op.create_index(
        "uq_ham_user_provider_hash", "hash_availability_memory",
        ["user_id", "provider_type", "infohash"],
        unique=True,
    )
    op.create_index("ix_ham_expires_at", "hash_availability_memory", ["expires_at"])

    # ── ProviderInventorySnapshot ───────────────────────────────────────
    op.create_table(
        "provider_inventory_snapshots",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("provider_type", sa.String(50), nullable=False),
        sa.Column("remote_item_id", sa.String(500), nullable=False),
        sa.Column("normalized_title", sa.String(500), nullable=True),
        sa.Column("year", sa.Integer, nullable=True),
        sa.Column("season", sa.Integer, nullable=True),
        sa.Column("episode", sa.Integer, nullable=True),
        sa.Column("infohash", sa.String(64), nullable=True),
        sa.Column("file_size", sa.BigInteger, nullable=True),
        sa.Column("file_name", sa.String(1000), nullable=True),
        sa.Column("display_path", sa.String(2000), nullable=True),
        sa.Column("inventory_class", sa.String(30), nullable=False),
        sa.Column("quality", sa.String(20), nullable=True),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_pis_user_id", "provider_inventory_snapshots", ["user_id"])
    op.create_index("ix_pis_user_provider", "provider_inventory_snapshots", ["user_id", "provider_type"])
    op.create_index("ix_pis_user_provider_hash", "provider_inventory_snapshots", ["user_id", "provider_type", "infohash"])
    op.create_index("ix_pis_user_provider_title", "provider_inventory_snapshots", ["user_id", "provider_type", "normalized_title"])
    op.create_index(
        "uq_pis_user_provider_item", "provider_inventory_snapshots",
        ["user_id", "provider_type", "remote_item_id"],
        unique=True,
    )
    op.create_index("ix_pis_expires_at", "provider_inventory_snapshots", ["expires_at"])


def downgrade() -> None:
    op.drop_table("provider_inventory_snapshots")
    op.drop_table("hash_availability_memory")
    op.drop_table("resolve_success_memory")

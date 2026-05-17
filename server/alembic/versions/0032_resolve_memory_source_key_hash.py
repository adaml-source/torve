"""Allow long resolve source keys and dedupe by digest.

Revision ID: 0032
Revises: 0031
"""
import hashlib

from alembic import op
import sqlalchemy as sa


revision = "0032"
down_revision = "0031"
branch_labels = None
depends_on = None


def _backfill_source_key_hashes() -> None:
    bind = op.get_bind()
    rows = bind.execute(
        sa.text(
            "SELECT id, source_key FROM resolve_success_memory "
            "WHERE source_key_hash IS NULL"
        )
    )
    update = sa.text(
        "UPDATE resolve_success_memory "
        "SET source_key_hash = :source_key_hash "
        "WHERE id = :id"
    )
    for row in rows:
        source_key = row._mapping["source_key"]
        digest = hashlib.sha256(source_key.encode("utf-8")).hexdigest()
        bind.execute(
            update,
            {"id": row._mapping["id"], "source_key_hash": digest},
        )


def upgrade() -> None:
    op.add_column(
        "resolve_success_memory",
        sa.Column("source_key_hash", sa.String(length=64), nullable=True),
    )
    _backfill_source_key_hashes()
    op.alter_column(
        "resolve_success_memory",
        "source_key_hash",
        existing_type=sa.String(length=64),
        nullable=False,
    )

    op.drop_index("uq_rsm_user_content_source", table_name="resolve_success_memory")
    op.alter_column(
        "resolve_success_memory",
        "source_key",
        existing_type=sa.String(length=500),
        type_=sa.Text(),
        existing_nullable=False,
    )
    op.create_index(
        "uq_rsm_user_content_source",
        "resolve_success_memory",
        [
            "user_id",
            "content_id",
            "season",
            "episode",
            "provider_type",
            "source_key_hash",
        ],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_rsm_user_content_source", table_name="resolve_success_memory")
    op.execute(
        "UPDATE resolve_success_memory "
        "SET source_key = left(source_key, 500) "
        "WHERE length(source_key) > 500"
    )
    op.alter_column(
        "resolve_success_memory",
        "source_key",
        existing_type=sa.Text(),
        type_=sa.String(length=500),
        existing_nullable=False,
    )
    op.drop_column("resolve_success_memory", "source_key_hash")
    op.create_index(
        "uq_rsm_user_content_source",
        "resolve_success_memory",
        ["user_id", "content_id", "season", "episode", "provider_type", "source_key"],
        unique=True,
    )

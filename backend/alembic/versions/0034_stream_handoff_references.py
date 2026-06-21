"""Add shared generic stream handoff references.

Revision ID: 0034
Revises: 0033
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0034"
down_revision = "0033"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "stream_handoff_references",
        sa.Column("stream_id", sa.String(100), primary_key=True, nullable=False),
        sa.Column("upstream_url_encrypted", sa.Text(), nullable=False),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "device_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("content_id", sa.String(255), nullable=False),
        sa.Column("provider_type", sa.String(50), nullable=False),
        sa.Column("source_ref", sa.String(255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stream_handoff_refs_user_id", "stream_handoff_references", ["user_id"])
    op.create_index("ix_stream_handoff_refs_expires_at", "stream_handoff_references", ["expires_at"])


def downgrade() -> None:
    op.drop_index("ix_stream_handoff_refs_expires_at", table_name="stream_handoff_references")
    op.drop_index("ix_stream_handoff_refs_user_id", table_name="stream_handoff_references")
    op.drop_table("stream_handoff_references")

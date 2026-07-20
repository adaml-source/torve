"""Add stream path telemetry tables.

Revision ID: 0035
Revises: 0034
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0035"
down_revision = "0034"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "stream_path_telemetry",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_hash", sa.String(64), nullable=True),
        sa.Column("device_hash", sa.String(64), nullable=True),
        sa.Column("path_type", sa.String(40), nullable=False),
        sa.Column("platform", sa.String(50), nullable=True),
        sa.Column("app_version", sa.String(50), nullable=True),
        sa.Column("distribution_channel", sa.String(80), nullable=True),
        sa.Column("content_type", sa.String(40), nullable=True),
        sa.Column("provider_category", sa.String(80), nullable=True),
        sa.Column("source_category", sa.String(80), nullable=True),
        sa.Column("device_category", sa.String(40), nullable=True),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stream_path_telemetry_created_at", "stream_path_telemetry", ["created_at"])
    op.create_index("ix_stream_path_telemetry_path_type", "stream_path_telemetry", ["path_type"])
    op.create_index("ix_stream_path_telemetry_platform", "stream_path_telemetry", ["platform"])
    op.create_index("ix_stream_path_telemetry_provider", "stream_path_telemetry", ["provider_category"])

    op.create_table(
        "stream_memory_coverage_snapshots",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("endpoint", sa.String(40), nullable=False),
        sa.Column("user_hash", sa.String(64), nullable=True),
        sa.Column("device_hash", sa.String(64), nullable=True),
        sa.Column("content_type", sa.String(40), nullable=True),
        sa.Column("eligible_candidate_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("memory_id_emitted_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("memory_id_missing_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("memory_id_coverage_ratio", sa.Float(), nullable=False, server_default="0"),
        sa.Column("missing_reason_counts", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("provider_category_counts", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("source_category_counts", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_stream_memory_coverage_created_at", "stream_memory_coverage_snapshots", ["created_at"])
    op.create_index("ix_stream_memory_coverage_endpoint", "stream_memory_coverage_snapshots", ["endpoint"])


def downgrade() -> None:
    op.drop_index("ix_stream_memory_coverage_endpoint", table_name="stream_memory_coverage_snapshots")
    op.drop_index("ix_stream_memory_coverage_created_at", table_name="stream_memory_coverage_snapshots")
    op.drop_table("stream_memory_coverage_snapshots")
    op.drop_index("ix_stream_path_telemetry_provider", table_name="stream_path_telemetry")
    op.drop_index("ix_stream_path_telemetry_platform", table_name="stream_path_telemetry")
    op.drop_index("ix_stream_path_telemetry_path_type", table_name="stream_path_telemetry")
    op.drop_index("ix_stream_path_telemetry_created_at", table_name="stream_path_telemetry")
    op.drop_table("stream_path_telemetry")

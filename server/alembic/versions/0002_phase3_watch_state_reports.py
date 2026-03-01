"""Phase 3 watch state reports

Revision ID: 0002_phase3_watch_state_reports
Revises: 0001_phase2_sync_backend
Create Date: 2026-03-01 00:00:00.000000
"""

from alembic import op
import sqlalchemy as sa


revision = "0002_phase3_watch_state_reports"
down_revision = "0001_phase2_sync_backend"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "watch_state_reports",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("device_id", sa.String(length=36), nullable=False),
        sa.Column("content_id", sa.String(length=128), nullable=False),
        sa.Column("provider", sa.String(length=80), nullable=False),
        sa.Column("position_ms", sa.BigInteger(), nullable=False),
        sa.Column("reported_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["devices.id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_watch_state_reports_user_reported_at",
        "watch_state_reports",
        ["user_id", "reported_at"],
        unique=False,
    )
    op.create_index(
        "ix_watch_state_reports_device_reported_at",
        "watch_state_reports",
        ["device_id", "reported_at"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_watch_state_reports_device_reported_at", table_name="watch_state_reports")
    op.drop_index("ix_watch_state_reports_user_reported_at", table_name="watch_state_reports")
    op.drop_table("watch_state_reports")

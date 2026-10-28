"""Add watch_state_reports table for cross-device resume.

Revision ID: 0025
Revises: 0024

Append-only log of (user, device, content, position) tuples. The
/me/watch_state/latest endpoint returns the newest row per (user,
content). device_id is nullable because the production JWT doesn't
carry a device claim today — clients may attach one optionally; if
omitted, the row records position-only without device attribution.

DEPLOYMENT NOTES
----------------
Purely additive: new table + indexes. No changes to existing tables.
Safe to apply against a running production. `alembic upgrade head`
from 0024 applies cleanly.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0025"
down_revision = "0024"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "watch_state_reports",
        sa.Column("id", UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("user_id", UUID(as_uuid=True),
                  sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("device_id", UUID(as_uuid=True),
                  sa.ForeignKey("devices.id", ondelete="SET NULL"), nullable=True),
        sa.Column("content_id", sa.String(255), nullable=False),
        sa.Column("provider", sa.String(80), nullable=False),
        sa.Column("position_ms", sa.BigInteger(), nullable=False),
        sa.Column("reported_at", sa.DateTime(timezone=True),
                  server_default=sa.text("now()"), nullable=False),
    )
    op.create_index(
        "ix_watch_state_user_content_reported_at",
        "watch_state_reports",
        ["user_id", "content_id", "reported_at"],
    )
    op.create_index(
        "ix_watch_state_user_reported_at",
        "watch_state_reports",
        ["user_id", "reported_at"],
    )
    op.create_index(
        "ix_watch_state_device_reported_at",
        "watch_state_reports",
        ["device_id", "reported_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_watch_state_device_reported_at", table_name="watch_state_reports")
    op.drop_index("ix_watch_state_user_reported_at", table_name="watch_state_reports")
    op.drop_index("ix_watch_state_user_content_reported_at", table_name="watch_state_reports")
    op.drop_table("watch_state_reports")

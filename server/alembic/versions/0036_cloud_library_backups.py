"""Encrypted versioned library backups, scoped to the Torve account."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0036"
down_revision = "0035"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("cloud_library_backups",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("request_id", sa.String(36), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("platform", sa.String(10), nullable=False),
        sa.Column("reason", sa.String(20), nullable=False),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("byte_count", sa.Integer(), nullable=False),
        sa.Column("counts_json", sa.Text(), nullable=False),
        sa.Column("encrypted_payload", sa.Text(), nullable=False),
        sa.UniqueConstraint("user_id", "request_id"))
    op.create_index("ix_cloud_library_backups_user_id", "cloud_library_backups", ["user_id"])


def downgrade():
    op.drop_table("cloud_library_backups")

"""Add user_playlists table for playlist backup/restore.

Revision ID: 0008
Revises: 0007
Create Date: 2026-03-22
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_playlists",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("playlist_id", sa.String(255), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("playlist_type", sa.String(20), nullable=False),
        sa.Column("url", sa.Text(), nullable=True),
        sa.Column("epg_url", sa.Text(), nullable=True),
        sa.Column("server", sa.Text(), nullable=True),
        sa.Column("username", sa.String(255), nullable=True),
        sa.Column("encrypted_password", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_user_playlists_user_id", "user_playlists", ["user_id"])
    op.create_index(
        "ix_user_playlists_user_playlist",
        "user_playlists",
        ["user_id", "playlist_id"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("ix_user_playlists_user_playlist", table_name="user_playlists")
    op.drop_index("ix_user_playlists_user_id", table_name="user_playlists")
    op.drop_table("user_playlists")

"""user playlists for cross-device IPTV restore

Revision ID: 0007
Revises: 0006
"""

from alembic import op
import sqlalchemy as sa

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_playlists",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("url", sa.Text, nullable=False, server_default=""),
        sa.Column("epg_url", sa.Text, nullable=True),
        sa.Column("playlist_type", sa.String(20), nullable=False, server_default="m3u"),
        sa.Column("server", sa.Text, nullable=True),
        sa.Column("username", sa.Text, nullable=True),
        sa.Column("password_enc", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_user_playlists_user_id", "user_playlists", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_user_playlists_user_id", table_name="user_playlists")
    op.drop_table("user_playlists")

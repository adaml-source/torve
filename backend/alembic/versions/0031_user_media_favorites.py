"""Add account-backed media favorites.

Favorites are keyed by a client-stable media_key per user and are synced via
the /me/media-favorites API plus MEDIA_FAVORITES_UPDATED SSE invalidation.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0031"
down_revision = "0030"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_media_favorites",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("media_key", sa.String(255), nullable=False),
        sa.Column("media_type", sa.String(20), nullable=False),
        sa.CheckConstraint(
            "media_type IN ('movie', 'series')",
            name="ck_user_media_favorites_media_type",
        ),
        sa.Column("tmdb_id", sa.Integer, nullable=True),
        sa.Column("imdb_id", sa.String(32), nullable=True),
        sa.Column("title", sa.String(512), nullable=False),
        sa.Column("poster_url", sa.Text, nullable=True),
        sa.Column("backdrop_url", sa.Text, nullable=True),
        sa.Column("rating", sa.Float, nullable=True),
        sa.Column("year", sa.Integer, nullable=True),
        sa.Column("added_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "source_device_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index(
        "ix_user_media_favorites_user_id",
        "user_media_favorites",
        ["user_id"],
    )
    op.create_index(
        "uq_user_media_favorite_key",
        "user_media_favorites",
        ["user_id", "media_key"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_user_media_favorite_key", table_name="user_media_favorites")
    op.drop_index("ix_user_media_favorites_user_id", table_name="user_media_favorites")
    op.drop_table("user_media_favorites")

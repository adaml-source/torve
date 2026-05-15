"""Account-scoped media favorites.

Stores favorite movie/show posters in the user's backend profile so every
signed-in device can render and mutate the same favorites set.
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
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("media_key", sa.String(255), nullable=False),
        sa.Column("media_type", sa.String(20), nullable=False),
        sa.Column("tmdb_id", sa.Integer, nullable=True),
        sa.Column("imdb_id", sa.String(64), nullable=True),
        sa.Column("title", sa.String(500), nullable=False),
        sa.Column("poster_url", sa.Text, nullable=True),
        sa.Column("backdrop_url", sa.Text, nullable=True),
        sa.Column("rating", sa.Float, nullable=True),
        sa.Column("year", sa.Integer, nullable=True),
        sa.Column("added_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "source_device_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index("ix_user_media_favorites_user_id", "user_media_favorites", ["user_id"])
    op.create_index(
        "ix_user_media_favorites_updated_at",
        "user_media_favorites",
        ["user_id", "updated_at"],
    )
    op.create_index(
        "uq_user_media_favorites_user_key",
        "user_media_favorites",
        ["user_id", "media_key"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_user_media_favorites_user_key", table_name="user_media_favorites")
    op.drop_index("ix_user_media_favorites_updated_at", table_name="user_media_favorites")
    op.drop_index("ix_user_media_favorites_user_id", table_name="user_media_favorites")
    op.drop_table("user_media_favorites")

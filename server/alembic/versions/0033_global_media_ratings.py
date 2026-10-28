"""Global provider-rating cache.

Revision ID: 0033
Revises: 0032
"""
from alembic import op
import sqlalchemy as sa


revision = "0033"
down_revision = "0032"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "global_media_ratings",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("media_type", sa.String(length=10), nullable=False),
        sa.Column("tmdb_id", sa.Integer(), nullable=False),
        sa.Column("imdb_id", sa.String(length=32), nullable=True),
        sa.Column("imdb_score", sa.Float(), nullable=True),
        sa.Column("imdb_votes", sa.Integer(), nullable=True),
        sa.Column("tmdb_score", sa.Float(), nullable=True),
        sa.Column("rotten_tomatoes_score", sa.Integer(), nullable=True),
        sa.Column("rt_audience_score", sa.Integer(), nullable=True),
        sa.Column("metacritic_score", sa.Integer(), nullable=True),
        sa.Column("letterboxd_score", sa.Float(), nullable=True),
        sa.Column("trakt_score", sa.Float(), nullable=True),
        sa.Column("mdblist_score", sa.Float(), nullable=True),
        sa.Column("mal_score", sa.Float(), nullable=True),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index(
        "uq_global_media_ratings_identity",
        "global_media_ratings",
        ["media_type", "tmdb_id"],
        unique=True,
    )
    op.create_index(
        "ix_global_media_ratings_fetched_at",
        "global_media_ratings",
        ["fetched_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_global_media_ratings_fetched_at", table_name="global_media_ratings")
    op.drop_index("uq_global_media_ratings_identity", table_name="global_media_ratings")
    op.drop_table("global_media_ratings")

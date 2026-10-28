"""Desktop release channel — appcast feed rows.

Stores one row per published desktop build.  The GET /releases/appcast.xml
endpoint picks the highest-semver published row and renders it as Sparkle XML.
"""
from alembic import op
import sqlalchemy as sa


revision = "0030"
down_revision = "0029"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "desktop_releases",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        # Semver string, e.g. "1.2.3".  Unique so re-registering the same
        # version fails loudly rather than silently creating a duplicate.
        sa.Column("version", sa.String(40), nullable=False, unique=True),
        sa.Column("msi_url", sa.Text, nullable=False),
        sa.Column("sha256_hex", sa.String(64), nullable=False),
        sa.Column("msi_bytes", sa.BigInteger, nullable=False),
        sa.Column("release_notes_html", sa.Text, nullable=False, server_default=""),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("is_published", sa.Boolean, nullable=False, server_default="true"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_desktop_releases_version", "desktop_releases", ["version"])
    op.create_index("ix_desktop_releases_published_at", "desktop_releases", ["published_at"])


def downgrade() -> None:
    op.drop_index("ix_desktop_releases_published_at", table_name="desktop_releases")
    op.drop_index("ix_desktop_releases_version", table_name="desktop_releases")
    op.drop_table("desktop_releases")

"""Encrypted connection profiles for SABnzbd/WebDAV streaming backends."""
from alembic import op
import sqlalchemy as sa

revision = "0040"
down_revision = "0039"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("nzbdav_configs", sa.Column("connection_options_encrypted", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("nzbdav_configs", "connection_options_encrypted")

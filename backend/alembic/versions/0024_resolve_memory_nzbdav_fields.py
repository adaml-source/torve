"""Add NzbDAV-specific fields to resolve_success_memory.

Revision ID: 0024
Revises: 0023

Rationale: recent-success rows need to round-trip NzbDAV-specific payload
(candidate_id, hash_key, nzb_url) along with a provenance discriminator so
the emission path can surface them as USENET_NZBDAV candidates during
/me/acceleration/sources and /me/acceleration/startup. All columns are
nullable — no backfill. Existing rows read back with provenance_kind=NULL
and behave identically to today.

DEPLOYMENT NOTES
----------------
This revision requires 0023 to already be applied. An environment stamped
at 0022 must apply both revisions in order:

    alembic upgrade 0023    # NzbDAV tables + warm-job ledger
    alembic upgrade 0024    # this revision — emission columns

`alembic upgrade head` from stamp 0022 will apply both automatically. Do
not cherry-pick 0024 without 0023 — the Sprint 1 NzbDAV tables must exist
before the resolver can record the outcomes that the emission columns
round-trip here.
"""
from alembic import op
import sqlalchemy as sa

revision = "0024"
down_revision = "0023"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "resolve_success_memory",
        sa.Column("provenance_kind", sa.String(40), nullable=True),
    )
    op.add_column(
        "resolve_success_memory",
        sa.Column("nzbdav_candidate_id", sa.String(255), nullable=True),
    )
    op.add_column(
        "resolve_success_memory",
        sa.Column("nzbdav_hash_key", sa.String(255), nullable=True),
    )
    op.add_column(
        "resolve_success_memory",
        sa.Column("nzbdav_nzb_url", sa.String(2000), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("resolve_success_memory", "nzbdav_nzb_url")
    op.drop_column("resolve_success_memory", "nzbdav_hash_key")
    op.drop_column("resolve_success_memory", "nzbdav_candidate_id")
    op.drop_column("resolve_success_memory", "provenance_kind")

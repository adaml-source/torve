"""Add NzbDAV upstream integration: nzbdav_configs and nzbdav_warm_jobs.

Revision ID: 0023
Revises: 0022

Rationale: adds per-user NzbDAV server configuration (encrypted API key) and
a warm-job ledger for speculative stream prewarming. Upstream semantics are
confined to these tables and the app/nzbdav/ package — public API surfaces
only Torve-native types.

DEPLOYMENT NOTES
----------------
Environments currently stamped at 0022 MUST apply this revision AND 0024
in sequence before the USENET_NZBDAV emission path becomes functional:

    alembic upgrade 0023    # this revision — creates NzbDAV tables
    alembic upgrade 0024    # extends resolve_success_memory with
                            # provenance_kind + NzbDAV round-trip columns

Running `alembic upgrade head` applies both in order. Do not stop at 0023 —
the emission path depends on columns added by 0024, and the app will not
surface USENET_NZBDAV candidates until both revisions are live.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision = "0023"
down_revision = "0022"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "nzbdav_configs",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("base_url", sa.String(2000), nullable=False),
        sa.Column("api_key_encrypted", sa.Text(), nullable=False),
        sa.Column("is_enabled", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("last_tested_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_healthy_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("version_string", sa.String(64), nullable=True),
        sa.Column("capabilities", JSONB(), nullable=True),
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
    op.create_index(
        "ix_nzbdav_configs_user_id",
        "nzbdav_configs",
        ["user_id"],
        unique=True,
    )

    op.create_table(
        "nzbdav_warm_jobs",
        sa.Column("job_id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("content_id", sa.String(255), nullable=False),
        sa.Column("candidate_id", sa.String(255), nullable=False),
        sa.Column("hash_key", sa.String(255), nullable=False),
        sa.Column("state", sa.String(30), nullable=False),
        sa.Column("phase", sa.String(30), nullable=True),
        sa.Column("failure_code", sa.String(50), nullable=True),
        sa.Column("failure_detail", sa.String(500), nullable=True),
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
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("stream_ready_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_nzbdav_warm_user_content_hash",
        "nzbdav_warm_jobs",
        ["user_id", "content_id", "hash_key"],
    )
    op.create_index(
        "ix_nzbdav_warm_user_state",
        "nzbdav_warm_jobs",
        ["user_id", "state"],
    )
    op.create_index(
        "ix_nzbdav_warm_expires_at",
        "nzbdav_warm_jobs",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_nzbdav_warm_expires_at", table_name="nzbdav_warm_jobs")
    op.drop_index("ix_nzbdav_warm_user_state", table_name="nzbdav_warm_jobs")
    op.drop_index("ix_nzbdav_warm_user_content_hash", table_name="nzbdav_warm_jobs")
    op.drop_table("nzbdav_warm_jobs")
    op.drop_index("ix_nzbdav_configs_user_id", table_name="nzbdav_configs")
    op.drop_table("nzbdav_configs")

"""Content policy hardening: add policy_state_version, drop content_policy_mode.

- Add policy_state_version (monotonic counter for cache invalidation)
- Drop content_policy_mode (policy mode is now request-scoped, not stored)

Revision ID: 0019
Revises: 0018
"""
from alembic import op
import sqlalchemy as sa

revision = "0019"
down_revision = "0018"


def upgrade() -> None:
    op.add_column(
        "user_content_policies",
        sa.Column("policy_state_version", sa.Integer, nullable=False, server_default="1"),
    )
    op.drop_column("user_content_policies", "content_policy_mode")


def downgrade() -> None:
    op.add_column(
        "user_content_policies",
        sa.Column("content_policy_mode", sa.String(30), nullable=False, server_default="google_play"),
    )
    op.drop_column("user_content_policies", "policy_state_version")

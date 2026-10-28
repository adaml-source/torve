"""Add lifetime_grants ledger table + backfill from user_entitlements.

Revision ID: 0022
Revises: 0021

Rationale: user_entitlements rows are cascade-deleted when a user deletes
their account, which means there's no way to auto-restore a previously
purchased lifetime grant if they sign back up. lifetime_grants stores a
persistent, email-keyed record of every lifetime grant so we can:
  - auto-restore on verified re-signup
  - let admin/support look up past grants by email

Backfill copies every existing lifetime_access entitlement into the new
table, joined to the user's email. That covers all currently-live
lifetime users. Accounts already deleted (e.g. losonczy80@gmail.com) are
unrecoverable here — their entitlement row is gone — but future deletes
will be recoverable because the grant row survives.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision = "0022"
down_revision = "0021"


def upgrade() -> None:
    op.create_table(
        "lifetime_grants",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("source", sa.String(50), nullable=False),
        sa.Column("source_ref", sa.String(255), nullable=False),
        sa.Column("product_id", sa.String(255), nullable=True),
        sa.Column("granted_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoke_reason", sa.String(255), nullable=True),
        sa.Column("notes", sa.Text, nullable=True),
    )
    op.create_index("ix_lifetime_grants_email", "lifetime_grants", ["email"])
    op.create_index(
        "uq_lifetime_grant_source_ref",
        "lifetime_grants",
        ["source", "source_ref"],
        unique=True,
    )

    # Backfill: one row per existing active lifetime entitlement, keyed by
    # the current user's email. INSERT ... SELECT ... ON CONFLICT DO NOTHING
    # so the migration is safely re-runnable if it partially commits.
    op.execute(
        """
        INSERT INTO lifetime_grants
          (id, email, source, source_ref, product_id, granted_at, revoked_at)
        SELECT
          gen_random_uuid(),
          u.email,
          e.source,
          e.source_ref,
          e.product_id,
          e.granted_at,
          e.revoked_at
        FROM user_entitlements e
        JOIN users u ON u.id = e.user_id
        WHERE e.entitlement_type = 'lifetime_access'
        ON CONFLICT (source, source_ref) DO NOTHING
        """
    )


def downgrade() -> None:
    op.drop_index("uq_lifetime_grant_source_ref", table_name="lifetime_grants")
    op.drop_index("ix_lifetime_grants_email", table_name="lifetime_grants")
    op.drop_table("lifetime_grants")

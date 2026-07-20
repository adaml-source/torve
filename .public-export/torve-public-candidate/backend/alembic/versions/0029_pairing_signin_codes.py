"""Anonymous TV-sign-in pairing codes.

A signed-out TV creates a code via POST /pairing/signin/code, displays
it as a QR (`torve-signin:<CODE>`), and polls /status until the user
scans the QR on their (signed-in) phone and the phone calls /claim.

Lives in its own table on purpose — the existing `pairing_codes` table
is for premium-gated remote-control pairing between two already-signed-in
devices. Different invariants (this one has no user_id at code-creation
time, and stores access+refresh tokens to hand to the TV on first
status poll), different lifecycle (single delivery, single consume,
serve "expired" thereafter), different security model (anonymous code
creation is fine because the row is bound to the requesting
installation_id and the TV proves possession by polling with the same
id). Sharing the table would mean nullable FKs + branchy state machines
on the live remote-control flow — not worth it.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0029"
down_revision = "0028"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "pairing_signin_codes",
        sa.Column("code", sa.String(10), primary_key=True),
        sa.Column("device_installation_id", sa.String(255), nullable=False),
        sa.Column("device_name", sa.String(200), nullable=True),
        sa.Column("device_type", sa.String(20), nullable=False),
        sa.Column("platform", sa.String(50), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "claimed_by_user_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=True,
        ),
        sa.Column("claimed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        # Encrypted at rest with the same encrypt_secret() used everywhere
        # else in this deployment — never plaintext on disk.
        sa.Column("access_token_encrypted", sa.Text, nullable=True),
        sa.Column("refresh_token_encrypted", sa.Text, nullable=True),
        sa.Column("access_token_expires_in_s", sa.Integer, nullable=True),
        sa.Column(
            "claimed_device_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("devices.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index(
        "ix_pairing_signin_codes_expires_at",
        "pairing_signin_codes",
        ["expires_at"],
    )
    op.create_index(
        "ix_pairing_signin_codes_installation",
        "pairing_signin_codes",
        ["device_installation_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_pairing_signin_codes_installation", table_name="pairing_signin_codes")
    op.drop_index("ix_pairing_signin_codes_expires_at", table_name="pairing_signin_codes")
    op.drop_table("pairing_signin_codes")

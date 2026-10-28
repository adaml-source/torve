"""Household Family policies, parent verification and email PIN recovery.

Revision ID: 0037
Revises: 0036
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql
revision = "0037"
down_revision = "0036"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("family_account_policies",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("kids", sa.JSON(), nullable=False, server_default="{}"),
        sa.Column("version", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("pin_hash", sa.Text()), sa.Column("pin_revision", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("failures", sa.Integer(), nullable=False, server_default="0"), sa.Column("locked_until", sa.DateTime(timezone=True)),
        sa.Column("recovery_hash", sa.String(64)), sa.Column("recovery_expires", sa.DateTime(timezone=True)),
        sa.Column("recovery_sent", sa.DateTime(timezone=True)), sa.Column("recovery_failures", sa.Integer(), nullable=False, server_default="0"))


def downgrade(): op.drop_table("family_account_policies")

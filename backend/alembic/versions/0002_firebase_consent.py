"""Add Firebase identity, consent and AI status fields."""
from alembic import op
import sqlalchemy as sa

revision = "0002_firebase_consent"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade():
    inspector = sa.inspect(op.get_bind())
    user_columns = {c["name"]: c for c in inspector.get_columns("users")}
    users = set(user_columns)
    indexes = {i["name"] for i in inspector.get_indexes("users")}
    with op.batch_alter_table("users") as batch:
        if "email" in users and not user_columns["email"]["nullable"]:
            batch.alter_column("email", existing_type=sa.String(320), nullable=True)
        if "password_hash" in users and not user_columns["password_hash"]["nullable"]:
            batch.alter_column("password_hash", existing_type=sa.String(255), nullable=True)
        if "firebase_uid" not in users:
            batch.add_column(sa.Column("firebase_uid", sa.String(128), nullable=True))
        if "cloud_sync_enabled" not in users:
            batch.add_column(sa.Column("cloud_sync_enabled", sa.Boolean(), nullable=False, server_default=sa.false()))
        if "ai_consent" not in users:
            batch.add_column(sa.Column("ai_consent", sa.Boolean(), nullable=False, server_default=sa.false()))
        if "ix_users_firebase_uid" not in indexes:
            batch.create_index("ix_users_firebase_uid", ["firebase_uid"], unique=True)
    inspector = sa.inspect(op.get_bind())
    insights = {c["name"] for c in inspector.get_columns("insights")}
    with op.batch_alter_table("insights") as batch:
        if "headline" not in insights:
            batch.add_column(sa.Column("headline", sa.String(100), nullable=True))
        if "generation_status" not in insights:
            batch.add_column(sa.Column("generation_status", sa.String(30), nullable=False, server_default="deterministic_only"))
        if "model_name" not in insights:
            batch.add_column(sa.Column("model_name", sa.String(100), nullable=True))


def downgrade():
    inspector = sa.inspect(op.get_bind())
    insights = {c["name"] for c in inspector.get_columns("insights")}
    with op.batch_alter_table("insights") as batch:
        if "headline" in insights:
            batch.drop_column("headline")
        if "model_name" in insights:
            batch.drop_column("model_name")
        if "generation_status" in insights:
            batch.drop_column("generation_status")
    inspector = sa.inspect(op.get_bind())
    user_columns = {c["name"]: c for c in inspector.get_columns("users")}
    users = set(user_columns)
    indexes = {i["name"] for i in inspector.get_indexes("users")}
    with op.batch_alter_table("users") as batch:
        if "ix_users_firebase_uid" in indexes:
            batch.drop_index("ix_users_firebase_uid")
        for name in ("ai_consent", "cloud_sync_enabled", "firebase_uid"):
            if name in users:
                batch.drop_column(name)

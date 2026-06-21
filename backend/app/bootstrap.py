"""Startup bootstrap tasks (idempotent)."""
import logging

from sqlalchemy.orm import Session

from app.config import settings
from app.models import AccountSettings, User
from app.security import hash_password

_log = logging.getLogger(__name__)


def bootstrap_reviewer_account(db: Session) -> None:
    """Ensure the Google Play reviewer account exists and is ready.

    Idempotent: creates on first run, updates on subsequent runs.
    Gated by GOOGLE_PLAY_REVIEW_ENABLED env var.
    """
    if not settings.GOOGLE_PLAY_REVIEW_ENABLED:
        _log.debug("Google Play review account bootstrap: disabled")
        return

    if not settings.GOOGLE_PLAY_REVIEW_PASSWORD:
        _log.warning(
            "GOOGLE_PLAY_REVIEW_ENABLED is true but GOOGLE_PLAY_REVIEW_PASSWORD is empty; skipping"
        )
        return

    email = settings.GOOGLE_PLAY_REVIEW_EMAIL.lower().strip()

    user = db.query(User).filter(User.email == email).first()

    if user:
        # Update password, display name, and flags in case they changed
        user.password_hash = hash_password(settings.GOOGLE_PLAY_REVIEW_PASSWORD)
        user.display_name = settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME
        user.is_active = True
        user.is_verified = True
        db.commit()
        _log.info("Google Play reviewer account updated: %s", email)
    else:
        user = User(
            email=email,
            password_hash=hash_password(settings.GOOGLE_PLAY_REVIEW_PASSWORD),
            display_name=settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME,
            is_active=True,
            is_verified=True,
        )
        db.add(user)
        db.flush()

        # Seed default account settings so the app isn't empty
        acct_settings = AccountSettings(
            user_id=user.id,
            settings={
                "language": "en",
                "home_layout": "default",
                "ratings_provider": "imdb",
            },
        )
        db.add(acct_settings)
        db.commit()
        _log.info("Google Play reviewer account created: %s", email)

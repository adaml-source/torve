"""DB layer over NzbdavConfig.

All credential persistence goes through this module. The API key is
encrypted via app.crypto.encrypt_secret — never stored in plaintext.
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.models import NzbdavConfig

_log = logging.getLogger(__name__)


class NzbdavAccountStore:
    """Thin CRUD-ish interface over the NzbdavConfig table.

    Methods commit their own transactions — callers don't need to call
    db.commit() explicitly for the mutating paths.
    """

    def get_for_user(
        self, db: Session, user_id: uuid.UUID
    ) -> NzbdavConfig | None:
        return (
            db.query(NzbdavConfig)
            .filter(NzbdavConfig.user_id == user_id)
            .one_or_none()
        )

    def get_decrypted_api_key(self, cfg: NzbdavConfig) -> str:
        return decrypt_secret(cfg.api_key_encrypted)

    def save(
        self,
        db: Session,
        *,
        user_id: uuid.UUID,
        base_url: str,
        api_key_plaintext: str,
        version_string: str | None = None,
        capabilities: dict | None = None,
        is_enabled: bool = True,
    ) -> NzbdavConfig:
        """Upsert a user's NzbDAV configuration."""
        cfg = self.get_for_user(db, user_id)
        encrypted = encrypt_secret(api_key_plaintext)
        now = datetime.now(timezone.utc)
        if cfg is None:
            cfg = NzbdavConfig(
                id=uuid.uuid4(),
                user_id=user_id,
                base_url=base_url,
                api_key_encrypted=encrypted,
                is_enabled=is_enabled,
                last_tested_at=now,
                last_healthy_at=now,
                version_string=version_string,
                capabilities=capabilities,
            )
            db.add(cfg)
        else:
            cfg.base_url = base_url
            cfg.api_key_encrypted = encrypted
            cfg.is_enabled = is_enabled
            cfg.last_tested_at = now
            cfg.last_healthy_at = now
            cfg.version_string = version_string
            cfg.capabilities = capabilities
        db.commit()
        db.refresh(cfg)
        return cfg

    def mark_healthy(
        self,
        db: Session,
        cfg: NzbdavConfig,
        *,
        version_string: str | None = None,
        capabilities: dict | None = None,
    ) -> None:
        now = datetime.now(timezone.utc)
        cfg.last_tested_at = now
        cfg.last_healthy_at = now
        if version_string is not None:
            cfg.version_string = version_string
        if capabilities is not None:
            cfg.capabilities = capabilities
        db.commit()

    def mark_degraded(
        self, db: Session, cfg: NzbdavConfig, *, reason: str
    ) -> None:
        """Mark the config as having failed a health check.

        `reason` is stored only in logs — we do not persist upstream detail
        on the model to avoid accidental leakage.
        """
        cfg.last_tested_at = datetime.now(timezone.utc)
        db.commit()
        _log.info(
            "NZBDAV_CONFIG_DEGRADED user=%s reason=%s", cfg.user_id, reason
        )

    def delete_for_user(self, db: Session, user_id: uuid.UUID) -> bool:
        cfg = self.get_for_user(db, user_id)
        if cfg is None:
            return False
        db.delete(cfg)
        db.commit()
        return True


account_store = NzbdavAccountStore()

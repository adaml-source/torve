"""Versioned, account-owned library snapshots. Connection data is never accepted."""
import json
import re
import uuid
from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

MAX_BYTES = 2_000_000
RETAIN = 10
PREFERENCE_KEYS = {
    "theme_mode", "app_language", "content_region_code", "rating_display_prefs", "card_prefs",
    "card_style_presets", "card_style_default_preset_id", "home_layout_source", "home_section_configs",
    "home_layout_order", "enabled_streaming_services", "metadata_tmdb_catalogue_enabled",
    "metadata_tmdb_enrichment_enabled", "hide_unwatched_episode_spoilers",
    "preferred_audio_language", "preferred_subtitle_language", "subtitles_enabled_default",
}
FIELDS = {
    "profile": {"name", "avatar", "createdAt", "protected", "rating"},
    "progress": {"mediaType", "title", "position", "duration", "updatedAt", "season", "episode", "show"},
    "history": {"mediaId", "mediaType", "title", "watchedAt", "duration", "season", "episode", "show"},
    "watchlist": {"mediaType", "title", "tmdb", "imdb", "rating", "year", "genres", "addedAt", "order"},
}
UNSAFE_PREFERENCE = re.compile(r"://|www\.|%[0-9a-f]{2}|password|token|credential|api[_-]?key|authorization|cookie", re.I)
UNSAFE_URL = re.compile(r"://|%[0-9a-fA-F]{2}")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Record(StrictModel):
    profile: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    kind: Literal["profile", "progress", "history", "watchlist"]
    id: str = Field(min_length=1, max_length=256)
    changedAt: int = Field(ge=0, le=9_000_000_000_000_000)
    deleted: Literal[False] = False
    data: dict

    @model_validator(mode="after")
    def safe_fields(self):
        def identity(value):
            return isinstance(value, str) and 1 <= len(value) <= 256 and ".." not in value and all(c.isalnum() or c in ":_- ." for c in value)
        if not identity(self.id) or not self.data.keys() <= FIELDS[self.kind]:
            raise ValueError("Unsupported backup fields")
        if self.kind == "profile" and self.id != self.profile:
            raise ValueError("Invalid profile identity")
        if self.kind == "history" and not identity(self.data.get("mediaId")):
            raise ValueError("Invalid history identity")
        for key, value in self.data.items():
            if not isinstance(value, (str, int, float, bool)) or (isinstance(value, str) and (len(value) > 4096 or UNSAFE_URL.search(value))):
                raise ValueError("Unsupported backup value")
            if key in {"position", "duration", "updatedAt", "watchedAt", "createdAt", "addedAt", "avatar", "tmdb", "year", "season", "episode", "order"}:
                if type(value) is not int or not 0 <= value <= 9_000_000_000_000_000:
                    raise ValueError("Invalid backup number")
            if key == "protected" and type(value) is not bool:
                raise ValueError("Invalid profile protection")
        return self


class Preference(StrictModel):
    profile: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    key: str
    value: str = Field(max_length=65_536)

    @model_validator(mode="after")
    def safe_preference(self):
        if self.key not in PREFERENCE_KEYS or UNSAFE_PREFERENCE.search(self.value):
            raise ValueError("Unsupported backup preference")
        return self


class LibraryPayload(StrictModel):
    schemaVersion: Literal[1] = 1
    exportedAt: int = Field(gt=0, le=9_000_000_000_000_000)
    records: list[Record] = Field(max_length=20_000)
    preferences: list[Preference] = Field(default_factory=list, max_length=2_500)

    @model_validator(mode="after")
    def coherent_profiles(self):
        profiles = {r.profile for r in self.records if r.kind == "profile"}
        identities = [(r.profile, r.kind, r.id) for r in self.records]
        prefs = [(p.profile, p.key) for p in self.preferences]
        if not 1 <= len(profiles) <= 8 or len(set(identities)) != len(identities) or len(set(prefs)) != len(prefs):
            raise ValueError("Invalid backup profiles")
        if any(r.profile not in profiles for r in self.records) or any(p.profile not in profiles for p in self.preferences):
            raise ValueError("Invalid backup profile reference")
        return self

    def counts(self):
        result = {kind: sum(r.kind == kind for r in self.records) for kind in FIELDS}
        result["preferences"] = len(self.preferences)
        return result


class CreateBackup(StrictModel):
    request_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    platform: Literal["ios", "tvos", "android", "firetv"]
    reason: Literal["manual", "automatic", "pre_restore"] = "manual"
    payload: LibraryPayload


class CloudBackup(Base):
    __tablename__ = "cloud_library_backups"
    __table_args__ = (UniqueConstraint("user_id", "request_id"),)
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    request_id: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    platform: Mapped[str] = mapped_column(String(10))
    reason: Mapped[str] = mapped_column(String(20))
    payload_hash: Mapped[str] = mapped_column(String(64))
    byte_count: Mapped[int] = mapped_column(Integer)
    counts_json: Mapped[str] = mapped_column(Text)
    encrypted_payload: Mapped[str] = mapped_column(Text)


def metadata(row: CloudBackup):
    return dict(id=str(row.id), created_at=row.created_at.isoformat(), platform=row.platform,
                reason=row.reason, byte_count=row.byte_count, counts=json.loads(row.counts_json))

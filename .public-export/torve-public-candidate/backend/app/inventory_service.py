"""
Provider inventory snapshot service.

Ingests normalized inventory items from connected debrid/cloud providers,
stores them as ProviderInventorySnapshot rows, and exposes matching queries
for content-to-inventory lookups during source acceleration.

Architecture:
- The service layer is provider-neutral. It accepts pre-normalized items.
- Per-provider adapters (below) normalize raw provider API responses into
  the common InventoryItem format. Adapters never touch credentials directly;
  they accept already-fetched API response payloads.
- Client or server-side sync jobs call ingest_snapshot() with normalized items.
- Read methods match requested content against snapshots by infohash, title,
  or season/episode.

Supported providers (adapter-ready):
- real_debrid: cloud torrents (/torrents endpoint)
- premiumize: transfers (/transfer/list endpoint)
- alldebrid: magnets (/magnet/status endpoint)
- torbox: torrents (/torrents endpoint)

Unsupported providers return empty from their adapters — no error, no block.

Security:
- No raw secrets stored or accessed. Credentials stay in UserIntegration
  (encrypted via Fernet) and are handled by the existing integration layer.
- This service only sees already-fetched data or normalized payloads.
"""
import logging
import re
import uuid
from datetime import datetime, timedelta, timezone
from typing import Callable, NamedTuple

from sqlalchemy import and_, desc, func, or_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session

from app.models import ProviderInventorySnapshot

_log = logging.getLogger(__name__)

# Snapshot freshness defaults
DEFAULT_SNAPSHOT_TTL_HOURS = 24
CLOUD_TTL_HOURS = 24
HISTORY_TTL_HOURS = 48


# ── Normalized item contract ────────────────────────────────────────────


class InventoryItem(NamedTuple):
    """Provider-neutral normalized inventory item."""
    remote_item_id: str
    file_name: str | None = None
    normalized_title: str | None = None
    year: int | None = None
    season: int | None = None
    episode: int | None = None
    infohash: str | None = None
    file_size: int | None = None
    display_path: str | None = None
    inventory_class: str = "cloud"  # cloud, download, history, library
    quality: str | None = None


# ── Title normalization ─────────────────────────────────────────────────


_QUALITY_RE = re.compile(
    r"\b(2160p|4k|uhd|1080p|720p|480p|hdtv|bluray|blu-ray|bdrip|brrip|"
    r"webrip|web-dl|webdl|hdrip|dvdrip|remux|hdr|hdr10|dv|dolby.?vision)\b",
    re.IGNORECASE,
)
_SEASON_EP_RE = re.compile(r"[Ss](\d{1,2})[Ee](\d{1,3})")
_SEASON_ONLY_RE = re.compile(r"[Ss](\d{1,2})\b")
_YEAR_RE = re.compile(r"\b((?:19|20)\d{2})\b")
_JUNK_RE = re.compile(
    r"[\[\](){}\-_.]+|"
    r"\b(x264|x265|h264|h265|hevc|avc|aac|ac3|dts|eac3|atmos|truehd|"
    r"flac|mp3|multi|dual|subs?|eng?|ita|fra|deu|spa|complete|proper|"
    r"repack|internal|limited|extended|unrated|directors?\.?cut|"
    r"10bit|8bit|6ch|5\.1|7\.1)\b",
    re.IGNORECASE,
)


def normalize_title(raw: str) -> tuple[str | None, int | None, int | None, int | None, str | None]:
    """Extract (normalized_title, year, season, episode, quality) from a filename/title.

    Returns best-effort parsed values. Any field may be None.
    """
    if not raw:
        return None, None, None, None, None

    # Extract quality
    quality_match = _QUALITY_RE.search(raw)
    quality = _canonicalize_quality(quality_match.group(1)) if quality_match else None

    # Extract season/episode
    se_match = _SEASON_EP_RE.search(raw)
    season = int(se_match.group(1)) if se_match else None
    episode = int(se_match.group(2)) if se_match else None
    if not se_match:
        s_match = _SEASON_ONLY_RE.search(raw)
        if s_match:
            season = int(s_match.group(1))

    # Extract year
    year_match = _YEAR_RE.search(raw)
    year = int(year_match.group(1)) if year_match else None

    # Build clean title: take everything before the first quality/resolution marker
    # or before SxxExx / Sxx pattern
    title = raw
    # Truncate at quality marker
    if quality_match:
        title = title[:quality_match.start()]
    # Truncate at SxxExx
    if se_match:
        title = title[:se_match.start()]
    elif season is not None:
        # Season-only pattern (e.g. "S02")
        s_match = _SEASON_ONLY_RE.search(title)
        if s_match:
            title = title[:s_match.start()]
    # Truncate at year if it looks like a boundary
    if year_match and year_match.start() > 3:
        title = title[:year_match.start()]

    # Clean up junk
    title = _JUNK_RE.sub(" ", title)
    title = re.sub(r"\s+", " ", title).strip().lower()

    return (title or None, year, season, episode, quality)


def _canonicalize_quality(raw: str) -> str:
    """Normalize quality string to canonical form."""
    q = raw.lower().replace("-", "").replace(" ", "")
    if q in ("2160p", "4k", "uhd"):
        return "4k"
    if q in ("1080p",):
        return "1080p"
    if q in ("720p",):
        return "720p"
    if q in ("480p",):
        return "480p"
    if q in ("remux",):
        return "remux"
    return raw.lower()


# ── Provider adapters ───────────────────────────────────────────────────

# Each adapter takes a raw provider API response (already fetched) and
# returns a list of InventoryItem. Adapters never touch credentials.


def adapt_real_debrid_torrents(torrents: list[dict]) -> list[InventoryItem]:
    """Normalize Real-Debrid /torrents response."""
    items = []
    for t in torrents:
        if not t.get("id"):
            continue
        filename = t.get("filename", "")
        title, year, season, episode, quality = normalize_title(filename)
        items.append(InventoryItem(
            remote_item_id=f"rd:torrent:{t['id']}",
            file_name=filename or None,
            normalized_title=title,
            year=year,
            season=season,
            episode=episode,
            infohash=_clean_hash(t.get("hash")),
            file_size=t.get("bytes"),
            display_path=filename,
            inventory_class="cloud",
            quality=quality,
        ))
    return items


def adapt_premiumize_transfers(transfers: list[dict]) -> list[InventoryItem]:
    """Normalize Premiumize /transfer/list response."""
    items = []
    for t in transfers:
        tid = t.get("id")
        if not tid:
            continue
        name = t.get("name", "")
        title, year, season, episode, quality = normalize_title(name)
        # Premiumize src field sometimes contains hash
        src = t.get("src", "")
        infohash = _extract_hash_from_magnet(src) if src.startswith("magnet:") else None
        items.append(InventoryItem(
            remote_item_id=f"pm:transfer:{tid}",
            file_name=name or None,
            normalized_title=title,
            year=year,
            season=season,
            episode=episode,
            infohash=infohash,
            file_size=t.get("size"),
            display_path=t.get("folder_id") or name,
            inventory_class="cloud",
            quality=quality,
        ))
    return items


def adapt_alldebrid_magnets(magnets: list[dict]) -> list[InventoryItem]:
    """Normalize AllDebrid /magnet/status response."""
    items = []
    for m in magnets:
        mid = m.get("id")
        if not mid:
            continue
        filename = m.get("filename", "")
        title, year, season, episode, quality = normalize_title(filename)
        items.append(InventoryItem(
            remote_item_id=f"ad:magnet:{mid}",
            file_name=filename or None,
            normalized_title=title,
            year=year,
            season=season,
            episode=episode,
            infohash=_clean_hash(m.get("hash")),
            file_size=m.get("size"),
            display_path=filename,
            inventory_class="cloud",
            quality=quality,
        ))
    return items


def adapt_torbox_torrents(torrents: list[dict]) -> list[InventoryItem]:
    """Normalize TorBox /torrents response."""
    items = []
    for t in torrents:
        tid = t.get("id")
        if not tid:
            continue
        name = t.get("name", "")
        title, year, season, episode, quality = normalize_title(name)
        items.append(InventoryItem(
            remote_item_id=f"tb:torrent:{tid}",
            file_name=name or None,
            normalized_title=title,
            year=year,
            season=season,
            episode=episode,
            infohash=_clean_hash(t.get("hash")),
            file_size=t.get("size"),
            display_path=name,
            inventory_class="cloud",
            quality=quality,
        ))
    return items


# Adapter registry: provider_type -> adapter function
PROVIDER_ADAPTERS: dict[str, callable] = {
    "real_debrid": adapt_real_debrid_torrents,
    "premiumize": adapt_premiumize_transfers,
    "alldebrid": adapt_alldebrid_magnets,
    "torbox": adapt_torbox_torrents,
}


def get_adapter(provider_type: str):
    """Return the adapter for a provider, or None if unsupported."""
    return PROVIDER_ADAPTERS.get(provider_type)


# ── Hash helpers ────────────────────────────────────────────────────────


def _clean_hash(h: str | None) -> str | None:
    if not h:
        return None
    h = h.strip().lower()
    return h if len(h) == 40 and all(c in "0123456789abcdef" for c in h) else None


_MAGNET_HASH_RE = re.compile(r"btih:([0-9a-fA-F]{40})")


def _extract_hash_from_magnet(uri: str) -> str | None:
    m = _MAGNET_HASH_RE.search(uri)
    return m.group(1).lower() if m else None


# ── Snapshot ingestion ──────────────────────────────────────────────────


def ingest_snapshot(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    items: list[InventoryItem],
    ttl_hours: int = DEFAULT_SNAPSHOT_TTL_HOURS,
    replace: bool = True,
    classify_fn: Callable[[str], str] | None = None,
) -> dict:
    """Ingest a batch of normalized inventory items into the snapshot table.

    If replace=True (default), existing items for this user+provider that
    are NOT in the new batch are marked expired. This gives full-refresh
    semantics. If replace=False, new items are upserted without expiring
    absent ones (partial/incremental sync).

    Returns {upserted: int, expired: int}.
    """
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(hours=ttl_hours)
    seen_remote_ids = set()

    rows = []
    for item in items:
        seen_remote_ids.add(item.remote_item_id)
        cls_value = classify_fn(item.file_name or item.normalized_title or "") if classify_fn else None
        rows.append({
            "user_id": user_id,
            "provider_type": provider_type,
            "remote_item_id": item.remote_item_id,
            "normalized_title": item.normalized_title,
            "year": item.year,
            "season": item.season,
            "episode": item.episode,
            "infohash": item.infohash,
            "file_size": item.file_size,
            "file_name": item.file_name,
            "display_path": item.display_path,
            "inventory_class": item.inventory_class,
            "quality": item.quality,
            "content_classification": cls_value,
            "last_seen_at": now,
            "expires_at": expires_at,
        })

    if rows:
        stmt = pg_insert(ProviderInventorySnapshot).values(rows)
        stmt = stmt.on_conflict_do_update(
            index_elements=["user_id", "provider_type", "remote_item_id"],
            set_={
                "normalized_title": stmt.excluded.normalized_title,
                "year": stmt.excluded.year,
                "season": stmt.excluded.season,
                "episode": stmt.excluded.episode,
                "infohash": stmt.excluded.infohash,
                "file_size": stmt.excluded.file_size,
                "file_name": stmt.excluded.file_name,
                "display_path": stmt.excluded.display_path,
                "inventory_class": stmt.excluded.inventory_class,
                "quality": stmt.excluded.quality,
                # Preserve existing classification when the new ingest has none
                "content_classification": func.coalesce(
                    stmt.excluded.content_classification,
                    ProviderInventorySnapshot.content_classification,
                ),
                "last_seen_at": stmt.excluded.last_seen_at,
                "expires_at": stmt.excluded.expires_at,
                "updated_at": now,
            },
        )
        db.execute(stmt)

    upserted = len(rows)

    # Full refresh: expire items not in the new batch
    expired = 0
    if replace and seen_remote_ids:
        expired = (
            db.query(ProviderInventorySnapshot)
            .filter(
                ProviderInventorySnapshot.user_id == user_id,
                ProviderInventorySnapshot.provider_type == provider_type,
                ~ProviderInventorySnapshot.remote_item_id.in_(seen_remote_ids),
                ProviderInventorySnapshot.expires_at > now,
            )
            .update({"expires_at": now}, synchronize_session=False)
        )

    db.flush()
    _log.info(
        "INVENTORY_INGEST user=%s provider=%s upserted=%d expired=%d replace=%s",
        user_id, provider_type, upserted, expired, replace,
    )
    return {"upserted": upserted, "expired": expired}


# ── Read/matching queries ───────────────────────────────────────────────


def match_by_infohash(
    db: Session,
    *,
    user_id: uuid.UUID,
    infohash: str,
    provider_type: str | None = None,
    limit: int = 20,
) -> list[ProviderInventorySnapshot]:
    """Find inventory items matching an exact infohash."""
    now = datetime.now(timezone.utc)
    q = db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user_id,
        ProviderInventorySnapshot.infohash == infohash.lower(),
        ProviderInventorySnapshot.expires_at > now,
    )
    if provider_type:
        q = q.filter(ProviderInventorySnapshot.provider_type == provider_type)
    return q.order_by(desc(ProviderInventorySnapshot.last_seen_at)).limit(limit).all()


def match_by_infohashes(
    db: Session,
    *,
    user_id: uuid.UUID,
    infohashes: list[str],
    provider_type: str | None = None,
) -> list[ProviderInventorySnapshot]:
    """Batch match: find inventory items matching any of the given infohashes."""
    if not infohashes:
        return []
    now = datetime.now(timezone.utc)
    clean = [h.lower() for h in infohashes if h]
    q = db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user_id,
        ProviderInventorySnapshot.infohash.in_(clean),
        ProviderInventorySnapshot.expires_at > now,
    )
    if provider_type:
        q = q.filter(ProviderInventorySnapshot.provider_type == provider_type)
    return q.limit(50).all()


def match_by_title(
    db: Session,
    *,
    user_id: uuid.UUID,
    title: str,
    year: int | None = None,
    season: int | None = None,
    episode: int | None = None,
    provider_type: str | None = None,
    limit: int = 20,
) -> list[ProviderInventorySnapshot]:
    """Find inventory items matching a normalized title with optional filters."""
    now = datetime.now(timezone.utc)
    q = db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user_id,
        ProviderInventorySnapshot.normalized_title == title.lower().strip(),
        ProviderInventorySnapshot.expires_at > now,
    )
    if year is not None:
        q = q.filter(ProviderInventorySnapshot.year == year)
    if season is not None:
        q = q.filter(ProviderInventorySnapshot.season == season)
    if episode is not None:
        q = q.filter(ProviderInventorySnapshot.episode == episode)
    if provider_type:
        q = q.filter(ProviderInventorySnapshot.provider_type == provider_type)
    return q.order_by(desc(ProviderInventorySnapshot.last_seen_at)).limit(limit).all()


def get_provider_snapshot_summary(
    db: Session,
    *,
    user_id: uuid.UUID,
) -> list[dict]:
    """Summary of snapshot freshness per provider.

    Returns [{provider_type, item_count, last_seen_at, oldest_expires_at}]
    for all non-expired snapshots.
    """
    now = datetime.now(timezone.utc)
    rows = (
        db.query(
            ProviderInventorySnapshot.provider_type,
            func.count(ProviderInventorySnapshot.id).label("item_count"),
            func.max(ProviderInventorySnapshot.last_seen_at).label("last_seen_at"),
            func.min(ProviderInventorySnapshot.expires_at).label("oldest_expires_at"),
        )
        .filter(
            ProviderInventorySnapshot.user_id == user_id,
            ProviderInventorySnapshot.expires_at > now,
        )
        .group_by(ProviderInventorySnapshot.provider_type)
        .all()
    )
    return [
        {
            "provider_type": r.provider_type,
            "item_count": r.item_count,
            "last_seen_at": r.last_seen_at.isoformat() if r.last_seen_at else None,
            "oldest_expires_at": r.oldest_expires_at.isoformat() if r.oldest_expires_at else None,
            "is_stale": r.oldest_expires_at < now + timedelta(hours=1) if r.oldest_expires_at else True,
        }
        for r in rows
    ]


def is_snapshot_fresh(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    min_items: int = 1,
) -> bool:
    """Check whether a snapshot for this user+provider is fresh enough to use."""
    now = datetime.now(timezone.utc)
    count = db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user_id,
        ProviderInventorySnapshot.provider_type == provider_type,
        ProviderInventorySnapshot.expires_at > now,
    ).count()
    return count >= min_items


# ── Cleanup ─────────────────────────────────────────────────────────────


def cleanup_expired(db: Session, batch_size: int = 2000) -> int:
    """Delete expired inventory snapshots. Returns count deleted."""
    now = datetime.now(timezone.utc)
    expired_ids = (
        db.query(ProviderInventorySnapshot.id)
        .filter(ProviderInventorySnapshot.expires_at < now)
        .limit(batch_size)
        .subquery()
    )
    count = (
        db.query(ProviderInventorySnapshot)
        .filter(ProviderInventorySnapshot.id.in_(expired_ids.select()))
        .delete(synchronize_session=False)
    )
    if count:
        db.commit()
        _log.info("INVENTORY_CLEANUP deleted=%d", count)
    return count

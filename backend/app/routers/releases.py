"""Desktop release channel — appcast and admin endpoints.

Public:
  GET /releases/appcast.xml  — Sparkle-compatible XML feed, no auth.

Admin (X-Admin-Secret header, same secret as admin_billing):
  POST /admin/releases        — register a new build.
  GET  /admin/releases        — list all rows.
  PATCH /admin/releases/{version} — flip is_published or update notes.

SHA-256 design choice: the caller supplies sha256 + length in the POST body.
The server never streams the MSI.  Rationale: the operator already has the
file while building; computing sha256 locally is trivial and avoids the
server downloading a potentially large binary over the CDN.  The client
verifies the hash against the downloaded installer independently.
"""
import hmac
import logging
import re
from datetime import datetime, timezone
from email.utils import format_datetime
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, field_validator
from sqlalchemy.orm import Session

from app import discord_release_notifier
from app.config import settings
from app.deps import get_db
from app.models import DesktopRelease

_log = logging.getLogger(__name__)

router = APIRouter(tags=["releases"])

_SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+")
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


# ── helpers ──────────────────────────────────────────────────────────────────

def _semver_key(v: str) -> tuple[int, ...]:
    """Convert e.g. '1.10.2' to (1, 10, 2) for numeric comparison."""
    parts = re.split(r"[.\-+]", v.lstrip("vV"))
    out = []
    for p in parts[:3]:
        try:
            out.append(int(p))
        except ValueError:
            out.append(0)
    while len(out) < 3:
        out.append(0)
    return tuple(out)


def _latest_published(db: Session) -> DesktopRelease | None:
    rows = db.query(DesktopRelease).filter(DesktopRelease.is_published == True).all()  # noqa: E712
    if not rows:
        return None
    return max(rows, key=lambda r: _semver_key(r.version))


def _render_appcast(release: DesktopRelease) -> str:
    pub_dt = release.published_at
    if pub_dt.tzinfo is None:
        pub_dt = pub_dt.replace(tzinfo=timezone.utc)
    else:
        pub_dt = pub_dt.astimezone(timezone.utc)
    pub_rfc2822 = format_datetime(pub_dt, usegmt=True)
    notes = release.release_notes_html or ""
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">\n'
        "  <channel>\n"
        "    <title>Torve Desktop Updates</title>\n"
        "    <item>\n"
        f"      <title>Torve {release.version}</title>\n"
        f"      <pubDate>{pub_rfc2822}</pubDate>\n"
        f"      <description><![CDATA[{notes}]]></description>\n"
        f"      <link>{release.msi_url}</link>\n"
        "      <enclosure\n"
        f'        url="{release.msi_url}"\n'
        f'        sparkle:version="{release.version}"\n'
        f'        sparkle:shortVersionString="{release.version}"\n'
        f'        sparkle:installerSha256="{release.sha256_hex}"\n'
        f'        length="{release.msi_bytes}"\n'
        '        type="application/x-msi"\n'
        "      />\n"
        "    </item>\n"
        "  </channel>\n"
        "</rss>\n"
    )


def _desktop_release_notification(release: DesktopRelease) -> discord_release_notifier.ReleaseNotification:
    filename = urlparse(release.msi_url).path.rsplit("/", 1)[-1]
    return discord_release_notifier.ReleaseNotification(
        platform="windows",
        version=release.version,
        build_type="public",
        release_notes_summary=release.release_notes_html,
        file_size_bytes=release.msi_bytes,
        checksum_sha256=release.sha256_hex,
        artifact_filename=filename,
    )


def _notify_desktop_release_if_published(release: DesktopRelease) -> None:
    if release.is_published:
        discord_release_notifier.notify_release_published(
            _desktop_release_notification(release)
        )


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Admin auth not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    ip = request.headers.get("x-real-ip") or (request.client.host if request.client else "?")
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", ip, request.method, request.url.path)


# ── public ────────────────────────────────────────────────────────────────────

@router.get("/releases/appcast.xml", include_in_schema=False)
def get_appcast(db: Session = Depends(get_db)):
    release = _latest_published(db)
    if release is None:
        raise HTTPException(status_code=404, detail="No published release")
    xml = _render_appcast(release)
    return Response(
        content=xml,
        media_type="application/xml",
        headers={"Cache-Control": "max-age=300"},
    )


# ── admin: create ─────────────────────────────────────────────────────────────

class ReleaseCreate(BaseModel):
    version: str
    msi_url: str
    sha256: str
    length: int
    release_notes_html: str = ""
    published_at: datetime | None = None
    is_published: bool = True

    @field_validator("version")
    @classmethod
    def version_is_semver(cls, v: str) -> str:
        if not _SEMVER_RE.match(v.lstrip("vV")):
            raise ValueError("version must start with MAJOR.MINOR.PATCH")
        return v.lstrip("vV")

    @field_validator("msi_url")
    @classmethod
    def url_is_https(cls, v: str) -> str:
        if not v.lower().startswith("https://"):
            raise ValueError("msi_url must be HTTPS")
        return v

    @field_validator("sha256")
    @classmethod
    def sha256_is_hex(cls, v: str) -> str:
        if not _SHA256_RE.match(v):
            raise ValueError("sha256 must be 64 hex characters")
        return v.lower()

    @field_validator("length")
    @classmethod
    def length_positive(cls, v: int) -> int:
        if v <= 0:
            raise ValueError("length must be > 0")
        return v


@router.post("/admin/releases", dependencies=[Depends(_verify_admin)], status_code=201)
def create_release(body: ReleaseCreate, db: Session = Depends(get_db)):
    existing = db.query(DesktopRelease).filter(DesktopRelease.version == body.version).first()
    if existing:
        raise HTTPException(status_code=409, detail=f"Version {body.version} already registered")

    now = datetime.now(timezone.utc)
    release = DesktopRelease(
        version=body.version,
        msi_url=body.msi_url,
        sha256_hex=body.sha256,
        msi_bytes=body.length,
        release_notes_html=body.release_notes_html,
        published_at=body.published_at or now,
        is_published=body.is_published,
        created_at=now,
    )
    db.add(release)
    db.commit()
    db.refresh(release)
    _notify_desktop_release_if_published(release)

    appcast_url = f"{settings.APP_PUBLIC_API_URL}/releases/appcast.xml"
    return {
        "id": release.id,
        "version": release.version,
        "msi_url": release.msi_url,
        "sha256_hex": release.sha256_hex,
        "msi_bytes": release.msi_bytes,
        "release_notes_html": release.release_notes_html,
        "published_at": release.published_at.isoformat(),
        "is_published": release.is_published,
        "created_at": release.created_at.isoformat(),
        "appcast_url": appcast_url,
    }


# ── admin: list ───────────────────────────────────────────────────────────────

@router.get("/admin/releases", dependencies=[Depends(_verify_admin)])
def list_releases(db: Session = Depends(get_db)):
    rows = db.query(DesktopRelease).order_by(DesktopRelease.created_at.desc()).all()
    return [
        {
            "id": r.id,
            "version": r.version,
            "msi_url": r.msi_url,
            "sha256_hex": r.sha256_hex,
            "msi_bytes": r.msi_bytes,
            "is_published": r.is_published,
            "published_at": r.published_at.isoformat(),
            "created_at": r.created_at.isoformat(),
        }
        for r in rows
    ]


# ── admin: patch ──────────────────────────────────────────────────────────────

class ReleasePatch(BaseModel):
    is_published: bool | None = None
    release_notes_html: str | None = None


@router.patch("/admin/releases/{version}", dependencies=[Depends(_verify_admin)])
def patch_release(version: str, body: ReleasePatch, db: Session = Depends(get_db)):
    version = version.lstrip("vV")
    release = db.query(DesktopRelease).filter(DesktopRelease.version == version).first()
    if not release:
        raise HTTPException(status_code=404, detail=f"Version {version} not found")
    was_published = release.is_published
    if body.is_published is not None:
        release.is_published = body.is_published
    if body.release_notes_html is not None:
        release.release_notes_html = body.release_notes_html
    db.commit()
    db.refresh(release)
    if body.is_published is True and not was_published:
        _notify_desktop_release_if_published(release)
    return {
        "version": release.version,
        "is_published": release.is_published,
        "release_notes_html": release.release_notes_html,
    }

"""SABnzbd and WebDAV adapter for supported Usenet streaming backends.

This module is the ONLY place that knows about NzbDAV-shaped URLs, error
payloads, and version strings. Nothing downstream of this module may leak
those semantics out.

Security:
- SSRF protection before any HTTP call (rejects private/loopback/link-local
  unless settings.NZBDAV_ALLOW_PRIVATE_HOSTS=True).
- Only http/https schemes.
- URL length cap of 2000 chars.
- 5s connect / 15s read for test/submit; 10s connect / 30s read for poll.
- Max 2 retries with jittered backoff.
- API keys and WebDAV credentials are sent in request bodies/headers.
- Connection errors, timeouts, and upstream 4xx/5xx are translated to
  UpstreamError before leaving the module.
"""
from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import posixpath
import re
import xml.etree.ElementTree as ET
import logging
import random
import socket
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote, unquote, urljoin, urlparse, urlsplit

import httpx
from defusedxml import ElementTree as SafeET
from defusedxml.common import DefusedXmlException

from app.config import settings
from app.nzbdav.backends import BackendConnection, BackendType
from app.nzbdav.failures import (
    FailureCode,
    UpstreamError,
    classify_http_status,
    classify_upstream_detail,
)

_log = logging.getLogger(__name__)


class _RedactSignedUrls(logging.Filter):
    """HTTPX logs URL objects at INFO, including NZB retrieval credentials."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple):
            record.args = tuple(
                value.copy_with(username="", password="", query=b"REDACTED" if value.query else b"")
                if isinstance(value, httpx.URL) else value for value in record.args
            )
        return True


logging.getLogger("httpx").addFilter(_RedactSignedUrls())

MAX_URL_LEN = 2000
MAX_RETRIES = 2


# ── SSRF guard ──────────────────────────────────────────────────────────


def validate_base_url(base_url: str) -> str:
    """Validate a base URL for use with the NzbDAV upstream.

    Raises UpstreamError(UPSTREAM_UNREACHABLE) if the URL is unsafe.
    Returns the normalized URL (trailing slash stripped).
    """
    if not isinstance(base_url, str) or not base_url:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="empty_base_url"
        )
    if len(base_url) > MAX_URL_LEN:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="base_url_too_long"
        )
    try:
        parsed = urlparse(base_url)
        _ = parsed.port
    except ValueError as exc:
        raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="invalid_url") from exc
    if any(ord(char) < 32 for char in base_url):
        raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="invalid_url")
    if parsed.scheme not in ("http", "https"):
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="invalid_scheme"
        )
    host = parsed.hostname
    if parsed.username or parsed.password or parsed.fragment:
        raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="invalid_url_authority")
    if not host:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="no_host"
        )
    if not settings.NZBDAV_ALLOW_PRIVATE_HOSTS:
        for addr in _resolve_addresses(host):
            if _is_private_address(addr):
                raise UpstreamError(
                    FailureCode.UPSTREAM_UNREACHABLE,
                    detail="private_host_rejected",
                )
    return base_url.rstrip("/")


def _resolve_addresses(host: str) -> list[str]:
    """Resolve a hostname to a list of IP address strings. If the host is
    already an IP literal, returns just that literal."""
    try:
        ip = ipaddress.ip_address(host)
        return [str(ip)]
    except ValueError:
        pass
    try:
        info = socket.getaddrinfo(host, None)
    except socket.gaierror:
        # DNS failure — treat as unreachable so the SSRF guard doesn't
        # accidentally allow a private range on retry.
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="dns_failure"
        )
    return [sockaddr[0] for _, _, _, _, sockaddr in info]


def _is_private_address(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return True  # Unknown — fail closed
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_unspecified  # 0.0.0.0/::
        or ip.is_multicast
        or ip.is_reserved
    )


# ── Data types ──────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ConnectionTestResult:
    ok: bool
    version_string: str | None
    capabilities: dict | None


@dataclass(frozen=True)
class SubmitJobResult:
    upstream_job_id: str


@dataclass(frozen=True)
class PollJobResult:
    upstream_state: str  # e.g. "queued", "downloading", "repairing", "ready", "failed"
    phase: str | None
    stream_url: str | None
    detail: str | None


# ── Client ──────────────────────────────────────────────────────────────

DAV = {"d": "DAV:"}
VIDEO_EXTENSIONS = {".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts", ".m2ts"}
PROPFIND_BODY = b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>'


def _xml(content: bytes) -> ET.Element:
    # WebDAV/NZB responses must never expand document-defined entities.
    try:
        return SafeET.fromstring(content, forbid_entities=True, forbid_external=True)
    except (ET.ParseError, DefusedXmlException) as exc:
        raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="invalid_xml") from exc


class NzbdavClient:
    """Protocol boundary retained under its old name for resolver compatibility."""

    def __init__(self, *, base_url: str, api_key: str,
                 connection: BackendConnection | None = None,
                 http_client: httpx.AsyncClient | None = None) -> None:
        self.base_url = validate_base_url(base_url)
        if urlsplit(self.base_url).query:
            raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="base_url_has_query")
        self.connection = connection or BackendConnection()
        self.webdav_url = validate_base_url(self.connection.resolved_webdav_url(self.base_url))
        if urlsplit(self.webdav_url).query:
            raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="webdav_url_has_query")
        self._api_key = api_key
        self._owns_client = http_client is None
        self._client = http_client

    @property
    def webdav_auth(self) -> httpx.BasicAuth | None:
        c = self.connection
        return httpx.BasicAuth(c.webdav_username, c.webdav_password) if c.webdav_username else None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(trust_env=False, follow_redirects=False)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        await self.aclose()

    async def _request(self, method: str, url: str, *, retry: bool = True,
                       max_bytes: int = 2 * 1024 * 1024, **kwargs) -> httpx.Response:
        # Revalidate DNS for every request; never follow upstream redirects with credentials.
        validate_base_url(url)
        client = await self._get_client()
        attempts = MAX_RETRIES + 1 if retry else 1
        for attempt in range(attempts):
            try:
                async with client.stream(method, url, follow_redirects=False,
                                         timeout=httpx.Timeout(30.0, connect=5.0), **kwargs) as response:
                    data = bytearray()
                    async for chunk in response.aiter_bytes():
                        data.extend(chunk)
                        if len(data) > max_bytes:
                            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="response_too_large")
                    headers = dict(response.headers)
                    headers.pop("content-encoding", None)
                    return httpx.Response(response.status_code, headers=headers,
                                          content=bytes(data), request=response.request)
            except httpx.TimeoutException as exc:
                code = FailureCode.TIMEOUT
                cause = exc
            except httpx.HTTPError as exc:
                code = FailureCode.UPSTREAM_UNREACHABLE
                cause = exc
            if attempt + 1 == attempts:
                raise UpstreamError(code, detail="upstream_request_failed") from cause
            await asyncio.sleep(0.1 * 2 ** attempt + random.random() * 0.05)
        raise AssertionError("unreachable")

    @staticmethod
    def _check_response(response: httpx.Response) -> None:
        # A login redirect or HTML SPA fallback is not a successful API call.
        if not 200 <= response.status_code < 300:
            code = classify_http_status(response.status_code)
            raise UpstreamError(code or FailureCode.UPSTREAM_UNREACHABLE,
                                detail=f"upstream_http_{response.status_code}")

    async def _sab(self, mode: str, *, files=None, **parameters) -> dict:
        response = await self._request(
            "POST", self.base_url + self.connection.resolved_api_path(),
            params={"mode": mode, "output": "json", **parameters},
            data={"apikey": self._api_key}, files=files,
            retry=mode not in ("addfile", "addurl"),
        )
        self._check_response(response)
        try:
            data = response.json()
        except ValueError as exc:
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_non_json") from exc
        if not isinstance(data, dict):
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_bad_shape")
        if data.get("status") is False or data.get("error"):
            error = str(data.get("error", ""))
            lowered = error.lower()
            code = FailureCode.AUTH_INVALID if any(
                word in lowered for word in ("api key", "apikey", "auth", "credential")
            ) else classify_upstream_detail(error)
            raise UpstreamError(code, detail="sab_request_rejected")
        return data

    async def test_connection(self) -> ConnectionTestResult:
        # version may be public and may report an emulated SAB version. Queue
        # verifies actual authorization; do not use version to certify a build.
        queue = await self._sab("queue", limit=1)
        if not isinstance(queue.get("queue"), dict) or not isinstance(queue["queue"].get("slots"), list):
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_queue_missing")
        await self._list_directory(self.webdav_url + "/", depth="0")
        return ConnectionTestResult(ok=True, version_string=None, capabilities={
            "sabnzbd": True, "webdav": True, "backend_type": self.connection.backend_type.value,
        })

    async def list_version(self) -> str | None:
        return (await self.test_connection()).version_string

    async def submit_nzb(self, *, nzb_url: str | None = None,
                         hash_key: str | None = None) -> SubmitJobResult:
        if not nzb_url:
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="nzb_url_required")
        # Upload the NZB as multipart rather than placing an indexer's signed
        # retrieval URL in SAB query strings, logs or upstream history.
        source = nzb_url
        for _ in range(4):
            response = await self._request("GET", source, max_bytes=16 * 1024 * 1024)
            if response.status_code in (301, 302, 303, 307, 308):
                location = response.headers.get("location")
                if not location:
                    raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="nzb_redirect_missing")
                source = validate_base_url(urljoin(source, location))
                continue
            self._check_response(response)
            document = _xml(response.content)
            if document.tag.rsplit("}", 1)[-1] != "nzb":
                raise UpstreamError(FailureCode.RELEASE_UNAVAILABLE, detail="not_an_nzb")
            break
        else:
            raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="nzb_redirect_limit")
        name = "torve-" + hashlib.sha256((hash_key or nzb_url).encode()).hexdigest()[:24] + ".nzb"
        data = await self._sab("addfile", files={"name": (name, response.content, "application/x-nzb")},
                               cat=self.connection.category, nzbname=name)
        ids = data.get("nzo_ids")
        if not isinstance(ids, list) or not ids or not isinstance(ids[0], str) or not ids[0]:
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="submit_missing_job_id")
        return SubmitJobResult(upstream_job_id=ids[0])

    async def poll_job(self, *, upstream_job_id: str) -> PollJobResult:
        history = await self._sab("history", nzo_ids=upstream_job_id, limit=1000)
        history_body = history.get("history")
        slots = history_body.get("slots") if isinstance(history_body, dict) else None
        if not isinstance(slots, list):
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_history_missing")
        slot = next((s for s in slots if isinstance(s, dict) and s.get("nzo_id") == upstream_job_id), None)
        if slot is not None:
            status = str(slot.get("status", "")).lower()
            if status in ("failed", "error", "deleted"):
                code = classify_upstream_detail(str(slot.get("fail_message") or ""))
                raise UpstreamError(code, detail="sab_job_failed")
            if status == "completed":
                directory = await self._content_directory(slot)
                files = await self._collect_media(directory)
                if not files:
                    raise UpstreamError(FailureCode.RELEASE_UNAVAILABLE, detail="no_playable_media")
                # Never silently select an episode from a season pack.
                if len(files) > 1:
                    raise UpstreamError(FailureCode.RELEASE_UNAVAILABLE, detail="ambiguous_media_release")
                # A mounted filename alone is insufficient: verify a small
                # authenticated read and range support before claiming ready.
                probe = await self._request("GET", files[0], auth=self.webdav_auth,
                                            headers={"Range": "bytes=0-0"}, max_bytes=1024)
                self._check_response(probe)
                if (probe.status_code != 206 or len(probe.content) != 1
                        or not re.fullmatch(r"bytes 0-0/[1-9][0-9]*", probe.headers.get("content-range", ""))):
                    raise UpstreamError(FailureCode.STREAM_NOT_READY, detail="dav_range_probe_failed")
                return PollJobResult("ready", "ready", files[0], None)
            return PollJobResult("checking", status, None, None)
        queue = await self._sab("queue", nzo_ids=upstream_job_id, limit=1000)
        queue_body = queue.get("queue")
        queued = queue_body.get("slots") if isinstance(queue_body, dict) else None
        if not isinstance(queued, list):
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_queue_missing")
        slot = next((s for s in queued if isinstance(s, dict) and s.get("nzo_id") == upstream_job_id), None)
        if slot is None:
            return PollJobResult("queued", "queued", None, None)
        state = str(slot.get("status", "queued")).lower()
        state = {"verifying": "checking", "fetching": "downloading"}.get(state, state)
        return PollJobResult(state, state, None, None)

    async def fetch_stream_url(self, *, upstream_job_id: str) -> str:
        result = await self.poll_job(upstream_job_id=upstream_job_id)
        if not result.stream_url:
            raise UpstreamError(FailureCode.STREAM_NOT_READY, detail="poll_no_stream_url")
        return result.stream_url

    def validate_stream_url(self, url: str) -> str:
        validate_base_url(url)
        root, target = urlsplit(self.webdav_url), urlsplit(url)
        path = unquote(target.path)
        root_path = unquote(root.path).rstrip("/")
        if ((target.scheme, target.netloc) != (root.scheme, root.netloc)
                or target.query or target.fragment or "\\" in path
                or any(p in (".", "..") for p in path.split("/"))
                or not (path == root_path or path.startswith(root_path + "/"))):
            raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="dav_path_outside_root")
        return url

    async def _content_directory(self, slot: dict) -> str:
        storage = str(slot.get("storage") or "").replace("\\", "/").rstrip("/")
        storage_name = posixpath.basename(storage)
        name = storage_name or str(slot.get("name") or slot.get("nzb_name") or "")
        if self.connection.backend_type == BackendType.DECYPHARR:
            # Decypharr's DownloadPath strips an extension from Entry.Name,
            # while its default DAV folder naming preserves Entry.Name.
            name = str(slot.get("name") or slot.get("nzb_name") or storage_name)
        job_id = str(slot.get("nzo_id") or "")
        category = str(slot.get("category") or self.connection.category)
        if self.connection.backend_type == BackendType.ALTMOUNT and not self.connection.content_path:
            config = (await self._sab("get_config")).get("config")
            if not isinstance(config, dict) or not isinstance(config.get("categories"), list):
                raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="sab_categories_missing")
            for item in config["categories"]:
                if not isinstance(item, dict):
                    continue
                if str(item.get("name", "")).lower() == category.lower():
                    category = str(item.get("dir") or category)
                    break
        for value in (name, storage_name, category, job_id):
            if "\\" in value or any(p in (".", "..") for p in value.split("/")):
                raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="invalid_history_path")
        if not name or "/" in name:
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="history_name_missing")
        path = self.connection.resolved_content_path().format(
            category=quote(category.strip("/"), safe="/"), name=quote(name, safe=""),
            storage_name=quote(storage_name, safe=""), id=quote(job_id, safe=""))
        path = "/".join(p for p in path.split("/") if p)
        url = self.webdav_url.rstrip("/") + "/" + path
        return self.validate_stream_url(url)

    async def _list_directory(self, url: str, *, depth: str = "1") -> list[tuple[str, bool, int]]:
        self.validate_stream_url(url)
        response = await self._request("PROPFIND", url, auth=self.webdav_auth,
                                       headers={"Depth": depth, "Content-Type": "application/xml"},
                                       content=PROPFIND_BODY)
        self._check_response(response)
        root = _xml(response.content)
        if root.tag != "{DAV:}multistatus":
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="dav_multistatus_missing")
        entries = []
        successful = False
        for entry in root.findall("d:response", DAV):
            href = entry.findtext("d:href", default="", namespaces=DAV)
            if not href:
                continue
            child = self.validate_stream_url(urljoin(url.rstrip("/") + "/", href))
            child_path = unquote(urlsplit(child).path).rstrip("/")
            parent_path = unquote(urlsplit(url).path).rstrip("/")
            if child_path != parent_path and not child_path.startswith(parent_path + "/"):
                raise UpstreamError(FailureCode.UPSTREAM_UNREACHABLE, detail="dav_child_outside_directory")
            # A server may return both failed and successful propstats.
            for propstat in entry.findall("d:propstat", DAV):
                if " 200 " not in propstat.findtext("d:status", default="", namespaces=DAV):
                    continue
                prop = propstat.find("d:prop", DAV)
                if prop is None:
                    continue
                successful = True
                if child_path == parent_path:
                    break
                collection = prop.find("d:resourcetype/d:collection", DAV) is not None
                try:
                    size = int(prop.findtext("d:getcontentlength", default="0", namespaces=DAV))
                except ValueError:
                    size = 0
                entries.append((child, collection, size))
                break
        if not successful:
            raise UpstreamError(FailureCode.AUTH_INVALID, detail="dav_no_successful_properties")
        if len(entries) > 1000:
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="dav_directory_too_large")
        return entries

    async def _collect_media(self, directory: str) -> list[str]:
        pending = [(directory, 0)]
        visited = set()
        media = []
        while pending:
            url, depth = pending.pop()
            if url in visited:
                continue
            visited.add(url)
            if len(visited) > 50:
                raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="dav_scan_limit")
            for child, is_dir, size in await self._list_directory(url):
                path = unquote(urlsplit(child).path)
                if re.search(r"(?:^|[ /_.-])sample(?:[ /_.-]|$)", posixpath.basename(path), re.I):
                    continue
                if is_dir and depth < 4:
                    pending.append((child, depth + 1))
                elif not is_dir and posixpath.splitext(path)[1].lower() in VIDEO_EXTENSIONS:
                    media.append(child)
        return list(dict.fromkeys(media))

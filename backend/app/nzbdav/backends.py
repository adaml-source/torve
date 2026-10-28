"""Connection profiles for SABnzbd-compatible Usenet streamers.

NNTP accounts belong to the streamer, not to Torve. Keep its protocol and
filesystem differences here, behind the existing Torve resolver contract.
"""
from enum import Enum

from pydantic import BaseModel, Field, model_validator


class BackendType(str, Enum):
    NZBDAV = "nzbdav"
    INFINIDYSK = "infinidysk"
    NZBDAVEX = "nzbdavex"
    ALTMOUNT = "altmount"
    DECYPHARR = "decypharr"


class BackendConnection(BaseModel):
    backend_type: BackendType = BackendType.NZBDAV
    # Optional overrides support separate DAV hosts and reverse-proxy subpaths.
    api_path: str = Field(default="", max_length=500)
    webdav_url: str = Field(default="", max_length=2000)
    webdav_username: str = Field(default="", max_length=500)
    webdav_password: str = Field(default="", max_length=2000, repr=False)
    category: str = Field(default="", max_length=100)
    # Relative to the WebDAV root; values are encoded as URL path components.
    content_path: str = Field(default="", max_length=1000)

    @model_validator(mode="after")
    def validate_paths(self):
        path = self.api_path
        if path and (not path.startswith("/") or path.startswith("//")
                     or any(c in path for c in "?#\\")
                     or any(p in (".", "..") for p in path.split("/"))):
            raise ValueError("api_path must be a path relative to the server")
        template = self.content_path
        for field in ("category", "name", "storage_name", "id"):
            template = template.replace("{" + field + "}", "")
        if any(c in template for c in "{}?#\\") or any(
            p in (".", "..") for p in template.split("/")
        ):
            raise ValueError("invalid content path template")
        if bool(self.webdav_username) != bool(self.webdav_password):
            raise ValueError("WebDAV username and password must be supplied together")
        return self

    def resolved_api_path(self) -> str:
        if self.api_path:
            return self.api_path
        return "/sabnzbd/api" if self.backend_type in (
            BackendType.ALTMOUNT, BackendType.DECYPHARR
        ) else "/api"

    def resolved_webdav_url(self, base_url: str) -> str:
        if self.webdav_url:
            return self.webdav_url
        suffix = "/webdav" if self.backend_type in (
            BackendType.ALTMOUNT, BackendType.DECYPHARR
        ) else ""
        return base_url.rstrip("/") + suffix

    def resolved_content_path(self) -> str:
        if self.content_path:
            return self.content_path
        if self.backend_type == BackendType.DECYPHARR:
            return "/__all__/{name}"
        if self.backend_type == BackendType.ALTMOUNT:
            return "/{category}/{name}"
        return "/content/{category}/{name}"


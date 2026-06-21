"""Admin endpoints for editable official Discord static messages."""
from __future__ import annotations

import hmac
import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field

from app import discord_static_messages as static_messages
from app.config import settings

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin/discord-static-messages", tags=["admin"])


class StaticMessageUpdate(BaseModel):
    payload: dict = Field(default_factory=dict)


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


def _page_or_404(page_key: str):
    try:
        return static_messages.PAGE_DEFINITIONS[page_key]
    except KeyError:
        raise HTTPException(status_code=404, detail="Unknown Discord static page")


def _page_response(page_key: str) -> dict:
    _page_or_404(page_key)
    return {
        "page": static_messages.page_admin_summary(page_key),
        "payload": static_messages.build_effective_payload(page_key),
    }


@router.get("/pages", dependencies=[Depends(_verify_admin)])
def list_pages():
    return {"pages": static_messages.list_admin_pages()}


@router.get("/{page_key}", dependencies=[Depends(_verify_admin)])
def get_page(page_key: str):
    return _page_response(page_key)


@router.put("/{page_key}", dependencies=[Depends(_verify_admin)])
def update_page(page_key: str, body: StaticMessageUpdate):
    _page_or_404(page_key)
    try:
        payload = static_messages.save_page_payload(page_key, body.payload)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail={"error_code": "invalid_payload", "message": str(exc)},
        )
    return {
        "page": static_messages.page_admin_summary(page_key),
        "payload": payload,
    }


@router.post("/{page_key}/reset", dependencies=[Depends(_verify_admin)])
def reset_page(page_key: str):
    _page_or_404(page_key)
    payload = static_messages.reset_page_payload(page_key)
    return {
        "page": static_messages.page_admin_summary(page_key),
        "payload": payload,
    }


@router.post("/{page_key}/publish", dependencies=[Depends(_verify_admin)])
def publish_page(page_key: str):
    page = _page_or_404(page_key)
    result = static_messages.publish_static_message(
        page.key,
        persist_created_message_id=True,
    )
    if result.ok:
        return {
            "ok": True,
            "page": static_messages.page_admin_summary(page.key),
            "action": result.action,
            "message_id": result.message_id,
        }
    if result.action == "missing_config":
        raise HTTPException(
            status_code=503,
            detail={
                "error_code": "webhook_not_configured",
                "message": f"{page.webhook_env} is not configured.",
            },
        )
    if result.action == "invalid_message_id":
        raise HTTPException(
            status_code=400,
            detail={
                "error_code": "invalid_message_id",
                "message": f"{page.message_id_env} is invalid.",
            },
        )
    if result.action == "invalid_payload":
        raise HTTPException(
            status_code=400,
            detail={
                "error_code": "invalid_payload",
                "message": "Discord payload is invalid.",
            },
        )
    raise HTTPException(
        status_code=502,
        detail={
            "error_code": "discord_publish_failed",
            "message": "Discord publish failed. Check sanitized server logs.",
        },
    )

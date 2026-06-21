"""Low-cost abuse telemetry endpoints.

These paths are not used by real clients. They exist to log callers that try
deprecated or forged premium activation surfaces discovered by reverse
engineering or guessing.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

_log = logging.getLogger(__name__)

router = APIRouter(tags=["abuse-telemetry"], include_in_schema=False)


@router.post("/premium/activate")
@router.post("/v1/premium/activate")
async def honeytoken_premium_activate(request: Request) -> JSONResponse:
    _log.warning(
        "HONEYTOKEN_SUBMITTED path=%s ip=%s user_agent=%s",
        request.url.path,
        request.client.host if request.client else None,
        request.headers.get("user-agent", ""),
    )
    return JSONResponse(status_code=404, content={"detail": "not_found"})

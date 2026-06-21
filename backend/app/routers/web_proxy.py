"""
Web BFF proxy — forwards authenticated requests from the website
to internal API endpoints using cookie-based session state.

All /web/api/* requests are rewritten to /* and forwarded internally
with the access token from the HttpOnly cookie injected as a Bearer header.

This eliminates the need for browser JS to handle tokens.
"""

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from jose import jwt, JWTError
from sqlalchemy.orm import Session
from starlette.requests import ClientDisconnect

from app.config import settings
from app.deps import get_db
from app.models import RefreshToken
from app.security import create_access_token, hash_refresh_token

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/web/api", tags=["web-proxy"])

_ACCESS_COOKIE = "torve_web_access"
_REFRESH_COOKIE = "torve_web_refresh"
_CSRF_COOKIE = "torve_csrf"
_CSRF_HEADER = "x-csrf-token"

# Methods that require CSRF validation
_CSRF_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
_BODYLESS_METHODS = {"GET", "HEAD"}


def _get_user_id_from_cookies(request: Request, db: Session) -> tuple[str | None, str | None, str | None]:
    """Extract user_id from cookies. Returns (user_id, new_access_token, new_refresh_token)."""
    access = request.cookies.get(_ACCESS_COOKIE)

    if access:
        try:
            payload = jwt.decode(access, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
            user_id = payload.get("sub")
            if user_id:
                return user_id, None, None
        except JWTError:
            pass

    # Try refresh with rotation
    refresh = request.cookies.get(_REFRESH_COOKIE)
    if refresh:
        from app.routers.auth import _handle_refresh_token_reuse, _rotate_refresh_token

        token_hash = hash_refresh_token(refresh)
        token_row = db.query(RefreshToken).filter(
            RefreshToken.token_hash == token_hash
        ).first()

        if token_row:
            if token_row.is_revoked:
                _handle_refresh_token_reuse(db, token_row)
                db.commit()
                return None, None, None

            if token_row.expires_at > datetime.now(timezone.utc):
                new_access = create_access_token(str(token_row.user_id))
                raw_new_refresh, _ = _rotate_refresh_token(db, token_row)
                db.commit()
                return str(token_row.user_id), new_access, raw_new_refresh

    return None, None, None


def _verify_csrf(request: Request) -> None:
    """Verify CSRF for state-changing methods."""
    if request.method in _CSRF_METHODS:
        cookie_token = request.cookies.get(_CSRF_COOKIE)
        header_token = request.headers.get(_CSRF_HEADER)
        if not cookie_token or not header_token or cookie_token != header_token:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="CSRF validation failed.",
            )


def _request_has_body(request: Request) -> bool:
    """Return true only when the inbound proxy request declares a body.

    Mobile Safari can close a GET connection while Starlette is waiting for a
    body frame, which raises ClientDisconnect. GET/HEAD bodies are not part of
    Torve's web API contract, so skip body reads for those methods entirely.
    """
    if request.method.upper() in _BODYLESS_METHODS:
        return False
    if request.headers.get("transfer-encoding"):
        return True
    content_length = request.headers.get("content-length")
    if not content_length:
        return False
    try:
        return int(content_length) > 0
    except ValueError:
        return False


@router.api_route("/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
async def proxy_request(path: str, request: Request, db: Session = Depends(get_db)):
    """Proxy authenticated requests to internal API endpoints.

    Reads session from cookies, validates CSRF for writes, and
    calls the actual endpoint handler with proper auth context.
    """
    _verify_csrf(request)

    user_id, new_access, new_refresh = _get_user_id_from_cookies(request, db)
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
        )

    # Build the internal API URL
    internal_path = "/" + path

    # Use httpx to call our own API internally
    import httpx

    # Read request body only when the client actually sent one. This avoids
    # benign ClientDisconnect noise from bodyless Mobile Safari GET requests.
    try:
        body = await request.body() if _request_has_body(request) else b""
    except ClientDisconnect:
        _log.info("Web proxy client disconnected before request body was read: %s %s",
                  request.method, request.url.path)
        return Response(status_code=499)

    # Forward headers (content-type etc) but inject our auth
    forward_headers = {
        "Content-Type": request.headers.get("content-type", "application/json"),
        "Authorization": f"Bearer {create_access_token(user_id)}",
    }

    try:
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{settings.APP_PORT}") as client:
            resp = await client.request(
                method=request.method,
                url=internal_path,
                content=body if body else None,
                headers=forward_headers,
                timeout=30.0,
            )
    except httpx.HTTPError as e:
        _log.error("Web proxy internal request failed: %s", e)
        raise HTTPException(status_code=502, detail="Backend request failed")

    # Build response
    response = Response(
        content=resp.content,
        status_code=resp.status_code,
        media_type=resp.headers.get("content-type", "application/json"),
    )

    # If we refreshed tokens, update cookies (rotation)
    if new_access:
        response.set_cookie(
            key=_ACCESS_COOKIE,
            value=new_access,
            httponly=True,
            secure=True,
            samesite="strict",
            path="/",
            max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        )
    if new_refresh:
        response.set_cookie(
            key=_REFRESH_COOKIE,
            value=new_refresh,
            httponly=True,
            secure=True,
            samesite="strict",
            path="/",
            max_age=settings.REFRESH_TOKEN_EXPIRE_DAYS * 86400,
        )

    return response

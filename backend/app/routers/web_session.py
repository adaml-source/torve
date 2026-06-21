"""
Web BFF session layer.

Provides cookie-backed authentication for the Torve website portal.
Tokens are stored in HttpOnly, Secure, SameSite=Strict cookies and
never exposed to browser JavaScript.

This module handles:
- POST /web/auth/login   — authenticate and set session cookies
- POST /web/auth/logout  — clear session cookies
- GET  /web/auth/session — check session validity and return user data
- GET  /web/auth/csrf    — issue a CSRF token cookie
"""

import logging
import secrets

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.models import AccountSettings, RefreshToken, User
from app.schemas import LoginRequest, SignupRequest
from app.security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    verify_password,
    generate_refresh_token,
    refresh_token_expiry,
)
from app.routers.auth import (
    _create_refresh_token,
    _handle_refresh_token_reuse,
    _auth_user_out,
    _rotate_refresh_token,
    _infer_device_type,
    _register_device_on_auth,
    _send_verification,
    password_reset_confirm,
    password_reset_request,
    resend_verification,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/web/auth", tags=["web-session"])

_COOKIE_DOMAIN = None  # None = current domain only
_COOKIE_PATH = "/"
_COOKIE_SECURE = True
_COOKIE_SAMESITE = "strict"
_ACCESS_COOKIE = "torve_web_access"
_REFRESH_COOKIE = "torve_web_refresh"
_CSRF_COOKIE = "torve_csrf"
_CSRF_HEADER = "x-csrf-token"


def _set_session_cookies(response: Response, access_token: str, refresh_token: str) -> None:
    """Set HttpOnly session cookies."""
    response.set_cookie(
        key=_ACCESS_COOKIE,
        value=access_token,
        httponly=True,
        secure=_COOKIE_SECURE,
        samesite=_COOKIE_SAMESITE,
        path=_COOKIE_PATH,
        max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
    )
    response.set_cookie(
        key=_REFRESH_COOKIE,
        value=refresh_token,
        httponly=True,
        secure=_COOKIE_SECURE,
        samesite=_COOKIE_SAMESITE,
        path=_COOKIE_PATH,
        max_age=settings.REFRESH_TOKEN_EXPIRE_DAYS * 86400,
    )


def _clear_session_cookies(response: Response) -> None:
    """Remove all session cookies.

    Must pass the same secure/samesite/httponly attributes used when setting
    the cookies, otherwise the browser won't match and delete them.
    """
    for name in (_ACCESS_COOKIE, _REFRESH_COOKIE):
        response.delete_cookie(
            key=name, path=_COOKIE_PATH,
            secure=_COOKIE_SECURE, samesite=_COOKIE_SAMESITE, httponly=True,
        )
    # CSRF cookie was set with httponly=False
    response.delete_cookie(
        key=_CSRF_COOKIE, path=_COOKIE_PATH,
        secure=_COOKIE_SECURE, samesite=_COOKIE_SAMESITE, httponly=False,
    )


def _set_csrf_cookie(response: Response) -> str:
    """Generate and set a CSRF token cookie (readable by JS)."""
    token = secrets.token_hex(32)
    response.set_cookie(
        key=_CSRF_COOKIE,
        value=token,
        httponly=False,  # JS must read this to send in header
        secure=_COOKIE_SECURE,
        samesite=_COOKIE_SAMESITE,
        path=_COOKIE_PATH,
        max_age=86400,
    )
    return token


def _verify_csrf(request: Request) -> None:
    """Verify CSRF token: cookie value must match header value."""
    cookie_token = request.cookies.get(_CSRF_COOKIE)
    header_token = request.headers.get(_CSRF_HEADER)
    if not cookie_token or not header_token or cookie_token != header_token:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="CSRF validation failed.",
        )


@router.post("/signup", status_code=status.HTTP_201_CREATED)
def web_signup(body: SignupRequest, db: Session = Depends(get_db)) -> JSONResponse:
    """Create a new account and establish a cookie-backed web session.

    Mirrors auth.signup() but returns a cookie-only response (no tokens in
    body) and issues the CSRF cookie, matching the web_login flow. Must live
    in /web/auth/ — not /web/api/ — because the proxy there requires an
    existing session and CSRF token, neither of which exists pre-signup.
    """
    email = body.email.lower().strip()
    existing = db.query(User).filter(User.email == email).first()
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered")

    user = User(
        email=email,
        password_hash=hash_password(body.password),
        display_name=body.display_name,
    )
    db.add(user)
    db.flush()

    raw_refresh, _ = _create_refresh_token(db, user, "Web Portal", "web")

    acct_settings = AccountSettings(user_id=user.id, settings={
        "language": "en", "home_layout": "default", "ratings_provider": "imdb",
    })
    db.add(acct_settings)
    db.commit()
    db.refresh(user)

    _send_verification(db, user)

    access_token = create_access_token(str(user.id))
    user_out = _auth_user_out(db, user)
    response = JSONResponse(
        status_code=status.HTTP_201_CREATED,
        content={"user": user_out.model_dump(mode="json")},
    )
    _set_session_cookies(response, access_token, raw_refresh)
    _set_csrf_cookie(response)
    _log.info("Web signup for user %s", user.id)
    return response


@router.post("/login")
def web_login(body: LoginRequest, db: Session = Depends(get_db)) -> JSONResponse:
    """Authenticate and establish a cookie-backed web session."""
    email = body.email.lower().strip()

    user = db.query(User).filter(User.email == email).first()
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is disabled",
        )

    # Create tokens
    access_token = create_access_token(str(user.id))
    raw_refresh, _ = _create_refresh_token(db, user, "Web Portal", "web")
    db.commit()

    # Build response with user data (no tokens in body)
    user_out = _auth_user_out(db, user)
    response = JSONResponse(content={
        "user": user_out.model_dump(mode="json"),
    })

    _set_session_cookies(response, access_token, raw_refresh)
    _set_csrf_cookie(response)
    _log.info("Web session created for user %s", user.id)
    return response


@router.post("/logout")
def web_logout(request: Request, db: Session = Depends(get_db)) -> JSONResponse:
    """Sign out: revoke the refresh token in the database, then clear cookies."""
    # Revoke the refresh token so it can't re-establish a session
    refresh = request.cookies.get(_REFRESH_COOKIE)
    if refresh:
        from app.security import hash_refresh_token as _hash
        token_row = db.query(RefreshToken).filter(
            RefreshToken.token_hash == _hash(refresh),
        ).first()
        if token_row and not token_row.is_revoked:
            token_row.is_revoked = True
            db.commit()
            _log.info("Web logout: revoked refresh token for user %s", token_row.user_id)

    response = JSONResponse(content={"message": "Signed out"})
    _clear_session_cookies(response)
    return response


@router.get("/session")
def web_session(request: Request, db: Session = Depends(get_db)) -> JSONResponse:
    """Check current session and return user data.

    If the access token is expired but refresh token is valid,
    silently refreshes and returns new cookies.
    """
    from jose import jwt, JWTError
    from datetime import datetime, timezone

    access = request.cookies.get(_ACCESS_COOKIE)
    refresh = request.cookies.get(_REFRESH_COOKIE)

    # If no refresh token cookie at all, the user is logged out — don't
    # trust a lingering access token after logout cleared cookies.
    if not refresh:
        if access:
            # Stale access cookie without a refresh cookie = post-logout remnant
            response = JSONResponse(content={"authenticated": False, "user": None})
            _clear_session_cookies(response)
            return response
        return JSONResponse(content={"authenticated": False, "user": None})

    # Verify the refresh token is still valid in the database.
    # This catches the case where logout revoked it but cookies lingered.
    token_hash = hash_refresh_token(refresh)
    token_row = db.query(RefreshToken).filter(
        RefreshToken.token_hash == token_hash
    ).first()

    if not token_row or token_row.is_revoked:
        # Revoked or unknown refresh token — clear everything
        if token_row and token_row.is_revoked:
            _handle_refresh_token_reuse(db, token_row)
            db.commit()
        response = JSONResponse(content={"authenticated": False, "user": None})
        _clear_session_cookies(response)
        return response

    if token_row.expires_at < datetime.now(timezone.utc):
        response = JSONResponse(content={"authenticated": False, "user": None})
        _clear_session_cookies(response)
        return response

    # Refresh token is valid — now try the access token
    if access:
        try:
            payload = jwt.decode(access, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
            user_id = payload.get("sub")
            if user_id:
                user = db.query(User).filter(User.id == user_id).first()
                if user and user.is_active:
                    return JSONResponse(content={
                        "authenticated": True,
                        "user": _auth_user_out(db, user).model_dump(mode="json"),
                    })
        except JWTError:
            pass  # Access token invalid/expired, fall through to refresh

    # Access token expired/invalid but refresh token is already validated above — rotate
    user = db.query(User).filter(User.id == token_row.user_id).first()
    if user and user.is_active:
        new_access = create_access_token(str(user.id))
        raw_new_refresh, _ = _rotate_refresh_token(db, token_row)
        db.commit()

        response = JSONResponse(content={
            "authenticated": True,
            "user": _auth_user_out(db, user).model_dump(mode="json"),
        })
        _set_session_cookies(response, new_access, raw_new_refresh)
        return response

    return JSONResponse(content={"authenticated": False, "user": None})


@router.get("/csrf")
def web_csrf() -> JSONResponse:
    """Issue a new CSRF token cookie."""
    response = JSONResponse(content={"ok": True})
    _set_csrf_cookie(response)
    return response


# ── Public (unauthenticated) auth passthroughs ─────────────────────────
# These endpoints take an email or token in the body — no session, no CSRF —
# so they cannot be served via /web/api/* (which requires both). Re-expose
# the existing auth.py handlers under /web/auth/* so the website can call
# them via the noAuth path in api.js.

router.add_api_route(
    "/password-reset/request", password_reset_request, methods=["POST"],
)
router.add_api_route(
    "/password-reset/confirm", password_reset_confirm, methods=["POST"],
)
router.add_api_route(
    "/resend-verification", resend_verification, methods=["POST"],
)

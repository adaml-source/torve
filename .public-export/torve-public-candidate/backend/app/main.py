import logging

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.database import SessionLocal
from app.bootstrap import bootstrap_reviewer_account
from app.events import event_bus
from app.routers import acceleration, abuse, account, account_settings, addons, admin_billing, admin_discord, admin_promo, admin_users, auth, beta, checkout, content_policy, devices, discord_interactions, health, health_integrations, integrations, media_favorites, meta, nzbdav as nzbdav_router, paddle_webhook, pairing_code, pairing_signin, pairings, playlists, purchase_verify, rebate, releases, sse, stream_handoff, stream_telemetry, stripe_billing, support, transfer, trust, watch_state, web_session, web_proxy

_log = logging.getLogger(__name__)


# Sentry initialisation happens before FastAPI() is constructed so the SDK
# can hook into ASGI lifecycle, request/response, and exception paths. This
# is a no-op when SENTRY_DSN is empty — no import-time failure, no telemetry.
def _init_sentry_if_configured() -> None:
    # This runs at app-module-import time, before uvicorn configures the
    # root logger. `print(..., flush=True)` bypasses that and lands in
    # journalctl via stdout, so operators can confirm initialisation on
    # restart without digging into the Sentry dashboard.
    if not settings.SENTRY_DSN.strip():
        return
    # Never initialise Sentry when running under pytest — tests run
    # against the live DB in this repo's convention, and we do NOT want
    # test-induced exceptions (especially asyncio CancelledError from
    # deliberate long-sleep tests) landing in the production Sentry
    # project. Belt-and-suspenders: SENTRY_DISABLE=1 in the env also bails.
    import sys as _sys
    import os as _os
    if "pytest" in _sys.modules or _os.environ.get("SENTRY_DISABLE") == "1":
        return
    try:
        import sentry_sdk
    except ImportError:  # pragma: no cover
        print("Sentry: DSN set but sentry-sdk not installed; skipping", flush=True)
        return
    sentry_sdk.init(
        dsn=settings.SENTRY_DSN.strip(),
        environment=(settings.SENTRY_ENVIRONMENT or settings.APP_ENV).strip(),
        release=settings.SENTRY_RELEASE.strip() or None,
        traces_sample_rate=settings.SENTRY_TRACES_SAMPLE_RATE,
        # Don't send PII. Request bodies are already redacted by the
        # RequestValidationError handler below; this belt-and-suspenders
        # the outgoing event payload. Local stack variables are also disabled:
        # they can include full DSNs or decrypted secrets before SDK scrubbing.
        send_default_pii=False,
        include_local_variables=False,
        max_breadcrumbs=50,
    )
    print(
        f"Sentry initialised env={settings.SENTRY_ENVIRONMENT or settings.APP_ENV}",
        flush=True,
    )


_init_sentry_if_configured()


# Boot-time Google Play IAP readiness banner. Single stdout line, no
# secrets. Makes operator-level misconfig visible on every restart
# instead of waiting for a user's purchase to fail.
def _log_google_play_readiness() -> None:
    try:
        from app.google_play_readiness import log_startup_readiness
        log_startup_readiness()
    except Exception:  # noqa: BLE001 — never let this block startup
        pass


_log_google_play_readiness()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    db = SessionLocal()
    try:
        bootstrap_reviewer_account(db)
    except Exception:
        _log.exception("Reviewer account bootstrap failed")
    finally:
        db.close()
    event_bus.start_listener()
    yield
    event_bus.stop_listener()

app = FastAPI(
    title="Torve Backend",
    version="0.1.0",
    docs_url="/docs" if settings.APP_ENV != "production" else None,
    redoc_url="/redoc" if settings.APP_ENV != "production" else None,
    openapi_url="/openapi.json" if settings.APP_ENV != "production" else None,
    lifespan=lifespan,
)

# Adjust origins once the Android app has a registered scheme or the web app is live
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://torve.app", "https://www.torve.app"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def _json_safe(v):
    """Recursively convert non-JSON-serializable values (e.g. ValueError from
    Pydantic v2 field_validator ctx) to their string representation."""
    if isinstance(v, dict):
        return {k: _json_safe(val) for k, val in v.items()}
    if isinstance(v, (list, tuple)):
        return [_json_safe(i) for i in v]
    if isinstance(v, (str, int, float, bool)) or v is None:
        return v
    return str(v)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    # Redact input values from logs to prevent secret leakage
    safe_errors = []
    for err in exc.errors():
        safe = {k: _json_safe(v) for k, v in err.items() if k != "input"}
        safe["input"] = "[REDACTED]"
        safe_errors.append(safe)
    _log.warning("Validation error on %s %s: %s", request.method, request.url.path, safe_errors)
    return JSONResponse(status_code=422, content={"detail": safe_errors})

app.include_router(health.router)
app.include_router(meta.router)
app.include_router(auth.router)
app.include_router(devices.router)
app.include_router(devices.compat_router)
app.include_router(pairings.router)
app.include_router(pairing_code.router)
app.include_router(pairing_signin.router)
app.include_router(account_settings.router)
app.include_router(account.router)
app.include_router(account.compat_router)
app.include_router(beta.router)
app.include_router(support.router)
app.include_router(integrations.router)
app.include_router(addons.router)
app.include_router(playlists.router)
app.include_router(media_favorites.router)
app.include_router(sse.router)
app.include_router(web_session.router)
app.include_router(web_proxy.router)
app.include_router(paddle_webhook.router)
app.include_router(checkout.router)
app.include_router(stripe_billing.router)
app.include_router(purchase_verify.router)
# Compat alias at /purchases/* — accepts pre-sprint Android clients that
# still POST to the dead-monorepo paths. See comment on legacy_router in
# purchase_verify.py. Remove once legacy installs drop to zero.
app.include_router(purchase_verify.legacy_router)
app.include_router(admin_promo.router)
app.include_router(admin_billing.router)
app.include_router(admin_discord.router)
app.include_router(admin_users.router)
app.include_router(discord_interactions.router)
app.include_router(acceleration.router)
app.include_router(content_policy.router)
app.include_router(rebate.user_router)
app.include_router(rebate.admin_router)
app.include_router(nzbdav_router.integrations_router)
app.include_router(nzbdav_router.resolver_router)
app.include_router(stream_handoff.router)
app.include_router(stream_telemetry.router)
app.include_router(health_integrations.router)
app.include_router(watch_state.router)
app.include_router(transfer.router)
app.include_router(releases.router)
app.include_router(trust.router)
app.include_router(abuse.router)

"""Client trust-signal intake.

The app is treated as untrusted. This endpoint records device/app integrity
context for abuse review and policy decisions, but never grants premium.
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_calling_installation_id, get_current_user_id, get_db
from app.models import Device
from app.rate_limits import enforce_rate_limit
from app.trust_signals import (
    is_google_play_channel,
    normalize_channel,
    normalize_risk_flags,
    verify_play_integrity_token,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/trust-signals", tags=["trust-signals"])


class TrustSignalRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    platform: str = Field(max_length=50)
    app_version: str | None = Field(default=None, alias="appVersion", max_length=50)
    build_number: str | None = Field(default=None, alias="buildNumber", max_length=80)
    distribution_channel: str | None = Field(default=None, alias="distributionChannel", max_length=80)
    package_name: str | None = Field(default=None, alias="packageName", max_length=255)
    application_id: str | None = Field(default=None, alias="applicationId", max_length=255)
    installer_package: str | None = Field(default=None, alias="installerPackage", max_length=255)
    signing_certificate_sha256: str | None = Field(default=None, alias="signingCertificateSha256", max_length=255)
    is_debuggable: bool = Field(default=False, alias="isDebuggable")
    is_emulator: bool = Field(default=False, alias="isEmulator")
    likely_virtual_device: bool = Field(default=False, alias="likelyVirtualDevice")
    has_known_hooking_indicators: bool = Field(default=False, alias="hasKnownHookingIndicators")
    has_known_root_indicators: bool = Field(default=False, alias="hasKnownRootIndicators")
    integrity_provider: str | None = Field(default=None, alias="integrityProvider", max_length=80)
    integrity_token: str | None = Field(default=None, alias="integrityToken", max_length=12000)
    generated_at_epoch_millis: int | None = Field(default=None, alias="generatedAtEpochMillis")


class TrustSignalResponse(BaseModel):
    accepted: bool
    channel: str
    device_id: str | None
    risk_flags: list[str]
    integrity_status: str | None = None
    integrity_provider: str | None = None


@router.post("", response_model=TrustSignalResponse)
def record_trust_signals(
    body: TrustSignalRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> TrustSignalResponse:
    uid = uuid.UUID(user_id)
    enforce_rate_limit(
        category="trust_signals",
        request=request,
        user_id=user_id,
        limit=120,
        window_seconds=300,
    )
    device_id: uuid.UUID | None = None
    if installation_id:
        device = (
            db.query(Device)
            .filter(
                Device.user_id == uid,
                Device.installation_id == installation_id,
                Device.is_active == True,  # noqa: E712
            )
            .first()
        )
        if device is not None:
            device_id = device.id

    channel = normalize_channel(body.distribution_channel)
    risk_flags = normalize_risk_flags(body)
    integrity_status: str | None = None

    if is_google_play_channel(
        distribution_channel=body.distribution_channel,
        installer_package=body.installer_package,
        integrity_provider=body.integrity_provider,
    ) and body.integrity_token:
        verdict = verify_play_integrity_token(
            integrity_token=body.integrity_token,
            package_name=body.package_name or body.application_id,
            expected_certificate_sha256=(
                body.signing_certificate_sha256
                or settings.GOOGLE_PLAY_SIGNING_CERT_SHA256
            ),
        )
        integrity_status = verdict.status
        risk_flags.extend(flag for flag in verdict.risk_flags if flag not in risk_flags)

    generated_age_ms: int | None = None
    if body.generated_at_epoch_millis:
        generated_age_ms = int(datetime.now(timezone.utc).timestamp() * 1000) - body.generated_at_epoch_millis

    _log.info(
        "TRUST_SIGNAL user=%s device=%s category=%s channel=%s platform=%s "
        "app_version=%s build=%s integrity_provider=%s integrity_status=%s "
        "risk_flags=%s generated_age_ms=%s ip=%s",
        uid,
        device_id,
        request.url.path,
        channel,
        body.platform,
        body.app_version,
        body.build_number,
        body.integrity_provider,
        integrity_status,
        ",".join(sorted(set(risk_flags))) or "none",
        generated_age_ms,
        request.client.host if request.client else None,
    )

    return TrustSignalResponse(
        accepted=True,
        channel=channel,
        device_id=str(device_id) if device_id else None,
        risk_flags=sorted(set(risk_flags)),
        integrity_status=integrity_status,
        integrity_provider=body.integrity_provider,
    )

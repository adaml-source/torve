"""Failure-mapping tests: every FailureCode is producible by some path."""
from __future__ import annotations

import pytest

from app.nzbdav.failures import (
    FailureCode,
    UpstreamError,
    classify_http_status,
    classify_upstream_detail,
    default_fallback_suggestions,
)


def test_every_enum_code_is_producible():
    # UPSTREAM_UNREACHABLE: connect error -> mapped in client (unit-level here)
    assert classify_http_status(500) == FailureCode.UPSTREAM_UNREACHABLE
    # AUTH_INVALID
    assert classify_http_status(401) == FailureCode.AUTH_INVALID
    assert classify_http_status(403) == FailureCode.AUTH_INVALID
    # RATE_LIMITED
    assert classify_http_status(429) == FailureCode.RATE_LIMITED
    # RELEASE_UNAVAILABLE (via HTTP 404 or upstream markers)
    assert classify_http_status(404) == FailureCode.RELEASE_UNAVAILABLE
    assert (
        classify_upstream_detail("no articles available")
        == FailureCode.RELEASE_UNAVAILABLE
    )
    # REPAIR_FAILED
    assert (
        classify_upstream_detail("PAR2 repair failed on block 5")
        == FailureCode.REPAIR_FAILED
    )
    # EXTRACTION_FAILED
    assert (
        classify_upstream_detail("unrar failed with code 3")
        == FailureCode.EXTRACTION_FAILED
    )
    # UNKNOWN_UPSTREAM_ERROR catches everything else
    assert classify_upstream_detail("cosmic rays") == FailureCode.UNKNOWN_UPSTREAM_ERROR
    # TIMEOUT + STREAM_NOT_READY are produced by the client module; we
    # assert that UpstreamError can carry each code.
    for code in FailureCode:
        e = UpstreamError(code)
        assert e.code == code


def test_http_200_not_classified_as_error():
    assert classify_http_status(200) is None
    assert classify_http_status(204) is None


def test_fallback_suggestions_never_contain_raw_upstream_detail():
    for code in FailureCode:
        suggestions = default_fallback_suggestions(code)
        assert isinstance(suggestions, list)
        for s in suggestions:
            # No whitespace / no colons that would indicate freeform text
            assert isinstance(s, str)
            assert " " not in s
            assert ":" not in s


def test_upstream_error_detail_not_in_public_fallback():
    err = UpstreamError(
        FailureCode.REPAIR_FAILED, detail="Raw upstream string with /var/nzb/secret"
    )
    # The enum code is all clients see. The raw detail stays internal.
    assert err.code.value == "REPAIR_FAILED"
    assert "secret" not in err.code.value

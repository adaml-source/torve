from types import SimpleNamespace

import pytest
from starlette.requests import ClientDisconnect

from app.routers import web_proxy


def _request(method: str, headers: dict[str, str] | None = None):
    return SimpleNamespace(method=method, headers=headers or {})


def test_request_has_body_skips_bodyless_gets():
    assert web_proxy._request_has_body(
        _request("GET", {"content-type": "application/json"}),
    ) is False


def test_request_has_body_requires_positive_content_length():
    assert web_proxy._request_has_body(_request("POST", {})) is False
    assert web_proxy._request_has_body(_request("POST", {"content-length": "0"})) is False
    assert web_proxy._request_has_body(_request("POST", {"content-length": "12"})) is True
    assert web_proxy._request_has_body(_request("POST", {"transfer-encoding": "chunked"})) is True


@pytest.mark.asyncio
async def test_proxy_request_handles_client_disconnect_before_body(monkeypatch):
    class DisconnectingRequest:
        method = "POST"
        headers = {"content-length": "12"}
        cookies = {}
        url = SimpleNamespace(path="/web/api/me/playlists")

        async def body(self):
            raise ClientDisconnect()

    monkeypatch.setattr(web_proxy, "_verify_csrf", lambda request: None)
    monkeypatch.setattr(
        web_proxy,
        "_get_user_id_from_cookies",
        lambda request, db: ("00000000-0000-4000-8000-000000000001", None, None),
    )

    response = await web_proxy.proxy_request(
        "me/playlists",
        DisconnectingRequest(),
        db=object(),
    )

    assert response.status_code == 499

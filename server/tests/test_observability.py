from app.observability import redact_secret_string, sentry_before_send


def test_redact_secret_string_masks_url_password() -> None:
    value = "postgresql://torve_user:secret@localhost:5432/torve"

    redacted = redact_secret_string(value)

    assert redacted == "postgresql://torve_user:***@localhost:5432/torve"
    assert "secret" not in redacted


def test_sentry_before_send_redacts_captured_locals() -> None:
    event = {
        "exception": {
            "values": [
                {
                    "stacktrace": {
                        "frames": [
                            {
                                "vars": {
                                    "dsn": "'postgresql://torve_user:secret@localhost:5432/torve'",
                                    "JWT_SECRET": "super-secret",
                                },
                            },
                        ],
                    },
                },
            ],
        },
    }

    redacted = sentry_before_send(event, {})
    frame_vars = redacted["exception"]["values"][0]["stacktrace"]["frames"][0]["vars"]

    assert frame_vars["dsn"] == "'postgresql://torve_user:***@localhost:5432/torve'"
    assert frame_vars["JWT_SECRET"] == "[REDACTED]"
    assert "super-secret" not in str(redacted)

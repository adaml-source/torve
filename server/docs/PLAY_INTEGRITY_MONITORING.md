# Play Integrity monitoring

Torve observes Standard Play Integrity results. These results never grant or deny
login, account access, playback, downloads, or backend features.

## Configuration

The Play Console application `com.torve.app` is linked to Cloud project **Torve**:

- Project number: `588484757557`
- Project ID: `gen-lang-client-0529563050`
- Core responses: app recognition, Play licensing, device integrity
- Optional responses and Play Games for PC: not enabled by this integration

The backend reuses `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, a path to the existing
server-side service-account JSON file. Its project must be linked to the Play
application, with the Integrity API enabled. Never put this credential in an APK.

```ini
PLAY_INTEGRITY_MONITOR_ENABLED=true
PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER=588484757557
PLAY_INTEGRITY_SIGNING_CERT_SHA256=6AE332FF21118B198A6CD6E6BCBD3AB5069AB4225A2327E619F0C4F91EDCC7D2
```

The expected certificate is the **Play app-signing** certificate, not the upload
key or locally signed APK certificate. Update it deliberately if Play signing
keys change. The feature defaults to disabled when the environment flag is absent.

## Flow and performance

The existing background account telemetry task triggers eligible observations at
login, foreground, or credential transfer, at most once per 24 hours per local
installation. The last attempt is persisted before starting. An in-flight mutex
prevents duplicate work. Network/Google failures also consume the cooldown.

Only non-debug Google builds installed by `com.android.vending` participate.
Amazon/Fire TV, sideloads and other platforms skip the flow. A 75-second overall
timeout includes Google provider preparation; navigation and playback do not
await this task. It does not prompt users to reinstall or repair their device.

1. Authenticated `POST /me/integrity-monitor/challenge` with `event_type` returns a
   signed five-minute challenge, its SHA-256 request hash, and Cloud project number.
2. The Google Standard API binds its token to this server-issued request hash.
3. `POST /me/integrity-monitor/verdict` sends the challenge and optional token.
4. The backend verifies challenge purpose, expiry, user and installation; asks
   Google to decode the token; verifies request hash/package/time; and checks
   recognized app identity against the server-owned certificate.

Google's Standard replay protection is used; tokens/verdicts are not cached.
Both endpoints are limited to eight requests/hour per user/IP per worker using
the existing in-process limiter. The client cooldown controls routine quota use;
the per-worker limiter is not a distributed quota guarantee.

## Operations and verification

Sanitized results use the `PLAY_INTEGRITY_MONITOR` service log marker:

```sh
journalctl -u torve-backend --since today --no-pager | grep PLAY_INTEGRITY_MONITOR
```

Results contain status and, for valid responses, whitelisted app/licensing/device
labels and certificate-match status. Raw tokens, challenges, account IDs and
Google payloads are not logged by this integration. Ordinary trust headers never
contain integrity tokens. Valid submissions return `accepted: true` and
`mode: monitor_only`, including negative verdicts and Google failures.
Authentication, schema validation and rate limits still apply.

To stop observations, set `PLAY_INTEGRITY_MONITOR_ENABLED=false` in the backend
environment and restart `torve-backend`. Existing clients retain their daily
cooldown. Do not enable access enforcement as part of this rollout.

Standalone tests (no database or production credentials):

```sh
python server/tests/test_play_integrity_monitor_unit.py
python server/tests/test_play_integrity_routes_unit.py
```

Shared tests: `PlayIntegrityMonitorTest` and `TrustSignalsApiContractTest`.
Their MockEngine uses the test scheduler so virtual timeout advancement cannot
race a real HTTP dispatcher.

Final end-to-end acceptance requires a new Google build installed through Play
(an internal track is sufficient), a normal signed-in foreground/login, and an
`observed` backend log with the expected Play recognition/licensing/device results.
An APK installed by ADB intentionally does not exercise this path.

References: [setup](https://developer.android.com/google/play/integrity/setup),
[Standard requests](https://developer.android.com/google/play/integrity/standard).

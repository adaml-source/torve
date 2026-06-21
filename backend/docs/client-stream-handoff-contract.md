# Generic Stream Handoff Contract

This contract covers non-Usenet premium stream paths such as debrid or
Panda-style providers while older clients still have direct provider paths.

## Create Handoff

`POST /resolver/stream/handoff`

Headers:

- `Authorization: Bearer <access token>`
- `X-Torve-Installation-Id: <stable install id>`

Body:

```json
{
  "content_id": "tmdb:123",
  "memory_id": "<backend acceleration memory_id>"
}
```

Legacy clients may send `provider_type` plus exact `source_key`, but new
clients should prefer `memory_id` from `/me/acceleration/sources` or
`/me/acceleration/startup`.

Response:

```json
{
  "url": "https://api.torve.app/resolver/stream/handoff/<token>",
  "is_direct": false,
  "supports_range": true,
  "stream_id": "generic_...",
  "expires_in_seconds": 300
}
```

The URL is short-lived, server-signed, and bound to user, device, content,
stream id, expiry, and `jti`. It is generated from `APP_PUBLIC_API_URL` so it
uses the API host, not the website host. The token does not contain the
upstream URL.

Generic handoff references are stored in shared backend TTL storage keyed by
`stream_id`, not process memory. This keeps playback working when a handoff is
created by one Uvicorn worker and played through another. The upstream URL is
encrypted at rest, expires after roughly 300 seconds, and is never logged or
returned to the client.

## Playback

The player should open `url`. If it expires or returns `stream_expired`, rerun
the resolver flow and request a new handoff. Do not persist raw upstream URLs.

## Errors

Clients must handle:

- `device_required`
- `device_not_authorized`
- `premium_required`
- `rate_limited`
- `stream_expired`
- `stream_reference_required`
- `stream_reference_not_found`
- `stream_handoff_unavailable`

All errors are sanitized. Clients must not display provider internals.

## Compatibility

The backend proxies the stream so range requests continue to work without
returning raw provider URLs. Single-use replay protection is intentionally not
enabled because media players commonly retry and issue multiple range requests.

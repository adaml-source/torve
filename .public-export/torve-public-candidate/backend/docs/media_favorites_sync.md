# Media Favorites Sync

Account media favorites are stored in the backend and exposed through:

- `GET /me/media-favorites`
- `PUT /me/media-favorites/{media_key}`
- `DELETE /me/media-favorites/{media_key}`

The backend is the source of truth. Clients may update the current device UI
optimistically after a PUT or DELETE, then reconcile from the API response.

## Payloads

`PUT /me/media-favorites/{media_key}` accepts media metadata for the favorite:

```json
{
  "media_type": "movie",
  "tmdb_id": 123,
  "imdb_id": "tt1234567",
  "title": "Example",
  "poster_url": "https://...",
  "backdrop_url": "https://...",
  "rating": 7.8,
  "year": 2026,
  "source_device_id": "registered-device-uuid"
}
```

`media_key` must be stable across platforms. Use the same key on desktop,
mobile, and TV so every app reconciles the same favorite.

Responses include a collection `version` and `updated_at`. Clients should store
both and use them to detect whether local favorite state is stale.

All signed-in devices should keep `/me/events` connected. When a
`MEDIA_FAVORITES_UPDATED` event is received, the client should immediately
refetch `GET /me/media-favorites` and replace its local favorites state with
the returned list.

As a fallback, clients should also refetch favorites whenever the app resumes
or reconnects to SSE.

## Client rules

- Never treat favorites as device-only data for signed-in users.
- If one sync category fails, do not wipe favorites; keep the last good local
  favorite cache and retry.
- On 401, refresh once and retry the original favorite request once.
- On 429 or transient 5xx/network errors, keep the local optimistic state marked
  pending and retry with backoff.
- On `MEDIA_FAVORITES_UPDATED`, do not trust the event payload as the favorite
  list; refetch `GET /me/media-favorites`.

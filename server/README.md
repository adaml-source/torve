# Torve Sync Backend (Phase 3)

This service provides:
- Account registration and login with JWT access and rotating refresh tokens.
- Device registration and revocation.
- TV pairing codes and claim flow.
- Authenticated WebSocket channel with offline outbox delivery.

## Local Run

1. Copy environment template:
   - `cp .env.example .env`
2. Start services:
   - `docker compose up --build`
3. Run migrations:
   - `docker compose exec api alembic upgrade head`

For repeat local runs, prefer the repo-level wrapper:
- `.\scripts\dev.ps1 -Target backend-up`
- `.\scripts\dev.ps1 -Target backend-up -BuildImages` only when dependencies or the Docker image changed
- `.\scripts\dev.ps1 -Target backend-migrate`

The API is served on `http://localhost:8080`.

## Phase 2 Migration Note

- Initial schema is in `alembic/versions/0001_phase2_sync_backend.py`.
- This migration creates users, devices, pairing codes, sessions, and event outbox tables.
- Existing deployments must run `alembic upgrade head` before enabling pairing and websocket sync features.

## Core Endpoints

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /pairing/code`
- `POST /pairing/claim`
- `POST /pairing/status`
- `GET /devices`
- `POST /devices/{id}/revoke`
- `POST /events/search_push`
- `POST /events/playback_intent`
- `POST /watch_state/report`
- `GET /health`
- `WS /ws?token=<access-token>`

## Phase 3 Migration Note

- Added migration `alembic/versions/0002_phase3_watch_state_reports.py`.
- Search push and playback handoff events are now available through `/events/*`.
- Player watch progress can be reported through `/watch_state/report`.
- Run `docker compose exec api alembic upgrade head` before enabling Phase 3 clients.

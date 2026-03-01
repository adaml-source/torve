# Torve Sync Backend (Phase 2)

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
- `GET /health`
- `WS /ws?token=<access-token>`

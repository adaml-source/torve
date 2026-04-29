# Torve credential-transfer protocol — v1

Phase 3 Slice B. This document is the single source of truth for the
encrypted device-to-device credential transfer Torve will use to copy a
user's stored secrets from one signed-in device to another over an
untrusted relay.

The client primitives that implement this protocol live in
`shared/src/commonMain/kotlin/com/torve/domain/transfer/` and a JVM
crypto engine in `desktopApp/src/main/kotlin/com/torve/desktop/security/`.
Backend relay endpoints are NOT part of this repo's deliverable — the
spec below is what a Panda/server change must implement to wire the
end-to-end flow.

---

## Goals

- A user signed in on Device A can transfer some of their locally-stored
  secrets to Device B without ever exposing them to the backend, the
  network, the AI providers, the diagnostics export, or any logs.
- The transfer is opt-in per category (debrid / IPTV / Plex/Jellyfin /
  Trakt/SIMKL / AI keys / Panda).
- A transfer envelope expires (default 10 min) and is one-time use.
- Failed import on Device B leaves Device B's existing secrets
  untouched.

## Threat model

The backend relay (if used) is treated as **honest-but-curious**: it can
log envelopes, drop them, replay them, or be compromised. It must NEVER
gain enough information to recover any plaintext credential. The QR
channel between sender and receiver is treated as **trustworthy** for the
duration of the visual exchange — both screens are physically present to
one user. Anyone who can scan the QR is assumed authorized.

## Cryptography

| Step                    | Primitive                       | Notes                                                                 |
| ----------------------- | ------------------------------- | --------------------------------------------------------------------- |
| Receiver key agreement  | X25519 ephemeral key pair       | Generated fresh per session; private key never leaves Device B.       |
| Sender key agreement    | X25519 ephemeral key pair       | Generated fresh per envelope; private key destroyed after sealing.    |
| Shared key derivation   | ECDH (X25519) + HKDF-SHA256     | `info` = `"torve-secrets-transfer-v1"`; output = 32 bytes.            |
| AEAD encryption         | AES-256-GCM, 12-byte random IV  | `associatedData` = envelope header (version + sender pubkey + iv + expiresAt). |
| Random material         | CSPRNG (`SecureRandom` on JVM)  | Used for X25519 keys, AES IVs, and the one-time-use nonce.            |

Why X25519 + HKDF + AES-GCM rather than NaCl/libsodium box: every Torve
target ships a JVM (desktop, Android) with native support for all three
in `java.security` from JDK 11+. iOS gets the same primitives via
CryptoKit (`Curve25519.KeyAgreement`, `HKDF`, `AES.GCM`). No external
crypto dependency required.

## Wire format

`SealedSecretsEnvelope` — a single binary blob, JSON-encoded for the
relay since it carries metadata Pacific to the relay (expiry, sender
device id) that the relay legitimately needs to enforce TTL.

```jsonc
{
  "version": 1,
  "sender_ephemeral_public_key": "<base64url, 32 bytes>",
  "aead_nonce": "<base64url, 12 bytes>",
  "ciphertext": "<base64url, ≥ 16 bytes — payload + GCM tag>",
  "expires_at_epoch_ms": 1735000000000,
  "sender_device_id": "<opaque, ≤ 128 bytes>"
}
```

The `ciphertext` plaintext, decrypted, is `SecretsTransferPayload`:

```jsonc
{
  "version": 1,
  "sender_device_name": "Adam's MacBook Pro",   // optional — never a hostname leak
  "created_at_epoch_ms": 1734999700000,
  "expires_at_epoch_ms": 1735000000000,         // duplicates envelope field — must match exactly
  "transfer_nonce": "<base64url, 16 bytes>",    // one-time-use guard, see below
  "categories": ["DEBRID", "PLEX_JELLYFIN"],
  "secrets": [
    { "category": "DEBRID", "key": "DEBRID_API_KEY_REAL_DEBRID", "value": "rd_..." },
    { "category": "DEBRID", "key": "DEBRID_API_KEY_TORBOX",      "value": "tb_..." },
    { "category": "PLEX_JELLYFIN", "key": "PLEX_ACCESS_TOKEN",   "value": "..." }
  ]
}
```

Categories are `SecretCategory` enum: `DEBRID`, `IPTV`, `PLEX_JELLYFIN`,
`TRAKT_SIMKL`, `AI_KEYS`, `PANDA`. The `key` field is the
`IntegrationSecretKey` enum name on the sender — the receiver maps the
same enum value back into its own `IntegrationSecretStore`. Mapping is
exact; unknown enum names are rejected.

## Lifecycle

```
Device A (sender)              Relay (optional)          Device B (receiver)
─────────────────              ───────────────           ────────────────────
                                                         generateEphemeralKeyPair() → (pubB, privB)
                                                         render QR(pubB, sessionId, expiresAt)
                              POST /transfer/session
                              { session_id, expires_at }
                              ← stores empty session
                                                         (waits for envelope)
scan QR → (pubB, sessionId)
generateEphemeralKeyPair() → (pubA, privA)
deriveSharedKey(privA, pubB)
build SecretsTransferPayload
seal(envelope) → ciphertext
zero(privA)
POST /transfer/session/<id>/envelope
                              { envelope_json }
                              ← stores ONCE; rejects 2nd POST
                                                         GET /transfer/session/<id>/envelope
                                                         ← envelope_json
                                                         deriveSharedKey(privB, pubA)
                                                         open(envelope) → payload
                                                         (validate: nowMs ≤ expires_at, payload.expires_at == envelope.expires_at, nonce not seen)
                                                         write each secret to local IntegrationSecretStore
                                                         atomic: ALL writes succeed or NONE persisted
                                                         POST /transfer/session/<id>/ack
                                                         ← marks session consumed, server deletes envelope
                                                         zero(privB), discard QR
```

### Sender obligations

1. Never write the payload plaintext, `privA`, or the shared key to disk.
2. Zero `privA` and the derived shared key from memory after sealing.
3. Refuse to seal if any selected secret is empty (server-side
   "Saved on server" placeholder) — the user gets a clear "this device
   doesn't have the value yet, transfer from where you typed it"
   message.
4. Sign the user out of nothing — sender state is unchanged by the
   transfer.

### Receiver obligations

1. `privB` lives only in the in-process key holder; never persist.
2. Validate `envelope.expires_at_epoch_ms ≥ now()` BEFORE attempting key
   agreement (cheap reject).
3. After successful AEAD decrypt, validate `payload.expires_at ==
   envelope.expires_at` and `payload.version == 1`.
4. Maintain a per-user, persistent set of consumed `transfer_nonce`s
   for at least 30 days (longer than max envelope TTL × replay-window
   safety factor) and reject any envelope whose nonce is already in
   that set.
5. Atomically apply secrets: all-or-nothing. If any single
   `IntegrationSecretStore.put` throws, roll back every write made
   during this transfer. Existing values for keys NOT in the payload
   are never touched.
6. Append an audit row: `(timestamp, sender_device_id,
   sender_device_name, categories: [...])`. The audit row MUST NOT
   contain any secret value or any plaintext credential. The receiver's
   own user_id is implicit — audit rows are scoped to the local user.
7. Zero `privB` and the derived shared key.

### Relay obligations (when present)

1. The relay accepts a `SealedSecretsEnvelope` JSON blob and a session
   id. It persists it for at most `expires_at - now()` (capped at
   600 seconds).
2. The relay enforces one-time delivery: the second GET on a session
   that's already had a successful ack returns 410 Gone.
3. The relay never inspects, parses, or logs the envelope's
   `ciphertext`. It MAY log: session id, sender ip, target user id,
   timestamp, total byte size, expires_at. It MUST NOT log any base64
   content from the envelope body.
4. The relay rejects envelopes larger than 64 KiB (a sane upper bound
   for a few dozen secrets).
5. After ack OR after expiry, the relay deletes the envelope row.
6. Authentication: both sender and receiver authenticate their
   relay calls with their own Torve JWT. The sender's JWT user-id MUST
   equal the receiver's JWT user-id — credential transfer never crosses
   accounts. (Cross-account relay is a future feature with a different
   trust model.)

### Suggested backend table

```sql
CREATE TABLE transfer_sessions (
    session_id   TEXT PRIMARY KEY,           -- 16-byte URL-safe random
    user_id      UUID NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    envelope     BYTEA,                      -- NULL until sender posts
    consumed_at  TIMESTAMPTZ,                -- NULL until receiver acks
    INDEX        (user_id, expires_at)
);

CREATE TABLE transfer_audit (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL,
    session_id      TEXT NOT NULL,
    direction       TEXT NOT NULL,           -- 'send' | 'receive'
    device_id       TEXT NOT NULL,
    device_name     TEXT,
    categories      TEXT[] NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    INDEX           (user_id, created_at DESC)
);
```

Both tables are vacuumed on a 24h cron to drop expired sessions and
sessions where `consumed_at IS NOT NULL` after a 7-day audit window.

## Acceptance criteria for the protocol

- A clean tampered-envelope test (flip one byte of ciphertext) returns
  `TransferDecryptResult.AuthenticationFailure`.
- A clean expired-envelope test (`expires_at < now()`) returns
  `TransferDecryptResult.Expired`.
- A clean version-mismatch test (envelope `version = 2`) returns
  `TransferDecryptResult.UnsupportedVersion`.
- A clean replay test (same nonce twice through the same receiver state)
  returns `TransferDecryptResult.Replayed` on the second open.
- Round-trip succeeds and `payload.secrets` matches the sender's input
  byte-for-byte.

## Out of scope for v1

- QR rendering / scanning library choice — separate decision.
- Multi-receiver fan-out (sender broadcasting to N devices).
- Asynchronous transfer (sender goes offline before receiver scans).
- Transfer between different Torve accounts.

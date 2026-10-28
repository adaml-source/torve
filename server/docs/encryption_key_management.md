# Encryption Key Management

## INTEGRATION_SECRET_KEY

This Fernet key encrypts all integration credentials, playlist passwords, and other third-party secrets at rest.

**If this key is lost, all encrypted credentials become unrecoverable.** Users would need to re-enter all their integration API keys, Xtream passwords, and service credentials.

### Backup procedure

1. Copy the key value from `.env`:
   ```
   grep INTEGRATION_SECRET_KEY .env
   ```
2. Store it in a secure location separate from the server (password manager, encrypted document, hardware vault).
3. Never commit it to version control.
4. Never share it over unencrypted channels.

### Key rotation

The system supports key rotation via `INTEGRATION_SECRET_KEY_PREVIOUS`:

1. Generate a new key:
   ```
   python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
   ```
2. In `.env`, set:
   ```
   INTEGRATION_SECRET_KEY=<new key>
   INTEGRATION_SECRET_KEY_PREVIOUS=<old key>
   ```
3. Restart the backend. New encryptions use the new key. Decryptions try both.
4. Optionally re-encrypt all rows with the new key, then remove `INTEGRATION_SECRET_KEY_PREVIOUS`.

### Other secrets to back up

| Secret | Purpose | Impact if lost |
|---|---|---|
| `INTEGRATION_SECRET_KEY` | Encrypts credentials at rest | All stored credentials unrecoverable |
| `REBATE_CODE_HMAC_SECRET` | HMAC for rebate code hashing | Existing codes become unredeemable |
| `JWT_SECRET` | Signs access tokens | All sessions invalidated (recoverable) |
| `PADDLE_ADMIN_SECRET` | Admin endpoint auth | Can be regenerated |

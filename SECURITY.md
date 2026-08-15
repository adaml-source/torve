# Security policy

Please report suspected vulnerabilities privately before opening a public
issue. Email `support@torve.app` with the subject prefix `[SECURITY]` and include
the affected Torve version, platform, impact, and minimal reproduction steps.

Do not send real passwords, API keys, refresh tokens, playlist credentials,
private stream URLs, signing material, database exports, or other users’ data.
Use redacted examples or temporary test credentials that can be revoked.

Security reports for the current public release and the current `master`
branch are in scope. Relevant areas include:

- account authentication, password recovery, sessions, and device linking;
- encrypted integration credentials and cross-device synchronization;
- update download, checksum, signature, and installer behavior;
- local network services, URL validation, and server-side request forgery;
- diagnostic redaction, data export, and account deletion;
- unauthorized access across account or device ownership boundaries.

The project will confirm receipt when the reporting channel is available,
investigate without exposing the reporter or users, and coordinate a fix and
disclosure appropriate to the risk. Do not publicly disclose an unresolved
issue that could expose users.

For ordinary bugs and feature requests, use the public issue tracker instead.

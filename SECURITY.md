# Security Policy

## Supported Versions

Security fixes are considered for the current `main` branch. Released builds may lag behind source until maintainers publish platform-specific updates.

## Reporting a Vulnerability

Do not report vulnerabilities in public issues if the report includes exploit details, credentials, tokens, private user data, or operational secrets.

Use GitHub private vulnerability reporting for this repository if it is enabled. If it is not enabled, contact the maintainers through an existing private channel and ask where to send the report.

Do not include real credentials, production secrets, database dumps, logs with tokens, or sensitive user data in reports. Use redacted examples and reproduction steps where possible.

## Scope

Security-sensitive areas include authentication, refresh tokens, account deletion and export, device linking, account-scoped sync, integration secret storage, backend authorization, release/update flows, and any code handling provider credentials.

Reports about paid-tier bypasses, premium gates, donor-only access, or subscription enforcement do not apply to Torve's current product model because Torve has no subscriptions, no paid tiers, no premium features, and no purchase requirement.

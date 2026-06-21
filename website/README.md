# Torve Website

Static source for `https://torve.app`.

The site is built as plain HTML, CSS, and JavaScript so it can be deployed by copying `dist/` to the static web root. It does not deploy itself, upload releases, sign binaries, or change backend state.

## Product Model

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

Login is for sync, device linking, account-backed cloud data, account export requests, account deletion, and other authenticated account services. Login must not become a payment or entitlement gate.

## Local Commands

```bash
npm run lint
npm run typecheck
npm test
npm run build
npm run check:links
npm run check:a11y
npm run check:responsive
```

Optional donation CTA build test:

```bash
TORVE_DONATION_URL=https://example.invalid/donate npm run build
```

When `TORVE_DONATION_URL` is unset, donation CTAs are hidden.

## Deployment

Production deployment is manual and maintainer-only. Build output is written to `dist/`.

Example production command, not executed by this package:

```bash
npm run build && rsync -av --delete dist/ torve@torve.app:/var/www/torve-site/
```

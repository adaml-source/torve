# Torve Billing Operations

Obsolete after free-software conversion. Retained for historical compatibility, refunds, reconciliation, and audit context only.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Current Access Rule

Billing provider state does not control product access. Historical Paddle, Stripe, Google Play, Amazon Appstore, Apple, promo, rebate, lifetime, admin grant, beta grant, and donation records do not unlock or remove product features.

## Legacy Provider Variables

Provider credentials may still appear by variable name in historical code, migrations, tests, or reconciliation tools. Keep real values outside the repository.

Examples of legacy categories:

- Paddle API keys and webhook secrets
- Stripe API keys and webhook secrets
- Google Play service-account files
- Amazon Appstore shared secrets
- App Store Connect keys
- admin reconciliation secrets

These variables are not required for product access.

## Historical Reconciliation

If historical billing records must be reconciled for refunds or support:

1. Use production secrets only from the private secret store.
2. Do not paste values into committed files, logs, issue comments, or terminal transcripts.
3. Treat payment endpoints as record-only or deprecated compatibility surfaces.
4. Do not grant product features based on payment, donation, promo, or supporter status.

## Public Release Requirement

Before publication, confirm that billing docs, env examples, CI, and release scripts do not instruct public contributors to configure production payment credentials.

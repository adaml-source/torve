# Torve Billing Operations

## Current Access Model

Torve backend product access is free by default for authenticated active
accounts. Subscriptions, purchases, premium flags, rebate codes, beta grants,
lifetime ledgers, Stripe state, Paddle state, Google Play receipts, Amazon
receipts, donations, and supporter status do not unlock or remove backend
features.

Payment and entitlement tables remain for historical reconciliation, refunds,
support, and old-client compatibility. They are not an access source of truth.

Remaining backend limits are auth, privacy, ownership, account security,
anti-abuse, or technical stability limits.

## Legacy Environment Variables

These variables are retained only for historical billing records, refund
support, admin billing tools, provider readiness checks, or tests. Keep example
values placeholder-only.

```
PADDLE_ADMIN_SECRET=      # Admin auth for promo/billing/rebate tools
PADDLE_API_KEY=           # Legacy Paddle API key for historical promo tooling
PADDLE_WEBHOOK_SECRET=    # Paddle webhook signature secret
PADDLE_ENVIRONMENT=       # "sandbox" or "production"
PADDLE_PRODUCT_ID=        # Legacy product id
PADDLE_PRICE_ID=          # Legacy price id

STRIPE_SECRET_KEY=        # Used by historical refund flows
STRIPE_WEBHOOK_SECRET=    # Legacy; webhooks are ignored for access
STRIPE_PRICE_MONTHLY=     # Legacy price id
STRIPE_PRICE_LIFETIME=    # Legacy price id
STRIPE_MONTHLY_PRICE_ID=  # Legacy alias
STRIPE_LIFETIME_PRICE_ID= # Legacy alias
STRIPE_TAX_ENABLED=false  # Legacy checkout setting
STRIPE_SUCCESS_URL=
STRIPE_CANCEL_URL=
STRIPE_PORTAL_RETURN_URL=
STRIPE_PUBLISHABLE_KEY=
STRIPE_API_VERSION=
REFUND_REVIEW_EMAIL=support@torve.app

GOOGLE_PLAY_PRODUCT_ID=
GOOGLE_PLAY_PACKAGE_NAME=
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON=
AMAZON_PRODUCT_ID=
AMAZON_APP_SECRET=
```

## Endpoint Behavior

- `GET /me/access-state`: returns free/default access for active accounts. Paid
  entitlement fields are `None`; `beta_access` is informational.
- `GET /me`: keeps legacy booleans for old clients, but access tier is `free`.
- `POST /me/checkout/intent`: returns a deprecated response with
  `checkout_required=false`; it does not create access.
- `POST /billing/stripe/checkout-session`: returns a deprecated response with no
  checkout URL or session id.
- `POST /billing/stripe/portal-session`: returns a deprecated response; the
  portal is no longer required for product access.
- `POST /billing/stripe/webhook`: records the webhook event as ignored for
  free-software access; it does not grant, expire, or revoke features.
- `POST /webhooks/paddle`: verifies the Paddle signature, records payment/refund
  history when possible, and does not grant or revoke features.
- `POST /me/purchases/google-play/verify` and legacy aliases: return a
  deprecated/free-software response and do not call store APIs for access.
- `POST /me/purchases/amazon/verify` and legacy aliases: return a
  deprecated/free-software response and do not grant access.
- `POST /me/purchases/restore`: reports that restore is no longer required and
  does not create entitlements.
- Rebate and admin lifetime/temporary grant endpoints record historical or
  community state only; they do not affect product access.

## Refunds And Reconciliation

Stripe refund request flows can still create refund request records, call Stripe
refund APIs when configured, update historical Stripe purchase/subscription
rows, and send manual review email. They no longer expire or revoke product
access.

Google Play voided-purchase sync and Paddle refund webhooks can still mark
historical payment or entitlement rows as refunded, expired, or revoked for
audit. Product access remains free for active accounts.

Useful admin views:

```
GET /admin/billing/reconcile
GET /admin/billing/payments?status=completed&limit=50
GET /admin/billing/payments?unlinked=true
GET /admin/billing/payments?status=refunded
GET /admin/billing/entitlements?status=active
GET /admin/billing/entitlements?status=revoked
POST /admin/billing/stripe-refund-requests/{request_id}/approve
```

## Troubleshooting

### User cannot use a feature

Check authentication, account active state, ownership, device registration for
device-scoped secret restore or stream handoff, privacy boundaries, rate limits,
and backend availability. Do not troubleshoot paid entitlement state as an access
gate.

### Historical payment exists but access state is free

This is expected. Payments and entitlements are historical/informational only.

### Webhook not arriving

Check provider dashboard delivery logs, route configuration, and webhook secret
configuration for audit completeness. Missing webhooks do not block product
access.

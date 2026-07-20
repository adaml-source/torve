# Stripe Web Billing

Stripe is the web checkout provider for Torve Premium Access. Premium access is software functionality only: account access, cross-device sync, device management, and enhanced app features. Torve does not sell, host, provide, bundle, license, or resell movies, live TV, IPTV subscriptions, streams, playlists, or third-party digital content.

## Environment

Required:

```bash
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_PRICE_MONTHLY=price_...
STRIPE_PRICE_LIFETIME=price_...
# Accepted aliases if a client rollout guide uses these names:
# STRIPE_MONTHLY_PRICE_ID=price_...
# STRIPE_LIFETIME_PRICE_ID=price_...
STRIPE_TAX_ENABLED=true
STRIPE_SUCCESS_URL=https://torve.app/billing/success/
STRIPE_CANCEL_URL=https://torve.app/billing/cancel/
STRIPE_PORTAL_RETURN_URL=https://torve.app/account/billing/
```

Optional:

```bash
STRIPE_PUBLISHABLE_KEY=pk_live_...
STRIPE_API_VERSION=
REFUND_REVIEW_EMAIL=support@torve.app
```

Validation:

- `STRIPE_SECRET_KEY` must start with `sk_test_` or `sk_live_`.
- `STRIPE_WEBHOOK_SECRET` must start with `whsec_`.
- Monthly and lifetime price IDs must start with `price_`.
- Prefer `STRIPE_PRICE_MONTHLY` and `STRIPE_PRICE_LIFETIME`; the backend also
  accepts `STRIPE_MONTHLY_PRICE_ID` and `STRIPE_LIFETIME_PRICE_ID` as aliases.
- `STRIPE_TAX_ENABLED` defaults to `false`; production should run with it set to `true`.
- Secret values are never returned from health endpoints.

## Dashboard Setup

1. Create a Stripe product for Torve Premium Access.
2. Create one recurring monthly price and one one-time lifetime price.
3. Put the monthly price ID in `STRIPE_PRICE_MONTHLY`.
4. Put the lifetime price ID in `STRIPE_PRICE_LIFETIME`.
   - Alias names `STRIPE_MONTHLY_PRICE_ID` and `STRIPE_LIFETIME_PRICE_ID` are
     also accepted, but keep production docs on `STRIPE_PRICE_*`.
5. Enable Stripe Tax for the account and set `STRIPE_TAX_ENABLED=true`.
6. Enable Customer Portal in the Stripe dashboard so subscribers can manage payment methods, invoices, and cancellation.
7. Create a webhook endpoint pointing to `https://api.torve.app/billing/stripe/webhook`.

Selected webhook events:

- `checkout.session.completed`
- `checkout.session.async_payment_succeeded`
- `checkout.session.async_payment_failed`
- `invoice.paid`
- `invoice.payment_failed`
- `customer.subscription.created`
- `customer.subscription.updated`
- `customer.subscription.deleted`
- `charge.refunded`
- `charge.dispute.created`

## Backend Endpoints

Authenticated checkout:

```http
POST /billing/stripe/checkout-session
Authorization: Bearer <token>
Content-Type: application/json

{"purchase_type":"monthly"}
```

`purchase_type` can be `monthly` or `lifetime`. The backend maps that value to the configured Stripe price ID. Clients must not send price IDs or trusted metadata.

Response:

```json
{
  "checkout_url": "https://checkout.stripe.com/...",
  "session_id": "cs_..."
}
```

Authenticated customer portal:

```http
POST /billing/stripe/portal-session
Authorization: Bearer <token>
```

Response:

```json
{
  "portal_url": "https://billing.stripe.com/..."
}
```

Authenticated refund request:

```http
POST /billing/stripe/refund-request
Authorization: Bearer <token>
Content-Type: application/json

{"purchase_type":"monthly","reason":"optional customer note"}
```

The backend auto-approves only eligible first website Premium purchases within
the goodwill refund window. Monthly renewals, repeat goodwill attempts, missing
payment references, and unusual patterns are recorded for manual review. Refund
responses are sanitized:

```json
{
  "status": "approved",
  "request_id": "...",
  "message": "Your refund was approved and is being processed."
}
```

Manual-review requests send a non-blocking internal notification to
`REFUND_REVIEW_EMAIL` using the existing Resend mail path. The subject includes
the refund request ID, purchase type, and policy reason. The message includes
safe review context only: request ID, user ID/email, purchase type, policy
reason, Stripe customer ID, and the customer's note. It does not include raw
payment details, payment fingerprints, card data, provider tokens, or raw Stripe
payloads.

Admin manual approval:

```http
POST /admin/billing/stripe-refund-requests/{request_id}/approve
X-Admin-Secret: <admin secret>
```

This endpoint calls Stripe's Refund API for a stored manual-review request.
If Stripe accepts the refund, the backend marks the request approved and
immediately revokes or expires only the matching `source="stripe"` entitlement.
Requests without a payment intent or charge are rejected with
`stripe_refund_missing_payment_reference` and must be handled directly in Stripe
or support ops.

Unauthenticated webhook:

```http
POST /billing/stripe/webhook
Stripe-Signature: ...
```

The webhook handler verifies the signature against the raw request body before parsing JSON. Entitlements are granted only after a verified webhook event.

## Entitlement Behavior

Stripe records converge through the canonical `user_entitlements` resolver:

- Lifetime paid checkout creates an active `source="stripe"` lifetime entitlement.
- Monthly subscription invoices and active/trialing subscription updates create or maintain a `source="stripe"` monthly subscription entitlement through `current_period_end`.
- Subscription updates with `cancel_at_period_end=true` keep the Stripe subscription entitlement active until `current_period_end`.
- Subscription deletion expires only the matching Stripe subscription entitlement.
- Stripe refunds immediately revoke or expire only the matching Stripe entitlement. This includes refunds created through the Torve backend and refunds created directly in Stripe that arrive through `charge.refunded`.
- Other active sources such as Google Play, Amazon, admin grants, rebate codes, and legacy Paddle records continue to grant access independently.

Checkout session creation never grants premium access. Device allowance changes only after a verified Stripe webhook updates the canonical entitlement state.

## Health Readiness

`/me/health/integrations` includes `stripe_readiness`:

```json
{
  "configured": true,
  "secret_key_present": true,
  "webhook_secret_present": true,
  "monthly_price_present": true,
  "lifetime_price_present": true,
  "tax_enabled": true
}
```

No secret values are exposed.

## Local Webhook Testing

Use the Stripe CLI against a local backend:

```bash
stripe listen --forward-to localhost:8000/billing/stripe/webhook
```

Copy the `whsec_...` value from the CLI output into `STRIPE_WEBHOOK_SECRET`, then trigger events from the Stripe dashboard or CLI.

## Security Notes

- Do not commit Stripe secret keys or webhook secrets.
- Do not log raw webhook payloads, full customer emails, payment method details, secret keys, or webhook secrets.
- Never trust client-submitted price IDs or metadata.
- Grant entitlements only from verified webhook events.
- Treat unknown webhook events as ignored and acknowledge them with `200`.
- Client-facing errors use stable sanitized codes such as `stripe_not_configured`, `stripe_checkout_failed`, `stripe_customer_missing`, `stripe_portal_failed`, `stripe_invalid_purchase_type`, `stripe_price_mismatch`, `stripe_webhook_signature_invalid`, and `stripe_webhook_processing_failed`.

## Production Checklist

- `STRIPE_SECRET_KEY` is a live `sk_live_...` key.
- `STRIPE_WEBHOOK_SECRET` matches the production webhook endpoint.
- Monthly and lifetime env vars contain live `price_...` IDs.
- Stripe Tax is enabled in Stripe and `STRIPE_TAX_ENABLED=true`.
- Customer Portal is enabled.
- Webhook endpoint is reachable at `https://api.torve.app/billing/stripe/webhook`.
- Webhook events above are selected.
- `alembic upgrade head` has run.
- The backend has been restarted after env/code changes.

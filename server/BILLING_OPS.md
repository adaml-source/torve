# Torve Billing Operations

## Required Environment Variables

```
PADDLE_API_KEY=           # Paddle API key (from Paddle dashboard > Developer Tools > API Keys)
PADDLE_WEBHOOK_SECRET=    # Webhook signing secret (from Paddle > Notifications > webhook endpoint)
PADDLE_ENVIRONMENT=       # "sandbox" or "production"
PADDLE_PRODUCT_ID=        # Paddle product ID for Torve Lifetime Access (pro_xxxxx)
PADDLE_PRICE_ID=          # Paddle one-time price ID for lifetime access (pri_xxxxx)
PADDLE_ADMIN_SECRET=      # Strong random secret for admin promo/billing endpoints

# Google Play in-app purchase verification
GOOGLE_PLAY_PRODUCT_ID=   # Exact product ID (e.g., com.torve.lifetime)
GOOGLE_PLAY_PACKAGE_NAME= # Android package name (e.g., com.torve.android)
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON= # Path to Google service account JSON file

# Amazon Appstore in-app purchase verification
AMAZON_PRODUCT_ID=        # Exact product ID for lifetime access
AMAZON_APP_SECRET=        # Amazon developer shared secret for RVS
```

All Paddle vars must be set before web checkout goes live. All Google Play vars must be set before GP purchase verification works. All Amazon vars must be set before Amazon verification works.

**Production behavior**: In `APP_ENV=production`, missing store product IDs cause all verification to fail closed (no entitlement granted). In non-production, missing product IDs log warnings but allow development testing.

## Paddle Dashboard Setup (Manual)

1. Create product "Torve Lifetime Access" in Paddle catalog.
2. Create a one-time price for the product.
3. Copy product_id and price_id to env vars.
4. Create a webhook notification endpoint pointing to `https://api.torve.app/webhooks/paddle`.
5. Subscribe the webhook to at minimum: `transaction.completed`, `transaction.updated`.
6. Copy the webhook signing secret to `PADDLE_WEBHOOK_SECRET`.
7. Configure checkout client token in Paddle dashboard for frontend Paddle.js.

## Webhook Endpoint

`POST /webhooks/paddle` on `api.torve.app`

Handled events:
- `transaction.completed`: records payment, grants lifetime entitlement if product matches and user is linked.
- `transaction.updated` (status: refunded, partially_refunded, reversed): revokes entitlement, updates payment status.
- `transaction.payment_failed`: logged, acknowledged.
- All other events: acknowledged, ignored.

Signature verification: HMAC-SHA256 using raw request body and Paddle-Signature header.

## Refund / Reversal Behavior

When Paddle sends a refund event:
1. The web_payments record is updated to status="refunded" with refunded_at timestamp.
2. The corresponding user_entitlements record is set to status="revoked".
3. user.has_lifetime_access is recomputed from remaining active entitlements.
4. If the user has another active entitlement (e.g., from a second purchase or admin grant), access remains.
5. If no active entitlements remain, has_lifetime_access becomes false.

`partially_refunded` is treated the same as `refunded` (full revocation). This is a conservative choice. If partial refunds should retain access, update the status check in `_handle_updated`.

## Purchase Intent (Account Binding)

The website must create a purchase intent before opening Paddle checkout:

1. Frontend calls `POST /me/checkout/intent` (authenticated).
2. Backend creates a PurchaseIntent record with user_id, product/price, and 60-minute expiry.
3. Frontend passes `intent_id` into Paddle checkout as `custom_data.torve_purchase_intent`.
4. Webhook resolves user linkage from the intent.

Fallback: if no intent is found, the webhook checks `custom_data.torve_user_id`. This is less secure and logged as a warning.

## Promo Code Administration

All admin endpoints require `X-Admin-Secret` header matching `PADDLE_ADMIN_SECRET`.

### Create a single code
```
POST /admin/promo/create
{
  "discount_percent": 100,
  "intended_for": "John Doe",
  "internal_note": "Friend of founder",
  "expires_at": "2026-12-31T23:59:59Z"  // optional
}
```

### Generate batch codes
```
POST /admin/promo/batch
{
  "count": 10,
  "discount_percent": 50,
  "internal_note": "Launch promo batch"
}
```

### List all codes
```
GET /admin/promo/list
```

### Disable a code
```
POST /admin/promo/{code_id}/disable
```

All codes are single-use (usage_limit=1) and restricted to the Torve Lifetime Access price.

## Reconciliation

### Quick summary
```
GET /admin/billing/reconcile
```
Returns counts of payments, entitlements, refunds, and unlinked transactions.

### Detailed payment list
```
GET /admin/billing/payments?status=completed&limit=50
GET /admin/billing/payments?unlinked=true
GET /admin/billing/payments?status=refunded
```

### Entitlement list
```
GET /admin/billing/entitlements?status=active
GET /admin/billing/entitlements?status=revoked
```

## Troubleshooting

### User paid but no access
1. Check `/admin/billing/payments` for the transaction. If `user_id` is null, the purchase intent failed or was not passed.
2. Manually grant access: create a user_entitlements row with source="admin_grant".

### Refund processed but user still has access
1. Check if user has multiple entitlements via `/admin/billing/entitlements`.
2. The system only revokes the entitlement matching the refunded transaction. Other entitlements keep access active.

### Webhook not arriving
1. Verify Paddle webhook URL is `https://api.torve.app/webhooks/paddle`.
2. Check nginx passes the route correctly (no rate limiting on this path).
3. Check Paddle dashboard notification logs.
4. Verify `PADDLE_WEBHOOK_SECRET` matches between Paddle and backend env.

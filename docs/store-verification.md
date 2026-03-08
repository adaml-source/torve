# Store Purchase Verification

## Apple App Store

### How it works
1. App completes StoreKit 2 purchase
2. StoreKit returns a `VerificationResult<Transaction>` containing a JWS-signed transaction
3. App extracts the `jwsRepresentation` string and sends it to POST /purchases/apple/verify
4. Backend decodes the JWS payload (base64url-encoded JSON)
5. Backend extracts: transactionId, originalTransactionId, productId, bundleId, purchaseDate
6. Backend validates bundleId matches configured APPLE_BUNDLE_ID
7. If APPLE_ISSUER_ID and APPLE_KEY_ID are configured, backend optionally verifies via App Store Server API v2
8. On success, creates purchase record and grants entitlement

### Required setup
- `APPLE_BUNDLE_ID`: "com.torve.app" (default, must match App Store)
- `APPLE_ISSUER_ID`: From App Store Connect → Keys → In-App Purchase (optional for v1)
- `APPLE_KEY_ID`: Key ID for the App Store Connect API key (optional for v1)
- `APPLE_PRIVATE_KEY_PATH`: Path to .p8 private key file (optional for v1)

### Product IDs
- `com.torve.pro.lifetime` → entitlement: torve_pro_lifetime
- `com.torve.pro.monthly` → entitlement: torve_pro_monthly

## Google Play

### How it works
1. App completes Play Billing purchase
2. Play Billing returns purchaseToken
3. App sends product_id + purchase_token to POST /purchases/google/verify
4. Backend calls Google Play Developer API v3:
   `GET androidpublisher/v3/applications/{package}/purchases/products/{productId}/tokens/{token}`
5. Backend checks purchaseState == 0 (Purchased)
6. On success, creates purchase record and grants entitlement

### Required setup
- `GOOGLE_PACKAGE_NAME`: "com.torve.app" (default)
- `GOOGLE_SERVICE_ACCOUNT_JSON`: Full JSON content of the Google Cloud service account key
  - Create in Google Cloud Console → IAM → Service Accounts
  - Grant role: "Android Publisher" or custom with androidpublisher scope
  - Download JSON key
  - Set as env var (entire JSON string, not a file path)

### Dev mode
If `GOOGLE_SERVICE_ACCOUNT_JSON` is empty, the backend accepts purchases on trust (logs a warning).

## Amazon Appstore

### How it works
1. App completes Amazon IAP purchase
2. Amazon returns receipt ID and amazon user ID
3. App sends receipt_id + amazon_user_id + product_id to POST /purchases/amazon/verify
4. Backend calls Amazon Receipt Verification Service (RVS):
   `GET /version/1.0/verifyReceiptId/developer/{secret}/user/{userId}/receiptId/{receiptId}`
5. Backend checks: correct productId, no cancelDate
6. On success, creates purchase record and grants entitlement

### Required setup
- `AMAZON_SHARED_SECRET`: From Amazon Developer Console → App → In-App Items → Shared Secret
- `AMAZON_RVS_SANDBOX`: Set to "true" during development (uses sandbox RVS endpoint)

### Product IDs
- `com.torve.pro.lifetime.amazon` → entitlement: torve_pro_lifetime

### Dev mode
If `AMAZON_SHARED_SECRET` is empty, the backend accepts purchases on trust (logs a warning).

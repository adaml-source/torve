# Store Purchase Verification

Obsolete after free-software conversion. Retained for historical compatibility and reconciliation context only.

Torve no longer uses StoreKit, Google Play Billing, Amazon IAP, Stripe, Paddle, checkout state, restore state, receipts, product IDs, purchase tokens, or entitlements to unlock product features.

Current behavior:

- iOS App Store builds do not require StoreKit purchase or restore flows.
- Google Play mobile and Google TV builds do not require Play Billing.
- Fire TV builds do not require Amazon IAP or Stripe checkout.
- Desktop does not require Stripe checkout, a customer portal, or paid license activation.
- Backend verification endpoints, if retained, are deprecated or record-only compatibility surfaces.

External store cleanup remains required before public release. See [store-metadata-cleanup-checklist.md](store-metadata-cleanup-checklist.md).

Secrets such as App Store Connect keys, Google service-account JSON, Amazon shared secrets, Stripe keys, Paddle keys, and webhook secrets must remain outside the repository. If any of those values were exposed or copied into archives, rotate them before publication.

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    APP_ENV: str = "production"
    APP_HOST: str = "127.0.0.1"
    APP_PORT: int = 8000

    DATABASE_URL: str

    JWT_SECRET: str
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 90

    PASSWORD_RESET_TOKEN_EXPIRE_MINUTES: int = 60
    EMAIL_VERIFICATION_TOKEN_EXPIRE_HOURS: int = 48
    MAX_DEVICES_PER_ACCOUNT: int = 5
    RESEND_API_KEY: str = ""
    MAIL_FROM: str = "noreply@torve.app"
    SUPPORT_EMAIL: str = "support@torve.app"
    REFUND_REVIEW_EMAIL: str = "support@torve.app"
    APP_PUBLIC_WEB_URL: str = "https://www.torve.app"
    APP_PUBLIC_API_URL: str = "https://api.torve.app"
    FOUNDER_LIFETIME_CAP: int = 500

    # Discord notifications
    DISCORD_RELEASE_WEBHOOK_URL: str = ""
    DISCORD_RULES_WEBHOOK_URL: str = ""
    DISCORD_RULES_MESSAGE_ID: str = ""
    DISCORD_FAQ_WEBHOOK_URL: str = ""
    DISCORD_FAQ_MESSAGE_ID: str = ""
    DISCORD_DOWNLOADS_WEBHOOK_URL: str = ""
    DISCORD_DOWNLOADS_MESSAGE_ID: str = ""
    DISCORD_BETA_INFO_WEBHOOK_URL: str = ""
    DISCORD_BETA_INFO_MESSAGE_ID: str = ""
    DISCORD_STATIC_MESSAGES_FILE: str = "/var/lib/torve-backend/discord_static_messages.json"
    TORVE_PUBLIC_DOWNLOAD_URL: str = "https://torve.app/download.html"
    DISCORD_BOT_TOKEN: str = ""
    DISCORD_PUBLIC_KEY: str = ""
    DISCORD_GUILD_ID: str = ""
    DISCORD_BETA_TESTER_ROLE_ID: str = ""
    DISCORD_BETA_APPLICATION_CHANNEL_ID: str = ""
    DISCORD_BETA_APPLICATION_MESSAGE_ID: str = ""
    DISCORD_BETA_REVIEW_CHANNEL_ID: str = ""
    DISCORD_BETA_AUTO_APPROVE: bool = False
    DISCORD_BETA_REVIEWER_ROLE_IDS: str = ""
    DISCORD_BETA_REVIEWER_USER_IDS: str = ""
    TORVE_BETA_GRANT_DAYS: int = 30
    BETA_SIGNUP_CLOSE_AT: str = "2026-07-01T23:59:59+02:00"
    BETA_FREE_ACCESS_END_AT: str = "2026-07-31T23:59:59+02:00"

    # Encryption key for integration secrets at rest (Fernet, 32-byte base64)
    INTEGRATION_SECRET_KEY: str = ""
    # Previous key for rotation: set when rotating, remove after re-encryption
    INTEGRATION_SECRET_KEY_PREVIOUS: str = ""

    # Paddle billing
    PADDLE_API_KEY: str = ""
    PADDLE_WEBHOOK_SECRET: str = ""
    PADDLE_ENVIRONMENT: str = "sandbox"  # sandbox or production
    PADDLE_PRODUCT_ID: str = ""  # Torve Lifetime Access product
    PADDLE_PRICE_ID: str = ""    # One-time price for lifetime access
    PADDLE_SUBSCRIPTION_PRICE_ID: str = ""  # Monthly subscription price
    PADDLE_ADMIN_SECRET: str = ""  # Secret for admin promo endpoints

    # Stripe billing for web checkout
    STRIPE_SECRET_KEY: str = ""
    STRIPE_WEBHOOK_SECRET: str = ""
    STRIPE_PUBLISHABLE_KEY: str = ""
    STRIPE_API_VERSION: str = ""
    STRIPE_PRICE_MONTHLY: str = ""
    STRIPE_PRICE_LIFETIME: str = ""
    # Legacy/user-facing aliases captured during Stripe setup. Prefer
    # STRIPE_PRICE_* in new docs, but keep these accepted so client rollout
    # guides and existing server env files do not fail Settings validation.
    STRIPE_MONTHLY_PRICE_ID: str = ""
    STRIPE_LIFETIME_PRICE_ID: str = ""
    STRIPE_PREMIUM_PRODUCT_ID: str = ""
    STRIPE_PREMIUM_MONTHLY_LOOKUP_KEY: str = ""
    STRIPE_PREMIUM_MONTHLY_PRICE_ID: str = ""
    STRIPE_PREMIUM_LIFETIME_LOOKUP_KEY: str = ""
    STRIPE_PREMIUM_LIFETIME_PRICE_ID: str = ""
    STRIPE_TAX_ENABLED: bool = False
    STRIPE_SUCCESS_URL: str = "https://torve.app/billing/success/"
    STRIPE_CANCEL_URL: str = "https://torve.app/billing/cancel/"
    STRIPE_PORTAL_RETURN_URL: str = "https://torve.app/account/billing/"

    # In-app purchase verification
    # Google Play
    GOOGLE_PLAY_PACKAGE_NAME: str = ""
    GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: str = ""
    GOOGLE_PLAY_LIFETIME_PRODUCT_ID: str = ""      # Managed product for lifetime
    GOOGLE_PLAY_SUBSCRIPTION_ID: str = ""           # Subscription ID for monthly
    GOOGLE_PLAY_SIGNING_CERT_SHA256: str = ""        # Optional Play Integrity cert digest
    # Amazon
    AMAZON_LIFETIME_PRODUCT_ID: str = ""            # Amazon lifetime SKU
    AMAZON_SUBSCRIPTION_PRODUCT_ID: str = "com.torve.pro.subscription"
    AMAZON_MONTHLY_PRODUCT_ID: str = "com.torve.pro.monthly"
    AMAZON_APP_SECRET: str = ""

    # Legacy aliases (backward compat, prefer the new names above)
    GOOGLE_PLAY_PRODUCT_ID: str = ""
    AMAZON_PRODUCT_ID: str = ""

    # Rebate codes
    REBATE_CODE_HMAC_SECRET: str = ""  # Dedicated HMAC key for rebate code hashing
    REFUND_ABUSE_HMAC_SECRET: str = ""  # Optional HMAC key for refund abuse signal hashing

    # Admin endpoint hardening (optional, empty = disabled)
    ADMIN_IP_ALLOWLIST: str = ""  # Comma-separated IPs/CIDRs, e.g. "10.0.0.1,192.168.1.0/24"

    # Google Play reviewer access
    GOOGLE_PLAY_REVIEW_ENABLED: bool = False
    GOOGLE_PLAY_REVIEW_EMAIL: str = "review@torve.app"
    GOOGLE_PLAY_REVIEW_PASSWORD: str = ""
    GOOGLE_PLAY_REVIEW_DISPLAY_NAME: str = "Google Play Reviewer"

    # Error reporting. Leave SENTRY_DSN empty to disable (no-op).
    # When set, sentry_sdk is initialised at app startup. No PII is sent;
    # request bodies are redacted via the existing FastAPI validation
    # handler, and the SDK's default scrubbing covers passwords / tokens.
    SENTRY_DSN: str = ""
    SENTRY_ENVIRONMENT: str = ""              # falls back to APP_ENV if empty
    SENTRY_TRACES_SAMPLE_RATE: float = 0.0    # 0.0 = errors only, no perf
    SENTRY_RELEASE: str = ""                  # optional release tag

    # NzbDAV upstream streaming integration
    NZBDAV_ALLOW_PRIVATE_HOSTS: bool = False
    NZBDAV_HANDOFF_SECRET: str = ""          # auto-generated if empty
    NZBDAV_HANDOFF_DIRECT_REDIRECT: bool = False
    NZBDAV_WARM_CONCURRENCY_PER_USER: int = 4
    NZBDAV_WARM_SUCCESS_TTL_SECONDS: int = 900
    NZBDAV_DEAD_RELEASE_TTL_SECONDS: int = 21600
    NZBDAV_MIN_KNOWN_GOOD: str = "0.0.0"

    @property
    def google_play_lifetime_id(self) -> str:
        return self.GOOGLE_PLAY_LIFETIME_PRODUCT_ID or self.GOOGLE_PLAY_PRODUCT_ID

    @property
    def amazon_lifetime_id(self) -> str:
        return self.AMAZON_LIFETIME_PRODUCT_ID or self.AMAZON_PRODUCT_ID

    @property
    def stripe_monthly_price_id(self) -> str:
        return (
            self.STRIPE_PRICE_MONTHLY
            or self.STRIPE_MONTHLY_PRICE_ID
            or self.STRIPE_PREMIUM_MONTHLY_PRICE_ID
        )

    @property
    def stripe_lifetime_price_id(self) -> str:
        return (
            self.STRIPE_PRICE_LIFETIME
            or self.STRIPE_LIFETIME_PRICE_ID
            or self.STRIPE_PREMIUM_LIFETIME_PRICE_ID
        )

    @property
    def all_google_play_product_ids(self) -> set[str]:
        ids = set()
        if self.google_play_lifetime_id:
            ids.add(self.google_play_lifetime_id)
        if self.GOOGLE_PLAY_SUBSCRIPTION_ID:
            ids.add(self.GOOGLE_PLAY_SUBSCRIPTION_ID)
        return ids

    @property
    def all_amazon_product_ids(self) -> set[str]:
        ids = set()
        if self.amazon_lifetime_id:
            ids.add(self.amazon_lifetime_id)
        if self.AMAZON_SUBSCRIPTION_PRODUCT_ID:
            ids.add(self.AMAZON_SUBSCRIPTION_PRODUCT_ID)
        if self.AMAZON_MONTHLY_PRODUCT_ID:
            ids.add(self.AMAZON_MONTHLY_PRODUCT_ID)
        return ids


settings = Settings()

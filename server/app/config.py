from functools import lru_cache
from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = Field(default="Torve Sync Server", alias="APP_NAME")
    api_host: str = Field(default="0.0.0.0", alias="API_HOST")
    api_port: int = Field(default=8080, alias="API_PORT")
    debug: bool = Field(default=False, alias="DEBUG")

    database_url: str = Field(alias="DATABASE_URL")
    redis_url: str = Field(alias="REDIS_URL")

    jwt_secret: str = Field(alias="JWT_SECRET")
    jwt_issuer: str = Field(default="torve-sync", alias="JWT_ISSUER")
    access_token_ttl_minutes: int = Field(default=15, alias="ACCESS_TOKEN_TTL_MINUTES")
    refresh_token_ttl_days: int = Field(default=30, alias="REFRESH_TOKEN_TTL_DAYS")
    pairing_code_ttl_minutes: int = Field(default=10, alias="PAIRING_CODE_TTL_MINUTES")

    # Apple App Store verification
    apple_bundle_id: str = Field(default="com.torve.app", alias="APPLE_BUNDLE_ID")
    apple_issuer_id: str = Field(default="", alias="APPLE_ISSUER_ID")
    apple_key_id: str = Field(default="", alias="APPLE_KEY_ID")
    apple_private_key_path: str = Field(default="", alias="APPLE_PRIVATE_KEY_PATH")

    # Google Play verification
    google_package_name: str = Field(default="com.torve.app", alias="GOOGLE_PACKAGE_NAME")
    google_service_account_json: str = Field(default="", alias="GOOGLE_SERVICE_ACCOUNT_JSON")

    # Amazon verification
    amazon_shared_secret: str = Field(default="", alias="AMAZON_SHARED_SECRET")
    amazon_rvs_sandbox: bool = Field(default=False, alias="AMAZON_RVS_SANDBOX")

    # Device governance
    device_max_active: int = Field(default=5, alias="DEVICE_MAX_ACTIVE")
    device_stale_days: int = Field(default=45, alias="DEVICE_STALE_DAYS")
    device_max_swaps_per_30d: int = Field(default=3, alias="DEVICE_MAX_SWAPS_PER_30D")

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


@lru_cache
def get_settings() -> Settings:
    return Settings()

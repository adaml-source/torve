from datetime import datetime
from pydantic import BaseModel, EmailStr, Field


class DeviceRegistration(BaseModel):
    installation_id: str = Field(min_length=8, max_length=128)
    device_name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(min_length=1, max_length=40)
    platform: str = Field(min_length=1, max_length=40)


class AuthRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=256)
    device: DeviceRegistration


class AuthLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=256)
    device: DeviceRegistration


class AuthRefreshRequest(BaseModel):
    refresh_token: str


class AuthLogoutRequest(BaseModel):
    refresh_token: str | None = None


class PairingCodeRequest(BaseModel):
    installation_id: str = Field(min_length=8, max_length=128)
    device_name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(min_length=1, max_length=40)
    platform: str = Field(min_length=1, max_length=40)


class PairingClaimRequest(BaseModel):
    code: str = Field(min_length=4, max_length=12)


class PairingStatusRequest(BaseModel):
    code: str = Field(min_length=4, max_length=12)
    installation_id: str = Field(min_length=8, max_length=128)


class UserResponse(BaseModel):
    id: str
    email: str
    created_at: datetime


class DeviceResponse(BaseModel):
    id: str
    installation_id: str
    device_name: str
    device_type: str
    platform: str
    last_seen_at: datetime
    revoked_at: datetime | None = None


class TokensResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class AuthResponse(BaseModel):
    user: UserResponse
    device: DeviceResponse
    tokens: TokensResponse


class PairingCodeResponse(BaseModel):
    code: str
    expires_at: datetime


class PairingStatusResponse(BaseModel):
    status: str
    paired_device: DeviceResponse | None = None
    user: UserResponse | None = None
    tokens: TokensResponse | None = None


class PairingClaimResponse(BaseModel):
    status: str
    device: DeviceResponse


class HealthResponse(BaseModel):
    status: str
    database: str
    redis: str

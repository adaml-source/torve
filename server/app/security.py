import hashlib
import uuid
from datetime import datetime, timedelta, timezone
from jose import JWTError, jwt
from passlib.context import CryptContext
from .config import get_settings


pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
settings = get_settings()


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    return pwd_context.verify(password, password_hash)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_access_token(user_id: str, device_id: str) -> tuple[str, int]:
    now = datetime.now(timezone.utc)
    expires_delta = timedelta(minutes=settings.access_token_ttl_minutes)
    payload = {
        "sub": user_id,
        "device_id": device_id,
        "type": "access",
        "iat": int(now.timestamp()),
        "exp": int((now + expires_delta).timestamp()),
        "iss": settings.jwt_issuer,
        "jti": str(uuid.uuid4()),
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm="HS256")
    return token, int(expires_delta.total_seconds())


def create_refresh_token(user_id: str, device_id: str) -> tuple[str, datetime]:
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(days=settings.refresh_token_ttl_days)
    payload = {
        "sub": user_id,
        "device_id": device_id,
        "type": "refresh",
        "iat": int(now.timestamp()),
        "exp": int(expires_at.timestamp()),
        "iss": settings.jwt_issuer,
        "jti": str(uuid.uuid4()),
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm="HS256")
    return token, expires_at


def decode_token(token: str, expected_type: str) -> dict:
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=["HS256"],
            options={"verify_aud": False},
            issuer=settings.jwt_issuer,
        )
    except JWTError as exc:
        raise ValueError("Invalid token") from exc

    token_type = payload.get("type")
    if token_type != expected_type:
        raise ValueError("Invalid token type")
    if not payload.get("sub"):
        raise ValueError("Invalid token subject")
    if not payload.get("device_id"):
        raise ValueError("Invalid token device")
    return payload

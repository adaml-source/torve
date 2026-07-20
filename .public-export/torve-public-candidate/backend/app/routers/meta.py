"""
Public metadata endpoints.

These return app configuration that clients need but that does not require
authentication. The AI provider registry is the canonical source of truth
for which providers are supported, their labels, and display metadata.
"""

from fastapi import APIRouter

router = APIRouter(prefix="/meta", tags=["meta"])

# ── AI Provider Registry ─────────────────────────────────────────────────
# Single source of truth. Both website and app should consume this.
# Order matters: sort_order determines display order in UI.

AI_PROVIDERS = [
    {
        "value": "CHATGPT",
        "label": "ChatGPT",
        "api_key_placeholder": "sk-...",
        "enabled": True,
        "recommended": True,
        "sort_order": 1,
        "advanced": False,
    },
    {
        "value": "GEMINI",
        "label": "Gemini",
        "api_key_placeholder": "Your Gemini API key",
        "enabled": True,
        "recommended": True,
        "sort_order": 2,
        "advanced": False,
    },
    {
        "value": "CLAUDE",
        "label": "Claude",
        "api_key_placeholder": "sk-ant-...",
        "enabled": True,
        "recommended": False,
        "sort_order": 3,
        "advanced": False,
    },
    {
        "value": "PERPLEXITY",
        "label": "Perplexity",
        "api_key_placeholder": "pplx-...",
        "enabled": True,
        "recommended": False,
        "sort_order": 4,
        "advanced": False,
    },
    {
        "value": "DEEPSEEK",
        "label": "DeepSeek",
        "api_key_placeholder": "Your DeepSeek API key",
        "enabled": True,
        "recommended": False,
        "sort_order": 5,
        "advanced": False,
    },
]

# Set of valid provider values for server-side validation
VALID_AI_PROVIDER_VALUES = frozenset(p["value"] for p in AI_PROVIDERS if p["enabled"])


@router.get("/ai-providers")
def list_ai_providers() -> list[dict]:
    """Return the canonical list of supported AI providers.

    Public endpoint, no auth required. Clients use this to build
    provider selection UI instead of hardcoding their own lists.
    """
    return [p for p in AI_PROVIDERS if p["enabled"]]


@router.get("/lifetime-offer")
def get_lifetime_offer() -> dict:
    """Return a deprecated/free-software-safe response for old clients."""
    return {
        "tier": "free",
        "label": "Torve Free Software",
        "display_price": "$0.00",
        "price_cents": 0,
        "currency": "USD",
        "cap": None,
        "sold": 0,
        "remaining": None,
        "status": "deprecated_free_software",
        "checkout_required": False,
        "message": "Lifetime purchases are no longer required; Torve access is free.",
        "next_tier": None,
    }

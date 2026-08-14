"""
Global configuration - loads secrets from environment variables
All sensitive values injected via Docker Compose / CI/CD, never hard-coded
"""

import os
from dotenv import load_dotenv

load_dotenv()  # load .env for local dev (git-ignored)

# LLM provider (OpenAI-compatible endpoint). Variable names kept as
# DEEPSEEK_* for deployment compatibility (docker-compose / .env already
# use them), but the values now point at Gemini's OpenAI-compatible layer.
# To switch providers just change these three env vars - no code changes.
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv(
    "DEEPSEEK_BASE_URL",
    "https://generativelanguage.googleapis.com/v1beta/openai/",
)
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "gemini-3.7-flash")

# Google Places API
GOOGLE_PLACES_API_KEY = os.getenv("GOOGLE_PLACES_API_KEY", "")

# Duffel API (replaces Amadeus flight search)
DUFFEL_API_TOKEN = os.getenv("DUFFEL_API_TOKEN", "")
DUFFEL_BASE_URL = os.getenv("DUFFEL_BASE_URL", "https://api.duffel.com")

# LiteAPI (Nuitée) - hotel search
LITEAPI_API_KEY = os.getenv("LITEAPI_API_KEY", "")
LITEAPI_BASE_URL = os.getenv("LITEAPI_BASE_URL", "https://api.liteapi.travel/v3.0")
LITEAPI_BOOKING_BASE_URL = os.getenv("LITEAPI_BOOKING_BASE_URL", "https://book.liteapi.travel/v3.0")

# ML model paths
ML_MODELS_DIR = os.getenv("ML_MODELS_DIR", "/app/models")
PRICE_MODEL_PATH = os.path.join(ML_MODELS_DIR, "price_predictor.joblib")
BUDGET_MODEL_PATH = os.path.join(ML_MODELS_DIR, "budget_allocator.joblib")

# Agent settings
MAX_CONVERSATION_HISTORY = int(os.getenv("MAX_CONVERSATION_HISTORY", "20"))
AGENT_TIMEOUT_SECONDS = int(os.getenv("AGENT_TIMEOUT_SECONDS", "120"))

# Default HTTP timeout (seconds) for external API calls
HTTP_TIMEOUT_SECONDS = int(os.getenv("HTTP_TIMEOUT_SECONDS", "30"))

"""
Global configuration — loads secrets from environment variables
All sensitive values injected via Docker Compose / CI/CD, never hard-coded
"""

import os
from dotenv import load_dotenv

load_dotenv()  # load .env for local dev (git-ignored)

# DeepSeek API (OpenAI-compatible endpoint)
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

# Amadeus API
AMADEUS_API_KEY = os.getenv("AMADEUS_API_KEY", "")
AMADEUS_API_SECRET = os.getenv("AMADEUS_API_SECRET", "")

# Google Places API
GOOGLE_PLACES_API_KEY = os.getenv("GOOGLE_PLACES_API_KEY", "")

# ML model paths
ML_MODELS_DIR = os.getenv("ML_MODELS_DIR", "/app/models")
PRICE_MODEL_PATH = os.path.join(ML_MODELS_DIR, "price_predictor.joblib")
BUDGET_MODEL_PATH = os.path.join(ML_MODELS_DIR, "budget_allocator.joblib")

# Agent settings
MAX_CONVERSATION_HISTORY = int(os.getenv("MAX_CONVERSATION_HISTORY", "20"))
AGENT_TIMEOUT_SECONDS = int(os.getenv("AGENT_TIMEOUT_SECONDS", "120"))

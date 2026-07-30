"""
DeepSeek LLM client — wraps OpenAI-compatible API
DeepSeek endpoint is compatible with the OpenAI SDK (change base_url + api_key)
Supports function calling — the agent decides which tool to invoke autonomously
"""

from openai import AsyncOpenAI
import config

# Async client (supports concurrent requests)
client = AsyncOpenAI(
    api_key=config.DEEPSEEK_API_KEY,
    base_url=config.DEEPSEEK_BASE_URL
)

# TODO: chat_completion() — basic message exchange
# TODO: chat_with_tools() — message + tool definitions, agent picks tools
# TODO: get_embedding() — text vectorization (for recommendation similarity)

"""
DeepSeek LLM client - wraps OpenAI-compatible API
DeepSeek endpoint is compatible with the OpenAI SDK (change base_url + api_key)
Supports function calling - the agent decides which tool to invoke autonomously
"""
import openai
from openai import AsyncOpenAI
import config
from agent.http_utils import retry_on_timeout

client = AsyncOpenAI(
    api_key=config.DEEPSEEK_API_KEY,
    base_url=config.DEEPSEEK_BASE_URL,
    timeout=config.AGENT_TIMEOUT_SECONDS,
)

# The OpenAI SDK wraps httpx internally and raises its own
# openai.APITimeoutError on timeout, not httpx.TimeoutException - so
# retry_on_timeout needs to be told that explicitly (its default only matches
# direct httpx callers like duffel_client/liteapi_client/google_places_client).
# Safe to retry: a chat completion has no side effects on DeepSeek's end.
_RETRY_ON_TIMEOUT = retry_on_timeout(max_attempts=3, exceptions=openai.APITimeoutError)


def retry_on_server_errors(max_attempts: int = 3, base_delay: float = 1.0):
    """Retries DeepSeek calls that fail with 5xx / 429 (server-side hiccups).
    DeepSeek intermittently returns 500 'internal error - contact support'
    even for well-formed requests; chat completions are side-effect-free so
    retrying is always safe here (never apply this pattern to booking calls)."""
    import functools
    import asyncio

    def decorator(func):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            last_exception = None
            for attempt in range(max_attempts):
                try:
                    return await func(*args, **kwargs)
                except (openai.InternalServerError, openai.RateLimitError) as e:
                    last_exception = e
                    if attempt < max_attempts - 1:
                        await asyncio.sleep(base_delay * (2 ** attempt))
            raise last_exception
        return wrapper
    return decorator


@retry_on_server_errors()
@_RETRY_ON_TIMEOUT
async def chat_completion(messages: list[dict], temperature: float = 0.3) -> str:
    """Basic exchange: pass OpenAI-format messages, get back plain text"""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        temperature=temperature,
    )
    return response.choices[0].message.content


@retry_on_server_errors()
@_RETRY_ON_TIMEOUT
async def chat_json(messages: list[dict], temperature: float = 0.2) -> str:
    """Forced JSON output: for structured-result scenarios (extraction, itinerary generation).
    max_tokens caps the output so a runaway itinerary JSON can't drag the
    response time - 4096 is generous for a 7-day itinerary but prevents the
    model from rambling."""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        temperature=temperature,
        response_format={"type": "json_object"},
        max_tokens=4096,
    )
    return response.choices[0].message.content


@retry_on_server_errors()
@_RETRY_ON_TIMEOUT
async def chat_with_tools(messages: list[dict], tools: list[dict], temperature: float = 0.3):
    """Backlog #5/#6/#8/#10: message + tool definitions, model decides which tool(s) to call.
    Returns the raw response message object (may contain tool_calls or plain content)."""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        tools=tools,
        temperature=temperature,
    )
    return response.choices[0].message

# TODO: get_embedding()
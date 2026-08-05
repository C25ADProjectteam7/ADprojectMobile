"""
DeepSeek LLM client - wraps OpenAI-compatible API
DeepSeek endpoint is compatible with the OpenAI SDK (change base_url + api_key)
Supports function calling - the agent decides which tool to invoke autonomously
"""
from openai import AsyncOpenAI
import config

client = AsyncOpenAI(
    api_key=config.DEEPSEEK_API_KEY,
    base_url=config.DEEPSEEK_BASE_URL
)


async def chat_completion(messages: list[dict], temperature: float = 0.3) -> str:
    """Basic exchange: pass OpenAI-format messages, get back plain text"""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        temperature=temperature,
    )
    return response.choices[0].message.content


async def chat_json(messages: list[dict], temperature: float = 0.2) -> str:
    """Forced JSON output: for structured-result scenarios (extraction, itinerary generation)"""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        temperature=temperature,
        response_format={"type": "json_object"},
    )
    return response.choices[0].message.content


async def chat_with_tools(messages: list[dict], tools: list[dict], temperature: float = 0.3):
    """Backlog #5/#6: message + tool definitions, model decides which tool(s) to call.
    Returns the raw response message object (may contain tool_calls or plain content)."""
    response = await client.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
        tools=tools,
        temperature=temperature,
    )
    return response.choices[0].message

# TODO: get_embedding()
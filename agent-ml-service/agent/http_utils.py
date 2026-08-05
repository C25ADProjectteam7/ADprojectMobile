"""
Shared HTTP utilities - retry logic for read-only (side-effect-free) requests.
IMPORTANT: only use retry_on_timeout for GET/search/lookup calls. Never wrap
booking/cancellation calls with automatic retry - see the team's booking
failure-handling design (Backlog #9) for why blind retries on requests with
side effects can cause duplicate transactions.
"""
import asyncio
import functools
import httpx


def retry_on_timeout(max_attempts: int = 3, base_delay: float = 1.0):
    """Decorator: retries an async function on httpx.TimeoutException, with a
    short exponential backoff between attempts. Only apply this to read-only
    calls (search, lookup) - never to calls that create/modify/cancel a
    resource, since a timeout does not guarantee the request wasn't already
    processed server-side."""
    def decorator(func):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            last_exception = None
            for attempt in range(max_attempts):
                try:
                    return await func(*args, **kwargs)
                except httpx.TimeoutException as e:
                    last_exception = e
                    if attempt < max_attempts - 1:
                        await asyncio.sleep(base_delay * (2 ** attempt))
            raise last_exception
        return wrapper
    return decorator
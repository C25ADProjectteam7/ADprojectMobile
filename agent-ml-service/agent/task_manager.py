"""
In-memory async task manager for long-running Agent operations (Backlog #6/#10).
Supports the Java backend's expected pattern: POST an operation, get a taskId
back immediately, poll GET /tasks/{taskId} for status/result.

NOTE: in-memory only - task state is lost on service restart, and won't be
shared across multiple instances if this service is ever horizontally scaled.
Acceptable for now (single-instance deployment); revisit with Redis or a DB
table if that changes.

NOTE: this store is for tracking IN-PROGRESS/recently-completed operations
only, not a permanent history. The Java backend is expected to persist the
actual result (e.g. into its trips/bookings tables) once a task completes -
this store cleans up old entries automatically so this service's memory
doesn't grow unbounded.
"""
import logging
import uuid
import asyncio
from datetime import datetime, timezone, timedelta

logger = logging.getLogger(__name__)

_tasks: dict[str, dict] = {}
_background_tasks: set = set()

# Shown to the API caller when a background task fails. Deliberately generic -
# the real exception (which may embed request URLs, library internals, or
# other details not meant for an external client) goes to the server log via
# logger.exception() instead, keyed by task_id for cross-referencing.
GENERIC_FAILURE_MESSAGE = (
    "The request could not be completed due to an internal error. "
    "Please try again; if this persists, contact support and reference this taskId."
)

TASK_RETENTION_MINUTES = 60  # how long a completed/failed task stays queryable


def create_task() -> str:
    """Creates a new task in 'processing' state, returns its taskId."""
    task_id = str(uuid.uuid4())
    _tasks[task_id] = {
        "status": "processing",
        "result": None,
        "error": None,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "finishedAt": None,
    }
    return task_id


def get_task(task_id: str) -> dict | None:
    """Returns the task's current state, or None if taskId is unknown
    (including if it existed but has since been cleaned up - see
    cleanup_expired_tasks)."""
    return _tasks.get(task_id)


def _complete_task(task_id: str, result: dict) -> None:
    if task_id in _tasks:
        _tasks[task_id]["status"] = "completed"
        _tasks[task_id]["result"] = result
        _tasks[task_id]["finishedAt"] = datetime.now(timezone.utc).isoformat()


def _fail_task(task_id: str, error: str) -> None:
    if task_id in _tasks:
        _tasks[task_id]["status"] = "failed"
        _tasks[task_id]["error"] = error
        _tasks[task_id]["finishedAt"] = datetime.now(timezone.utc).isoformat()


def cleanup_expired_tasks() -> int:
    """Removes completed/failed tasks older than TASK_RETENTION_MINUTES from
    memory. Does not touch tasks still in 'processing' state, regardless of
    age. Returns the number of tasks removed."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=TASK_RETENTION_MINUTES)
    expired_ids = []
    for task_id, task in _tasks.items():
        if task["status"] not in ("completed", "failed"):
            continue
        finished_at_str = task.get("finishedAt")
        if not finished_at_str:
            continue
        finished_at = datetime.fromisoformat(finished_at_str)
        if finished_at < cutoff:
            expired_ids.append(task_id)

    for task_id in expired_ids:
        del _tasks[task_id]
    return len(expired_ids)


async def start_cleanup_loop(interval_seconds: int = 600) -> None:
    """Backlog #6/#10 support: runs cleanup_expired_tasks periodically in the
    background. Should be started once at app startup (see main.py)."""
    while True:
        await asyncio.sleep(interval_seconds)
        removed = cleanup_expired_tasks()
        if removed:
            print(f"[task_manager] Cleaned up {removed} expired task(s)")


def run_in_background(task_id: str, coro) -> None:
    """Schedules a coroutine to run in the background, updating the task's
    state (completed/failed) when it finishes. Keeps a strong reference to
    the created asyncio.Task to prevent it being garbage-collected before
    completion - a documented asyncio pitfall, not optional defensive code."""
    async def _wrapper():
        try:
            result = await coro
            _complete_task(task_id, result)
        except Exception:
            # Full exception (message, type, traceback) goes to the server log
            # only - see GENERIC_FAILURE_MESSAGE for why the task's "error"
            # field itself stays generic.
            logger.exception("Background task %s failed", task_id)
            _fail_task(task_id, GENERIC_FAILURE_MESSAGE)
        finally:
            _background_tasks.discard(task)

    task = asyncio.create_task(_wrapper())
    _background_tasks.add(task)
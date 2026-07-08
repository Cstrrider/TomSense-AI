"""Detached chat generation.

A chat reply is produced by a background task that runs to completion and
persists itself — independent of whether a client is still connected. Clients
stream it live and can reconnect (after a swipe-to-app handoff, a network
drop, an app restart). Single uvicorn worker (see Dockerfile), so an in-memory
registry is sufficient.
"""

import asyncio
import uuid
from typing import Optional

# Yielded by LiveRun.stream() so the SSE layer can emit a keepalive comment.
KEEPALIVE = object()
_END = object()

# Finished runs linger this long so a late reconnect can still replay them.
_RETAIN_SECONDS = 150.0


class LiveRun:
    """One in-progress (or recently finished) chat generation."""

    def __init__(self, run_id: str, chat_id: Optional[str], user_id: Optional[str] = None):
        self.run_id = run_id
        self.chat_id = chat_id
        # Owner of the run — reconnects must present the same user. Set for
        # every run (ephemeral chats have no chat_id to check ownership via).
        self.user_id = user_id
        # Ordered log of everything run_chat yielded: str text or dict events.
        self.chunks: list = []
        self.done = False
        self.error: Optional[str] = None
        self._subs: set[asyncio.Queue] = set()

    def append(self, chunk) -> None:
        self.chunks.append(chunk)
        for q in self._subs:
            q.put_nowait(chunk)

    def finish(self, error: Optional[str] = None) -> None:
        self.done = True
        self.error = error
        for q in self._subs:
            q.put_nowait(_END)

    async def stream(self, from_index: int = 0):
        """Yield run chunks from `from_index` on, then tail live ones until the
        run finishes. Yields KEEPALIVE on a 10s idle so the caller can keep the
        SSE connection warm — short enough that mobile/VPN NATs don't idle-drop
        the socket during a long silent tool call (e.g. image generation)."""
        q: asyncio.Queue = asyncio.Queue()
        # Snapshot the backlog and register the live queue with NO await in
        # between: the single-threaded event loop can't interleave an
        # append(), so nothing is lost or duplicated across the handoff.
        backlog = self.chunks[from_index:]
        self._subs.add(q)
        already_done = self.done
        try:
            for c in backlog:
                yield c
            if already_done:
                return
            while True:
                try:
                    c = await asyncio.wait_for(q.get(), timeout=10.0)
                except asyncio.TimeoutError:
                    yield KEEPALIVE
                    continue
                if c is _END:
                    return
                yield c
        finally:
            self._subs.discard(q)


_RUNS: dict[str, LiveRun] = {}
_RUN_BY_CHAT: dict[str, str] = {}
# Strong refs to in-flight eviction tasks. asyncio only holds a weak reference
# to the result of create_task(); without this set a GC pass can cancel the
# eviction mid-sleep, so finished runs would linger in _RUNS forever (slow
# memory leak under load). We discard each task when it completes.
_EVICT_TASKS: set = set()


def create_run(chat_id: Optional[str], user_id: Optional[str] = None) -> LiveRun:
    run = LiveRun(uuid.uuid4().hex, chat_id, user_id)
    _RUNS[run.run_id] = run
    if chat_id:
        _RUN_BY_CHAT[chat_id] = run.run_id
    return run


def get_run(run_id: str) -> Optional[LiveRun]:
    return _RUNS.get(run_id)


def live_run_for_chat(chat_id: str) -> Optional[LiveRun]:
    """The chat's current run — only while it's still streaming."""
    run = _RUNS.get(_RUN_BY_CHAT.get(chat_id, ""))
    return run if (run is not None and not run.done) else None


def retire_run(run: LiveRun) -> None:
    """Schedule a finished run for eviction after a grace period — late
    reconnects (slow handoff, app still loading) can still replay it until
    then; afterward the persisted message is the source of truth."""

    async def _evict():
        await asyncio.sleep(_RETAIN_SECONDS)
        _RUNS.pop(run.run_id, None)
        if run.chat_id and _RUN_BY_CHAT.get(run.chat_id) == run.run_id:
            _RUN_BY_CHAT.pop(run.chat_id, None)

    task = asyncio.create_task(_evict())
    _EVICT_TASKS.add(task)
    task.add_done_callback(_EVICT_TASKS.discard)

"""Wire-format Pydantic models for /chat/stream and /chats CRUD."""

from typing import Any, Literal, Optional
from pydantic import BaseModel, Field


class Message(BaseModel):
    role: Literal["system", "user", "assistant", "tool"]
    # str for plain text; list for OpenAI-shaped multimodal content parts
    # (text + image_url) — e.g. the native app's camera path sends these
    # pre-formed.
    content: str | list[dict[str, Any]]
    # Tool call metadata only set on assistant messages emitting tool calls,
    # or tool messages returning results.
    tool_calls: Optional[list[dict[str, Any]]] = None
    tool_call_id: Optional[str] = None


class ChatRequest(BaseModel):
    messages: list[Message]
    model: Optional[str] = None              # override settings.model_chat
    # Clamped: an unbounded client value would bypass the per-env cost
    # ceilings (MAX_TOKENS_CHAT/CODER). 32768 = 2× the coder default.
    max_tokens: Optional[int] = Field(default=None, ge=1, le=32768)
    # If the client knows the user attached an image and wants the vision
    # path, set this. We auto-detect from messages too.
    vision: bool = False
    # "Think" toggle — run this turn on the reasoning model (MODEL_THINK)
    # with reasoning_effort=high. Explicit model picks still win.
    think: bool = False
    # When set, the user message (last in messages) gets persisted to this
    # chat and the assistant response gets appended on stream end.
    chat_id: Optional[str] = None
    # Upload ids to attach to the last user message. Text/PDF excerpts get
    # prepended to the message text; images are sent as vision content.
    upload_ids: Optional[list[str]] = None


class ToolResultRequest(BaseModel):
    """Device's answer to a `client_tool` SSE event — see clienttools.py."""
    call_id: str = Field(min_length=1, max_length=200)
    result: str = Field(max_length=20000)


class CreateChatRequest(BaseModel):
    title: Optional[str] = None
    model: Optional[str] = None
    # Create a code-mode chat — the coding agent with file/bash tools.
    code: bool = False


class RenameChatRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)


class UpdateSystemPromptRequest(BaseModel):
    # Allow empty string / null to clear the persona.
    system_prompt: Optional[str] = Field(default=None, max_length=8000)


class CreateMemoryRequest(BaseModel):
    content: str = Field(min_length=1, max_length=500)


class UpdateModelRequest(BaseModel):
    model: str = Field(min_length=1, max_length=200)


class PinRequest(BaseModel):
    is_pinned: bool


class FolderRequest(BaseModel):
    folder: Optional[str] = Field(default=None, max_length=80)


class BranchRequest(BaseModel):
    message_id: int


class CreatePersonaRequest(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    system_prompt: Optional[str] = Field(default=None, max_length=8000)
    model: Optional[str] = Field(default=None, max_length=200)


class UpdatePersonaRequest(BaseModel):
    name: Optional[str] = Field(default=None, max_length=120)
    system_prompt: Optional[str] = Field(default=None, max_length=8000)
    model: Optional[str] = Field(default=None, max_length=200)

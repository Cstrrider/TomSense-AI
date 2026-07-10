"""Provider-agnostic AI operations beyond chat.

The chat/completions path is already provider-agnostic (providers.py); these
modules do the same for the other operations — embeddings, speech, images — so
each resolves through a configured provider (OpenAI-compatible first-class,
Cloudflare as one adapter) instead of hand-rolled CF-only calls.

Each adapter takes a resolved model string (`provider::model`, empty = the
bundled CF default) and branches on provider kind:
  - the CF builtin uses Cloudflare's native run URL / audio endpoints;
  - any other provider speaks the standard OpenAI endpoint shape.
"""

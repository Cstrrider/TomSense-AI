// GENERATED from backend/app/cf_catalog.py — do not hand-edit.
// Regenerate when the Python catalogue changes; it remains the source of
// truth until backend/ is retired.
//
// Capability is DATA, per model (spec §4):
//   vision    accepts image_url content parts
//   reasoning emits a hidden reasoning channel
//   context   advertised window, best-effort
//   roles     which tool-model pickers this model belongs in

import type { ModelEntry } from "./types";

export interface CfModel extends ModelEntry {
  label: string;
  note: string;
  roles: string[];
}

export const CF_MODELS: CfModel[] = [
  {
    "id": "@cf/google/gemma-4-26b-a4b-it",
    "label": "Gemma 4 (26B)",
    "note": "default · vision + tools",
    "vision": true,
    "reasoning": true,
    "context": 16000,
    "roles": [
      "chat",
      "vision",
      "code"
    ]
  },
  {
    "id": "@cf/moonshotai/kimi-k2.7-code",
    "label": "Kimi K2.7 (1T)",
    "note": "frontier · 262k ctx · vision + tools · neuron-hungry",
    "vision": true,
    "reasoning": true,
    "context": 262000,
    "roles": [
      "chat",
      "vision",
      "code"
    ]
  },
  {
    "id": "@cf/moonshotai/kimi-k2.6",
    "label": "Kimi K2.6",
    "note": "tight reasoning when warm",
    "vision": true,
    "reasoning": true,
    "context": 262000,
    "roles": [
      "chat",
      "code"
    ]
  },
  {
    "id": "@cf/zai-org/glm-5.2",
    "label": "GLM-5.2",
    "note": "flagship agentic · 262k ctx · neuron-hungry",
    "vision": false,
    "reasoning": true,
    "context": 262000,
    "roles": [
      "chat",
      "code"
    ]
  },
  {
    "id": "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "label": "Llama 3.3 70B",
    "note": "fastest · text-only",
    "vision": false,
    "reasoning": false,
    "context": 24000,
    "roles": [
      "chat",
      "research"
    ]
  },
  {
    "id": "@cf/openai/gpt-oss-120b",
    "label": "GPT-OSS 120B",
    "note": "reasoning · expensive",
    "vision": false,
    "reasoning": true,
    "context": 128000,
    "roles": [
      "chat",
      "research",
      "code"
    ]
  },
  {
    "id": "@cf/openai/gpt-oss-20b",
    "label": "GPT-OSS 20B",
    "note": "reasoning · cheap",
    "vision": false,
    "reasoning": true,
    "context": 128000,
    "roles": [
      "chat",
      "research",
      "code"
    ]
  },
  {
    "id": "@cf/nvidia/nemotron-3-120b-a12b",
    "label": "Nemotron 3 120B",
    "note": "agentic 120B (12B active) · 256k ctx",
    "vision": false,
    "reasoning": true,
    "context": 256000,
    "roles": [
      "code"
    ]
  },
  {
    "id": "@cf/qwen/qwen3-30b-a3b-fp8",
    "label": "Qwen3 30B",
    "note": "fast & cheap MoE · 3B active FP8",
    "vision": false,
    "reasoning": true,
    "context": 32000,
    "roles": [
      "code"
    ]
  },
  {
    "id": "@cf/meta/llama-4-scout-17b-16e-instruct",
    "label": "Llama 4 Scout 17B",
    "note": "fast multimodal MoE",
    "vision": true,
    "reasoning": false,
    "context": 128000,
    "roles": [
      "chat",
      "vision"
    ]
  },
  {
    "id": "@cf/meta/llama-3.1-8b-instruct",
    "label": "Llama 3.1 8B",
    "note": "",
    "vision": false,
    "reasoning": false,
    "context": 16000,
    "roles": [
      "chat",
      "title"
    ]
  },
  {
    "id": "@cf/meta/llama-3.2-3b-instruct",
    "label": "Llama 3.2 3B",
    "note": "tiny",
    "vision": false,
    "reasoning": false,
    "context": 16000,
    "roles": [
      "chat",
      "title"
    ]
  },
  {
    "id": "@cf/black-forest-labs/flux-2-klein-4b",
    "label": "Flux 2 Klein 4B",
    "note": "default · ~$0.001/img",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  },
  {
    "id": "@cf/runwayml/stable-diffusion-v1-5-img2img",
    "label": "SD v1.5",
    "note": "beta · FREE · CF",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  },
  {
    "id": "@cf/black-forest-labs/flux-2-klein-9b",
    "label": "Flux 2 Klein 9B",
    "note": "~$0.015/img · better quality",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  },
  {
    "id": "google/imagen-4",
    "label": "Imagen 4",
    "note": "~$0.04/img · photorealistic",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image"
    ]
  },
  {
    "id": "@cf/black-forest-labs/flux-2-dev",
    "label": "Flux 2 Dev",
    "note": "premium · ~$0.04/img · multi-ref",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  },
  {
    "id": "openai/gpt-image-2",
    "label": "gpt-image-2",
    "note": "$0.055/img medium · OpenAI",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  },
  {
    "id": "google/nano-banana-2",
    "label": "Nano Banana 2",
    "note": "~$0.08/img · best all-rounder",
    "vision": false,
    "reasoning": false,
    "roles": [
      "image",
      "image_edit"
    ]
  }
];

export const CF_MODELS_BY_ID: Record<string, CfModel> = Object.fromEntries(
  CF_MODELS.map((m) => [m.id, m]),
);

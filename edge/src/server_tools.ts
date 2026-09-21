/**
 * Tools the EDGE runs, as opposed to the ones the phone runs.
 *
 * Until now every tool was a device tool: the run parked in `awaiting_tools`,
 * the phone executed it, and the result came back through `/run/{id}/tool_result`.
 * That is right for anything needing the device — the calendar, the torch, a
 * permission prompt — and wrong for anything needing the edge. Image
 * generation is the first of the second kind: it wants the AI binding and R2,
 * neither of which the phone has.
 *
 * So a round's tool calls are now SPLIT. Server tools execute inline and the
 * loop continues; device tools park as before. The split matters beyond
 * tidiness — parking for a tool the phone cannot run would hang the run until
 * it was swept away, and a phone that is asleep would never answer.
 */

import type { Env, ToolCall } from "./types";
import { putGenerated } from "./files";

export interface ServerToolResult {
  /** Text handed back to the model as the tool result. */
  content: string;
  /** R2 key to attach to the reply, for anything that produced a file. */
  attachmentKey?: string;
  attachmentMime?: string;
}

interface ServerTool {
  schema: Record<string, unknown>;
  run(
    env: Env,
    userId: string,
    args: Record<string, unknown>,
    modelOverride?: string,
  ): Promise<ServerToolResult>;
}

/**
 * Default image model.
 *
 * flux-1-schnell, NOT flux-2-klein. The klein models reject a plain prompt
 * with "required properties at '/' are 'multipart'" — they take a different
 * request shape entirely, and the failure surfaces only at call time, as the
 * model apologising that it cannot generate images. Verified against the live
 * API: schnell accepts { prompt } and returns { image: base64 }.
 *
 * Overridable per user through the `image` slot, so this is a default rather
 * than a hardcoding.
 */
const DEFAULT_IMAGE_MODEL = "@cf/black-forest-labs/flux-1-schnell";

const generateImage: ServerTool = {
  schema: {
    type: "function",
    function: {
      name: "generate_image",
      description:
        "Generate an image from a text description and show it to the user. " +
        "Use for any request to draw, paint, illustrate, or create a picture. " +
        "The image is displayed automatically — do not describe it at length " +
        "afterwards, and never claim to have attached something you did not.",
      parameters: {
        type: "object",
        properties: {
          prompt: {
            type: "string",
            description:
              "What to depict. Be specific about subject, style and composition; " +
              "the model does not see the conversation, only this string.",
          },
        },
        required: ["prompt"],
      },
    },
  },

  async run(env, userId, args, modelOverride) {
    const prompt = String(args["prompt"] ?? "").trim();
    if (!prompt) return { content: "generate_image failed: no prompt given" };

    // A slot value is a full provider::model spec; only the model id matters
    // here, because this always runs on the AI binding.
    const model = (modelOverride || DEFAULT_IMAGE_MODEL).split("::").pop() as string;

    try {
      const out = (await env.AI.run(model as never, { prompt } as never)) as unknown;

      const bytes = await imageBytes(out);
      if (!bytes) return { content: "generate_image failed: the model returned no image" };

      const key = await putGenerated(env, userId, bytes, "image/jpeg", "generated.jpg");
      return {
        // Deliberately terse. The image is delivered as an attachment, so a
        // model that then narrates the picture is describing something it
        // cannot see.
        content: `Image generated and shown to the user. Prompt: ${prompt}`,
        attachmentKey: key,
        attachmentMime: "image/jpeg",
      };
    } catch (e) {
      return { content: `generate_image failed: ${(e as Error).message}` };
    }
  },
};

/**
 * Workers AI image models are inconsistent about their return shape — some
 * hand back a ReadableStream of JPEG bytes, others a JSON object with a
 * base64 `image` field. Handling only one silently breaks when the model is
 * changed.
 */
async function imageBytes(out: unknown): Promise<Uint8Array | null> {
  if (out instanceof ReadableStream) {
    const chunks: Uint8Array[] = [];
    const reader = out.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) chunks.push(value as Uint8Array);
    }
    const total = chunks.reduce((n, c) => n + c.length, 0);
    const buf = new Uint8Array(total);
    let at = 0;
    for (const c of chunks) {
      buf.set(c, at);
      at += c.length;
    }
    return total ? buf : null;
  }

  const b64 = (out as { image?: unknown })?.image;
  if (typeof b64 === "string" && b64) {
    const bin = atob(b64);
    const buf = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
    return buf;
  }
  return null;
}

const REGISTRY: Record<string, ServerTool> = {
  generate_image: generateImage,
};

export function isServerTool(name: string): boolean {
  return name in REGISTRY;
}

/** Schemas to advertise alongside whatever device tools the client sent. */
export function serverToolSchemas(): Record<string, unknown>[] {
  return Object.values(REGISTRY).map((t) => t.schema);
}

export async function runServerTool(
  env: Env,
  userId: string,
  call: ToolCall,
  modelOverride?: string,
): Promise<ServerToolResult> {
  const tool = REGISTRY[call.name];
  if (!tool) return { content: `unknown tool ${call.name}` };

  const args =
    call.arguments && typeof call.arguments === "object"
      ? (call.arguments as Record<string, unknown>)
      : {};
  return tool.run(env, userId, args, modelOverride);
}

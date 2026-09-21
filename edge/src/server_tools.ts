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
import { runImageModel, resizeForEdit, needsMultipart } from "./images";

export interface ServerToolResult {
  /** Text handed back to the model as the tool result. */
  content: string;
  /**
   * Why it failed, shown to the USER as a notice.
   *
   * Without this the only account of a failure is the model paraphrasing the
   * tool result — "I am having technical difficulties" — which tells nobody
   * what actually went wrong, including whoever is debugging it.
   */
  error?: string;
  /** R2 key to attach to the reply, for anything that produced a file. */
  attachmentKey?: string;
  attachmentMime?: string;
}

/**
 * What a server tool knows about the run it is part of.
 *
 * `recentImageKeys` is what makes editing possible at all: the model says
 * "make it red" without naming a file, so the tool needs the conversation's
 * images in order. Oldest first, newest last.
 */
export interface ToolContext {
  imageModel?: string;
  recentImageKeys?: string[];
}

interface ServerTool {
  schema: Record<string, unknown>;
  run(
    env: Env,
    userId: string,
    args: Record<string, unknown>,
    ctx?: ToolContext,
  ): Promise<ServerToolResult>;
}

/**
 * Default image model.
 *
 * flux-1-schnell: fast, cheap, and takes a plain JSON prompt. Overridable per
 * user through the `image` slot — including to a flux-2 model, which
 * images.ts knows to call with multipart.
 */
const DEFAULT_IMAGE_MODEL = "@cf/black-forest-labs/flux-1-schnell";

/**
 * Editing needs a model that can actually edit.
 *
 * Only the flux-2 family does, so a user whose image slot points at
 * flux-1-schnell still gets a working edit rather than a silent regeneration.
 */
const DEFAULT_EDIT_MODEL = "@cf/black-forest-labs/flux-2-klein-4b";

function modelIdOf(spec: string | undefined, fallback: string): string {
  const raw = (spec || fallback).trim();
  return (raw.split("::").pop() as string) || fallback;
}

const generateImage: ServerTool = {
  schema: {
    type: "function",
    function: {
      name: "generate_image",
      description:
        "Create a NEW image from a text description and show it to the user. " +
        "Use for any request to draw, paint, illustrate or picture something. " +
        "To change an image that already exists in this conversation, use " +
        "edit_image instead. The image is displayed automatically — do not " +
        "describe it at length afterwards, and never claim to have attached " +
        "something you did not.",
      parameters: {
        type: "object",
        properties: {
          prompt: {
            type: "string",
            description:
              "What to depict. Be specific about subject, style and composition; " +
              "the image model does not see the conversation, only this string.",
          },
        },
        required: ["prompt"],
      },
    },
  },

  async run(env, userId, args, ctx) {
    const prompt = String(args["prompt"] ?? "").trim();
    if (!prompt) return { content: "generate_image failed: no prompt given" };

    const model = modelIdOf(ctx?.imageModel, DEFAULT_IMAGE_MODEL);
    const { bytes, error } = await runImageModel(env, model, prompt);
    if (!bytes) {
      const why = error ?? "no image returned";
      return { content: `generate_image failed: ${why}`, error: `Image generation failed: ${why}` };
    }

    const key = await putGenerated(env, userId, bytes, "image/jpeg", "generated.jpg");
    return {
      // Terse on purpose: the image is delivered as an attachment, so a model
      // that then narrates it is describing something it cannot see.
      content: `Image generated and shown to the user. Prompt: ${prompt}`,
      attachmentKey: key,
      attachmentMime: "image/jpeg",
    };
  },
};

const editImage: ServerTool = {
  schema: {
    type: "function",
    function: {
      name: "edit_image",
      description:
        "Modify the image most recently shown or attached in this conversation " +
        "— change a colour, add or remove something, restyle it — keeping the " +
        "rest intact. Use this rather than generate_image whenever the user " +
        "refers to an existing picture ('make it red', 'remove the background', " +
        "'now in winter'). Fails if there is no image in the conversation yet.",
      parameters: {
        type: "object",
        properties: {
          instruction: {
            type: "string",
            description:
              "The change to make, phrased as an instruction about the existing " +
              "image, e.g. 'make the mug bright red, keep everything else the same'.",
          },
        },
        required: ["instruction"],
      },
    },
  },

  async run(env, userId, args, ctx) {
    const instruction = String(args["instruction"] ?? "").trim();
    if (!instruction) return { content: "edit_image failed: no instruction given" };

    const sourceKey = ctx?.recentImageKeys?.at(-1);
    if (!sourceKey) {
      return {
        content:
          "edit_image failed: there is no image in this conversation to edit. " +
          "Generate one first, or ask the user to attach one.",
      };
    }

    const obj = await env.FILES.get(sourceKey);
    if (!obj) return { content: "edit_image failed: the source image is no longer available" };
    const original = new Uint8Array(await obj.arrayBuffer());

    // MUST be under 512x512 or Cloudflare drops it without complaint, and the
    // "edit" silently becomes a fresh generation from the instruction alone.
    const source = await resizeForEdit(env, original);
    if (!source) {
      return {
        content:
          "edit_image failed: could not prepare the source image. Editing needs " +
          "the image resizing binding to be available.",
      };
    }

    // The slot model is used only if it can edit; otherwise the edit model.
    const slot = modelIdOf(ctx?.imageModel, "");
    const model = slot && needsMultipart(slot) ? slot : DEFAULT_EDIT_MODEL;

    const { bytes, error } = await runImageModel(env, model, instruction, [source]);
    if (!bytes) {
      const why = error ?? "no image returned";
      return { content: `edit_image failed: ${why}`, error: `Image edit failed: ${why}` };
    }

    const key = await putGenerated(env, userId, bytes, "image/jpeg", "edited.jpg");
    return {
      content: `Edited image produced and shown to the user. Change: ${instruction}`,
      attachmentKey: key,
      attachmentMime: "image/jpeg",
    };
  },
};

const REGISTRY: Record<string, ServerTool> = {
  generate_image: generateImage,
  edit_image: editImage,
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
  ctx?: ToolContext,
): Promise<ServerToolResult> {
  const tool = REGISTRY[call.name];
  if (!tool) return { content: `unknown tool ${call.name}` };

  const args =
    call.arguments && typeof call.arguments === "object"
      ? (call.arguments as Record<string, unknown>)
      : {};
  return tool.run(env, userId, args, ctx);
}

/**
 * TomSense home agent — the entire residual home footprint (spec §7).
 *
 * Replaces: tomsense-backend, tomsense-frontend, postgres, qdrant, searxng,
 * nginx, the docker proxy, and the tunnel that fronted them. One container.
 *
 * It dials OUTBOUND to the HomeLink Durable Object and holds a WebSocket
 * open. Nothing at home listens on a public port; there is no ingress at all.
 * If this container is down, LAN tools report offline and every other part of
 * the assistant keeps working — that separation is the point.
 *
 * SECURITY POSTURE, stated plainly, because this process can touch the LAN:
 *
 *   - The tool surface is an explicit ALLOW-LIST. There is no generic "run a
 *     shell command" tool, because that is just a remote shell with extra
 *     steps, and the edge is internet-facing.
 *   - Each tool validates its own arguments. Container names are matched
 *     against a fixed set rather than interpolated into a command.
 *   - Commands are spawned with execFile and an argv array — never a shell
 *     string — so argument content cannot become syntax.
 */

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import WebSocket from "ws";

const execFileAsync = promisify(execFile);

const EDGE_URL = process.env["EDGE_URL"] ?? "wss://tomsense-edge.workers.dev/homelink/agent";
const AGENT_TOKEN = process.env["HOME_AGENT_TOKEN"] ?? "";

/** Containers this agent is permitted to act on. Anything else is refused. */
const ALLOWED_CONTAINERS = new Set([
  "tomsense-backend",
  "tomsense-frontend",
  "tomsense-postgres",
  "kitchensync-backend",
  "kitchensync-frontend",
  "backrest",
  "adguard",
]);

interface ToolDef {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (args: Record<string, unknown>) => Promise<unknown>;
}

function requireString(args: Record<string, unknown>, key: string): string {
  const v = args[key];
  if (typeof v !== "string" || !v) throw new Error(`${key} must be a non-empty string`);
  return v;
}

async function docker(...argv: string[]): Promise<string> {
  const { stdout } = await execFileAsync("docker", argv, { timeout: 20_000 });
  return stdout.trim();
}

const TOOLS: ToolDef[] = [
  {
    name: "lan_container_status",
    description:
      "List status of the allow-listed containers on the home server. Use for questions like 'is the backup running' or 'is anything down'.",
    inputSchema: { type: "object", properties: {} },
    handler: async () => {
      const out = await docker(
        "ps",
        "--all",
        "--format",
        "{{.Names}}\t{{.State}}\t{{.Status}}",
      );
      return out
        .split("\n")
        .filter(Boolean)
        .map((line) => {
          const [name = "", state = "", status = ""] = line.split("\t");
          return { name, state, status };
        })
        .filter((c) => ALLOWED_CONTAINERS.has(c.name));
    },
  },
  {
    name: "lan_container_restart",
    description: "Restart one allow-listed container on the home server.",
    inputSchema: {
      type: "object",
      properties: { name: { type: "string" } },
      required: ["name"],
    },
    handler: async (args) => {
      const name = requireString(args, "name");
      // Membership check, not sanitisation. An allow-list is the only thing
      // that reliably prevents this becoming arbitrary container control.
      if (!ALLOWED_CONTAINERS.has(name)) {
        throw new Error(`container ${name} is not in the allow-list`);
      }
      await docker("restart", name);
      return { restarted: name };
    },
  },
  {
    name: "lan_backup_status",
    description:
      "Last backup result from Backrest: when it ran, whether it succeeded, and the repo size.",
    inputSchema: { type: "object", properties: {} },
    handler: async () => {
      const res = await fetch("http://backrest:9898/v1/summary", {
        signal: AbortSignal.timeout(10_000),
      });
      if (!res.ok) throw new Error(`backrest returned ${res.status}`);
      return await res.json();
    },
  },
  {
    name: "lan_disk_free",
    description: "Free disk space on the home server's data volumes.",
    inputSchema: { type: "object", properties: {} },
    handler: async () => {
      const { stdout } = await execFileAsync("df", ["-h", "--output=target,size,used,avail,pcent"], {
        timeout: 10_000,
      });
      return stdout.trim();
    },
  },
];

const TOOLS_BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));

/** Reconnect with exponential backoff + jitter, capped. */
let backoffMs = 1_000;
const BACKOFF_MAX_MS = 60_000;

function connect(): void {
  if (!AGENT_TOKEN) {
    console.error("HOME_AGENT_TOKEN is not set — refusing to connect");
    process.exit(1);
  }

  const ws = new WebSocket(EDGE_URL, {
    headers: { "x-home-agent-token": AGENT_TOKEN },
  });

  ws.on("open", () => {
    backoffMs = 1_000; // reset only after a genuinely successful connect
    console.log(`connected to ${EDGE_URL}`);
    ws.send(
      JSON.stringify({
        t: "hello",
        tools: TOOLS.map(({ name, description, inputSchema }) => ({
          name,
          description,
          inputSchema,
        })),
      }),
    );
  });

  ws.on("message", (raw: Buffer) => {
    void handle(ws, raw.toString());
  });

  ws.on("close", () => {
    scheduleReconnect("closed");
  });

  ws.on("error", (err: Error) => {
    console.error(`socket error: ${err.message}`);
    // 'close' fires after 'error'; let that path own the reconnect so we
    // don't schedule two reconnect timers and double the connection rate.
  });
}

function scheduleReconnect(reason: string): void {
  const jitter = Math.random() * 0.3 * backoffMs;
  const delay = Math.min(backoffMs + jitter, BACKOFF_MAX_MS);
  console.log(`${reason} — reconnecting in ${Math.round(delay)}ms`);
  setTimeout(connect, delay);
  backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
}

async function handle(ws: WebSocket, raw: string): Promise<void> {
  let msg: { t?: string; id?: string; name?: string; arguments?: Record<string, unknown> };
  try {
    msg = JSON.parse(raw);
  } catch {
    return;
  }
  if (msg.t !== "call" || !msg.id) return;

  const tool = msg.name ? TOOLS_BY_NAME.get(msg.name) : undefined;
  if (!tool) {
    ws.send(JSON.stringify({ t: "result", id: msg.id, error: `unknown tool ${msg.name}` }));
    return;
  }

  try {
    const result = await tool.handler(msg.arguments ?? {});
    ws.send(JSON.stringify({ t: "result", id: msg.id, result }));
  } catch (e) {
    ws.send(JSON.stringify({ t: "result", id: msg.id, error: (e as Error).message }));
  }
}

connect();

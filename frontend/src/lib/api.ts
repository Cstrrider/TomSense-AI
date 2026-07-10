import { runClientTool, toolStatusLabel } from './clienttools';
import type {
  Artifact,
  Chat,
  ChatSearchResult,
  ChatWithMessages,
  Credentials,
  InfoResponse,
  Memory,
  MeResponse,
  Message,
  NeuronUsage,
  Persona,
  Project,
  Provider,
  ProviderTestResult,
  MountsApplyResult,
  MountsResponse,
  ProjectMount,
  SandboxFileContent,
  SandboxListing,
  UploadResponse,
  UserArtifact,
  UserPrefs,
  UserUpload
} from './types';

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    throw new Error(`${r.status} ${r.statusText}: ${text}`);
  }
  return (await r.json()) as T;
}

export async function getInfo(): Promise<InfoResponse> {
  return http<InfoResponse>('/info');
}

export async function searchChats(q: string): Promise<ChatSearchResult[]> {
  const j = await http<{ chats: ChatSearchResult[] }>(
    `/chats/search?q=${encodeURIComponent(q)}`
  );
  return j.chats;
}

export async function deleteChats(ids: string[]): Promise<number> {
  const j = await http<{ deleted: number }>('/chats/delete-batch', {
    method: 'POST',
    body: JSON.stringify({ ids })
  });
  return j.deleted;
}

// ─── scheduled prompts ───────────────────────────────────────────────────────

export interface Schedule {
  id: string;
  title: string;
  prompt: string;
  run_at: string; // "HH:MM" 24h in the server's SCHEDULE_TZ
  weekdays: number; // bitmask Mon=1 … Sun=64
  enabled: boolean;
  model: string | null;
  last_run_date: string | null;
  created_at: string;
}

export async function listSchedules(): Promise<{ schedules: Schedule[]; tz: string }> {
  return http('/me/schedules');
}

export async function createSchedule(s: {
  title: string;
  prompt: string;
  run_at: string;
  weekdays?: number;
  model?: string | null;
}): Promise<Schedule> {
  return http<Schedule>('/me/schedules', { method: 'POST', body: JSON.stringify(s) });
}

export async function updateSchedule(
  id: string,
  patch: Partial<Pick<Schedule, 'title' | 'prompt' | 'run_at' | 'weekdays' | 'enabled' | 'model'>>
): Promise<Schedule> {
  return http<Schedule>(`/me/schedules/${id}`, { method: 'PATCH', body: JSON.stringify(patch) });
}

export async function deleteSchedule(id: string): Promise<void> {
  await http(`/me/schedules/${id}`, { method: 'DELETE' });
}

// ─── projects ────────────────────────────────────────────────────────────────

export async function listProjects(): Promise<Project[]> {
  const j = await http<{ projects: Project[] }>('/me/projects');
  return j.projects;
}

export async function createProject(name: string, system_prompt?: string | null): Promise<Project> {
  return http<Project>('/me/projects', {
    method: 'POST',
    body: JSON.stringify({ name, system_prompt })
  });
}

export async function updateProject(
  id: string,
  patch: { name?: string; system_prompt?: string | null }
): Promise<Project> {
  return http<Project>(`/me/projects/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(patch)
  });
}

export async function deleteProject(id: string): Promise<void> {
  const r = await fetch(`/me/projects/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function setChatProject(chatId: string, projectId: string | null): Promise<Chat> {
  return http<Chat>(`/chats/${chatId}/project`, {
    method: 'PUT',
    body: JSON.stringify({ project_id: projectId })
  });
}

export async function listChats(): Promise<Chat[]> {
  const j = await http<{ chats: Chat[] }>('/chats');
  return j.chats;
}

export async function createChat(code = false, model?: string): Promise<Chat> {
  const body: Record<string, unknown> = {};
  if (code) body.code = true;
  if (model) body.model = model;
  return http<Chat>('/chats', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

export async function getChat(id: string): Promise<ChatWithMessages> {
  return http<ChatWithMessages>(`/chats/${id}`);
}

export async function renameChat(id: string, title: string): Promise<Chat> {
  return http<Chat>(`/chats/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ title })
  });
}

export async function deleteChat(id: string): Promise<void> {
  const r = await fetch(`/chats/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function setSystemPrompt(id: string, system_prompt: string | null): Promise<Chat> {
  return http<Chat>(`/chats/${id}/system_prompt`, {
    method: 'PUT',
    body: JSON.stringify({ system_prompt })
  });
}

export async function createShare(id: string): Promise<{ share_token: string; chat_id: string }> {
  return http<{ share_token: string; chat_id: string }>(`/chats/${id}/share`, {
    method: 'POST'
  });
}

export async function revokeShare(id: string): Promise<void> {
  const r = await fetch(`/chats/${id}/share`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`revoke failed: ${r.status}`);
}

export async function getSharedChat(token: string): Promise<ChatWithMessages> {
  return http<ChatWithMessages>(`/share/${token}`);
}

// ─── MCP servers ─────────────────────────────────────────────────────────────

export interface McpServer {
  id: string;
  name: string;
  url: string;
  auth_header: boolean; // redacted — true when a secret is stored
  enabled: boolean;
  created_at: string;
}

export async function listMcpServers(): Promise<McpServer[]> {
  const j = await http<{ servers: McpServer[] }>('/me/mcp');
  return j.servers;
}

export async function createMcpServer(s: {
  name: string;
  url: string;
  auth_header?: string;
}): Promise<McpServer> {
  return http<McpServer>('/me/mcp', { method: 'POST', body: JSON.stringify(s) });
}

export async function updateMcpServer(
  id: string,
  patch: { name?: string; url?: string; auth_header?: string; enabled?: boolean }
): Promise<McpServer> {
  return http<McpServer>(`/me/mcp/${id}`, { method: 'PATCH', body: JSON.stringify(patch) });
}

export async function deleteMcpServer(id: string): Promise<void> {
  await http(`/me/mcp/${id}`, { method: 'DELETE' });
}

export async function testMcpServer(id: string): Promise<{ ok: boolean; tools: string[] }> {
  return http(`/me/mcp/${id}/test`, { method: 'POST' });
}

export async function setUploadProject(
  uploadId: string,
  projectId: string | null
): Promise<void> {
  await http(`/me/uploads/${uploadId}/project`, {
    method: 'PUT',
    body: JSON.stringify({ project_id: projectId })
  });
}

export async function updateArtifact(
  id: number,
  patch: { content?: string; title?: string }
): Promise<Artifact> {
  return http<Artifact>(`/artifacts/${id}`, { method: 'PATCH', body: JSON.stringify(patch) });
}

export async function listArtifacts(chatId: string): Promise<Artifact[]> {
  const j = await http<{ artifacts: Artifact[] }>(`/chats/${chatId}/artifacts`);
  return j.artifacts;
}

export async function regenerateLast(chatId: string): Promise<{ deleted_message_id: number }> {
  return http<{ deleted_message_id: number }>(`/chats/${chatId}/regenerate`, {
    method: 'POST'
  });
}

/** Suggested follow-up questions for a chat (generated by the cheap model).
 *  Best-effort — resolves to [] on any failure. */
export async function getFollowups(chatId: string): Promise<string[]> {
  try {
    const j = await http<{ followups: string[] }>(`/chat/${chatId}/followups`, {
      method: 'POST'
    });
    return Array.isArray(j.followups) ? j.followups : [];
  } catch {
    return [];
  }
}

export async function setChatModel(chatId: string, model: string): Promise<Chat> {
  return http<Chat>(`/chats/${chatId}/model`, {
    method: 'PUT',
    body: JSON.stringify({ model })
  });
}

export async function exportChat(chatId: string, format: 'md' | 'json'): Promise<void> {
  const r = await fetch(`/chats/${chatId}/export?format=${format}`);
  if (!r.ok) throw new Error(`export failed: ${r.status}`);
  const blob = await r.blob();
  const disp = r.headers.get('Content-Disposition') ?? '';
  const m = disp.match(/filename="([^"]+)"/);
  const fname = m?.[1] ?? `chat.${format}`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fname;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function truncateFrom(chatId: string, messageId: number): Promise<{ removed: number }> {
  const r = await fetch(`/chats/${chatId}/messages/from/${messageId}`, {
    method: 'DELETE'
  });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    throw new Error(`${r.status}: ${text || r.statusText}`);
  }
  return (await r.json()) as { removed: number };
}

export async function setChatPinned(chatId: string, is_pinned: boolean): Promise<Chat> {
  return http<Chat>(`/chats/${chatId}/pin`, {
    method: 'PUT',
    body: JSON.stringify({ is_pinned })
  });
}

export async function branchChat(chatId: string, message_id: number): Promise<Chat> {
  return http<Chat>(`/chats/${chatId}/branch`, {
    method: 'POST',
    body: JSON.stringify({ message_id })
  });
}

export async function listPersonas(): Promise<Persona[]> {
  const j = await http<{ personas: Persona[] }>('/me/personas');
  return j.personas;
}

export async function createPersona(
  name: string,
  system_prompt: string | null,
  model: string | null
): Promise<Persona> {
  return http<Persona>('/me/personas', {
    method: 'POST',
    body: JSON.stringify({ name, system_prompt, model })
  });
}

export async function updatePersona(
  id: string,
  patch: { name?: string; system_prompt?: string | null; model?: string | null }
): Promise<Persona> {
  return http<Persona>(`/me/personas/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch)
  });
}

export async function deletePersona(id: string): Promise<void> {
  const r = await fetch(`/me/personas/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function getMe(): Promise<MeResponse> {
  return http<MeResponse>('/me');
}

export async function getUsageToday(): Promise<import('./types').UsageToday> {
  return http<import('./types').UsageToday>('/me/usage/today');
}

export async function getNeurons(): Promise<NeuronUsage> {
  return http<NeuronUsage>('/me/neurons');
}

export async function getPrefs(): Promise<UserPrefs> {
  const j = await http<{ prefs: UserPrefs }>('/me/prefs');
  return j.prefs;
}

export async function updatePrefs(patch: UserPrefs): Promise<UserPrefs> {
  const j = await http<{ prefs: UserPrefs }>('/me/prefs', {
    method: 'PUT',
    body: JSON.stringify(patch)
  });
  return j.prefs;
}

// ─── embedding backend (RAG) + starters ──────────────────────────────────────

export interface RagStatus {
  model: string;
  kind: string;
  configured_dim: number;
  collection_dim: number | null;
  indexed_chunks: number;
  stale: boolean;
}

export async function getRagStatus(): Promise<RagStatus> {
  return http<RagStatus>('/me/rag-status');
}

/** Re-embed every indexed chunk with the current embedding backend. */
export async function reindexAll(): Promise<{ ok: boolean; reindexed: number }> {
  return http<{ ok: boolean; reindexed: number }>('/me/reindex', { method: 'POST' });
}

export interface Starter {
  icon: string;
  text: string;
}

/** Personalized welcome-screen starters (generated from the user's memories). */
export async function getStarters(): Promise<Starter[]> {
  const j = await http<{ starters: Starter[] }>('/me/starters');
  return j.starters;
}

// ─── providers ──────────────────────────────────────────────────────────────

export async function listProviders(): Promise<Provider[]> {
  const j = await http<{ providers: Provider[] }>('/me/providers');
  return j.providers;
}

export async function createProvider(p: {
  name: string;
  base_url: string;
  api_key: string;
  models?: Provider['models'];
}): Promise<Provider> {
  return http<Provider>('/me/providers', {
    method: 'POST',
    body: JSON.stringify(p)
  });
}

export async function updateProvider(
  id: string,
  patch: Partial<Pick<Provider, 'name' | 'base_url' | 'api_key' | 'models'>>
): Promise<Provider> {
  return http<Provider>(`/me/providers/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(patch)
  });
}

export async function deleteProvider(id: string): Promise<void> {
  const r = await fetch(`/me/providers/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function testProvider(id: string): Promise<ProviderTestResult> {
  return http<ProviderTestResult>(`/me/providers/${id}/test`, { method: 'POST' });
}

/** Discover the model ids a provider advertises at /models — for a saved
 *  provider (provider_id) or an in-progress form (base_url + api_key). */
export async function discoverProviderModels(
  body: { provider_id?: string; base_url?: string; api_key?: string }
): Promise<string[]> {
  const j = await http<{ models: string[] }>('/me/providers/discover', {
    method: 'POST',
    body: JSON.stringify(body)
  });
  return j.models;
}

export async function listMemories(): Promise<Memory[]> {
  const j = await http<{ memories: Memory[] }>('/me/memories');
  return j.memories;
}

export async function addMemory(content: string): Promise<Memory> {
  return http<Memory>('/me/memories', {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}

export async function deleteMemory(id: number): Promise<void> {
  const r = await fetch(`/me/memories/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function listUserUploads(): Promise<UserUpload[]> {
  const j = await http<{ uploads: UserUpload[] }>('/me/uploads');
  return j.uploads;
}

export async function deleteUserUpload(id: string): Promise<void> {
  const r = await fetch(`/me/uploads/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

export async function reindexUpload(id: string): Promise<{ chunks: number }> {
  return http<{ upload_id: string; chunks: number }>(`/me/uploads/${id}/reindex`, {
    method: 'POST'
  });
}

// ─── builtin-provider credentials (UI-rotatable api keys) ────────────────

export async function getCredentials(): Promise<Credentials> {
  const j = await http<{ credentials: Credentials }>('/me/credentials');
  return j.credentials;
}

/** Set or clear builtin-provider credentials. Empty string or null clears
 *  that key — falls back to the backend env default (if any). */
export async function setCredentials(patch: {
  cf_api_token?: string | null;
  anthropic_api_key?: string | null;
}): Promise<Credentials> {
  const j = await http<{ credentials: Credentials }>('/me/credentials', {
    method: 'PUT',
    body: JSON.stringify(patch)
  });
  return j.credentials;
}

// ─── secret vault ─────────────────────────────────────────────────────────
// Named secrets the code-mode agent can USE (injected into its sandbox shell)
// without the value ever reaching the model or the client. List returns names
// + timestamps only; values are write-only.

export interface SecretMeta {
  name: string;
  created_at: string;
  updated_at: string;
}

export async function listSecrets(): Promise<SecretMeta[]> {
  const j = await http<{ secrets: SecretMeta[] }>('/me/secrets');
  return j.secrets;
}

export async function setSecret(name: string, value: string): Promise<void> {
  await http(`/me/secrets/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify({ value })
  });
}

export async function deleteSecret(name: string): Promise<void> {
  await http(`/me/secrets/${encodeURIComponent(name)}`, { method: 'DELETE' });
}

// ─── cross-chat artifacts (file manager) ──────────────────────────────────

export async function listUserArtifacts(): Promise<UserArtifact[]> {
  const j = await http<{ artifacts: UserArtifact[] }>('/me/artifacts');
  return j.artifacts;
}

export async function getArtifact(id: number): Promise<Artifact & {
  chat_id: string;
  chat_title: string | null;
}> {
  return http(`/artifacts/${id}`);
}

export async function deleteArtifact(id: number): Promise<void> {
  const r = await fetch(`/artifacts/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

// ─── sandbox project mounts (host dir → /workspace/projects/<name>/) ─────

export async function listMounts(): Promise<MountsResponse> {
  return http<MountsResponse>('/me/mounts');
}

export async function addMount(name: string, host_path: string): Promise<ProjectMount[]> {
  const j = await http<{ mounts: ProjectMount[] }>('/me/mounts', {
    method: 'POST',
    body: JSON.stringify({ name, host_path })
  });
  return j.mounts;
}

export async function deleteMount(name: string): Promise<ProjectMount[]> {
  const j = await http<{ mounts: ProjectMount[] }>(`/me/mounts/${encodeURIComponent(name)}`, {
    method: 'DELETE'
  });
  return j.mounts;
}

export async function applyMounts(): Promise<MountsApplyResult> {
  return http<MountsApplyResult>('/me/mounts/apply', { method: 'POST' });
}

// ─── sandbox file manager (user-facing /sandbox/fs/*) ─────────────────────

export async function sandboxList(path = '.'): Promise<SandboxListing> {
  return http<SandboxListing>(`/sandbox/fs/list?path=${encodeURIComponent(path)}`);
}

export async function sandboxRead(
  path: string,
  offset = 0,
  limit = 2000
): Promise<SandboxFileContent> {
  const q = new URLSearchParams({ path, offset: String(offset), limit: String(limit) });
  return http<SandboxFileContent>(`/sandbox/fs/read?${q.toString()}`);
}

export async function sandboxWrite(path: string, content: string): Promise<void> {
  await http('/sandbox/fs/write', {
    method: 'POST',
    body: JSON.stringify({ path, content })
  });
}

export async function sandboxRename(src: string, dst: string): Promise<void> {
  await http('/sandbox/fs/rename', {
    method: 'POST',
    body: JSON.stringify({ src, dst })
  });
}

export async function sandboxDelete(path: string, recursive = false): Promise<void> {
  await http('/sandbox/fs/delete', {
    method: 'POST',
    body: JSON.stringify({ path, recursive })
  });
}

export async function sandboxMkdir(path: string): Promise<void> {
  await http('/sandbox/fs/mkdir', {
    method: 'POST',
    body: JSON.stringify({ path })
  });
}

export async function uploadFile(file: File): Promise<UploadResponse> {
  const fd = new FormData();
  fd.append('file', file);
  const r = await fetch('/uploads', { method: 'POST', body: fd });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    throw new Error(`${r.status}: ${text || r.statusText}`);
  }
  return (await r.json()) as UploadResponse;
}

export async function transcribeAudio(blob: Blob, filename = 'speech.webm'): Promise<string> {
  const fd = new FormData();
  fd.append('file', blob, filename);
  const r = await fetch('/transcribe', { method: 'POST', body: fd });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    throw new Error(`${r.status}: ${text || r.statusText}`);
  }
  const j = (await r.json()) as { text: string };
  return j.text;
}

export async function fetchTTS(
  text: string,
  voice?: string,
  signal?: AbortSignal,
  format: 'mp3' | 'wav' | 'opus' = 'mp3'
): Promise<Blob> {
  const r = await fetch('/tts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, voice, format }),
    signal
  });
  if (!r.ok) {
    const t = await r.text().catch(() => '');
    throw new Error(`${r.status}: ${t || r.statusText}`);
  }
  return await r.blob();
}

export interface StreamEvent {
  /** `tool_status` is a transient user-facing progress line ("Opening
   *  Spotify…") emitted while a client tool runs on the device; an empty
   *  `text` clears it. It is never part of the reply text. */
  type: 'text' | 'done' | 'error' | 'tool_status';
  text?: string;
  error?: string;
}

/** Report a client-fulfilled tool's result back to the paused streaming turn. */
export async function postToolResult(callId: string, result: string): Promise<void> {
  try {
    await fetch('/chat/tool_result', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ call_id: callId, result })
    });
  } catch {
    // The backend falls back to its own timeout if the result never arrives.
  }
}

/** Whether `chatId` has a reply being generated right now — returns the run
 *  id to reconnect to, or null. */
export async function getLiveRun(chatId: string): Promise<string | null> {
  try {
    const j = await http<{ run_id: string | null }>(`/chat/${chatId}/live`);
    return j.run_id;
  } catch {
    return null;
  }
}

/**
 * Stream a chat reply as SSE events.
 *
 * The reply is generated server-side as a detached run, so this survives
 * disconnects: if the SSE drops mid-reply it transparently reconnects to the
 * same run (from where it left off — no duplicate text) and resumes. Pass
 * `existingRunId` to attach to a reply already in progress — e.g. after the
 * app is opened from the assist overlay — instead of starting a new one.
 */
/** A pending edit awaiting the user's Apply/Reject decision. The handler
 *  resolves to the string posted back to the paused turn: 'approve' or
 *  'reject'. */
export interface ApproveEdit {
  callId: string;
  tool: string;
  paths: string[];
  diff: string;
}

export async function* streamChat(
  messages: Message[],
  chatId: string | null,
  uploadIds: string[] | null,
  signal?: AbortSignal,
  existingRunId?: string,
  onApprove?: (e: ApproveEdit) => Promise<string>,
  opts?: { think?: boolean }
): AsyncGenerator<StreamEvent, void, void> {
  let runId: string | null = existingRunId ?? null;
  let chunkIndex = 0; // run chunks (text + client_tool) already consumed
  let attempts = 0;

  while (true) {
    let res: Response;
    try {
      if (runId === null) {
        res = await fetch('/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            messages,
            chat_id: chatId,
            upload_ids: uploadIds && uploadIds.length ? uploadIds : undefined,
            think: opts?.think || undefined
          }),
          signal
        });
      } else {
        res = await fetch(`/chat/stream/${runId}?from=${chunkIndex}`, { signal });
      }
    } catch (e) {
      if (signal?.aborted) return;
      if (runId !== null && ++attempts <= 6) {
        await new Promise((r) => setTimeout(r, 400 * attempts));
        continue;
      }
      throw e;
    }

    if (res.status === 404 && runId !== null) {
      // The run finished and was evicted — the reply is already persisted.
      yield { type: 'done' };
      return;
    }
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => '');
      throw new Error(`${res.status} ${res.statusText}: ${text}`);
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let sawDone = false;
    let dropped = false;
    try {
      while (true) {
        let readResult: { done: boolean; value?: Uint8Array };
        try {
          readResult = await reader.read();
        } catch (e) {
          if (signal?.aborted) throw e;
          dropped = true; // network drop — reconnect below
          break;
        }
        if (readResult.done) {
          dropped = !sawDone; // ended without a `done` event → treat as a drop
          break;
        }
        buffer += decoder.decode(readResult.value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const raw = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          if (!raw.startsWith('data:')) continue;
          let ev: StreamEvent & {
            run_id?: string;
            call_id?: string;
            name?: string;
            arguments?: Record<string, unknown>;
            paths?: string[];
            diff?: string;
          };
          try {
            ev = JSON.parse(raw.slice(5).trim());
          } catch {
            continue; // skip malformed
          }
          const kind = (ev as { type: string }).type;
          if (kind === 'run') {
            runId = ev.run_id ?? runId;
          } else if (kind === 'done') {
            sawDone = true;
          } else if (kind === 'client_tool') {
            // Device-fulfilled tool: surface a status line ("Opening
            // Spotify…"), run it, post the result, clear the status. The
            // model's continuation arrives as normal text on the stream.
            chunkIndex++;
            yield { type: 'tool_status', text: toolStatusLabel(ev.name ?? '', ev.arguments ?? {}) };
            const result = await runClientTool(ev.name ?? '', ev.arguments ?? {});
            await postToolResult(ev.call_id ?? '', result);
            yield { type: 'tool_status', text: '' };
          } else if (kind === 'approve_edit') {
            // Diff-approval gate: surface the pending edit, wait for the user's
            // Apply/Reject, post it back to the paused turn. Default to reject
            // if no handler is wired (safer than silently applying).
            //
            // chunkIndex is advanced ONLY AFTER the decision is posted — NOT
            // before. The turn blocks server-side while we wait, and a mobile
            // SSE drop during that wait triggers a reconnect from `chunkIndex`.
            // If we'd already consumed this event, the reconnect would replay
            // PAST it, the card would never re-appear, and the backend would
            // hang until its 15-min timeout. Leaving chunkIndex on the event
            // means a reconnect REPLAYS approve_edit and re-shows the card.
            let decision = 'reject';
            if (onApprove) {
              try {
                decision = await onApprove({
                  callId: ev.call_id ?? '',
                  tool: ev.name ?? '',
                  paths: ev.paths ?? [],
                  diff: ev.diff ?? ''
                });
              } catch {
                decision = 'reject';
              }
            }
            await postToolResult(ev.call_id ?? '', decision);
            chunkIndex++;
          } else if (kind === 'text') {
            chunkIndex++;
            yield ev;
          } else {
            yield ev; // error, etc.
          }
        }
        if (sawDone) break;
      }
    } finally {
      reader.cancel().catch(() => {});
    }

    if (sawDone) {
      yield { type: 'done' };
      return;
    }
    if (!dropped || signal?.aborted) return;
    if (runId === null) throw new Error('connection lost before the reply started');
    if (++attempts > 6) {
      yield { type: 'error', error: 'lost connection to the reply' };
      return;
    }
    await new Promise((r) => setTimeout(r, 400 * attempts));
    // loop → reconnect to /chat/stream/{runId}?from=chunkIndex
  }
}

/** Attach to a reply already being generated server-side (e.g. after a
 *  swipe-to-app handoff). Streams it from the start; reconnects on drops. */
export function attachToRun(
  runId: string,
  signal?: AbortSignal,
  onApprove?: (e: ApproveEdit) => Promise<string>
): AsyncGenerator<StreamEvent, void, void> {
  return streamChat([], null, null, signal, runId, onApprove);
}

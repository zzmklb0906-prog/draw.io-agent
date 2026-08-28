import { apiUrl, ApiError, authenticatedHeaders } from '../../shared/api/httpClient';
import type { ChatRequest, StreamChunk, StreamEnvelope, StreamPhase } from './chat.types';

const phases = new Set<StreamPhase>([
  'thinking',
  'analyzing',
  'drawing',
  'generating',
  'reviewing',
  'done',
  'error',
]);

export interface ChatStreamRunResponse {
  runId: string;
  agentId?: string;
  userId?: string;
  sessionId?: string;
  conversationId?: string;
  checkpointId?: string;
  checkpointRevision?: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  lastSequenceNo?: number;
  errorMessage?: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface ActiveRunMetadata {
  runId: string;
  sessionId: string;
  conversationId?: string;
  lastSequenceNo: number;
  updatedAt: number;
}

const ACTIVE_RUN_STORAGE_KEY = 'drawio_active_chat_stream_run';

export function saveActiveRunMetadata(meta: ActiveRunMetadata): void {
  try {
    sessionStorage.setItem(ACTIVE_RUN_STORAGE_KEY, JSON.stringify(meta));
  } catch {
    // Storage quota or restricted environment
  }
}

export function getActiveRunMetadata(sessionId?: string, conversationId?: string): ActiveRunMetadata | null {
  try {
    const raw = sessionStorage.getItem(ACTIVE_RUN_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ActiveRunMetadata;
    if (!parsed || !parsed.runId) return null;
    if (conversationId && parsed.conversationId && parsed.conversationId !== conversationId) return null;
    if (sessionId && parsed.sessionId && parsed.sessionId !== sessionId) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearActiveRunMetadata(runId?: string): void {
  try {
    if (!runId) {
      sessionStorage.removeItem(ACTIVE_RUN_STORAGE_KEY);
      return;
    }
    const current = getActiveRunMetadata();
    if (current?.runId === runId) {
      sessionStorage.removeItem(ACTIVE_RUN_STORAGE_KEY);
    }
  } catch {
    // Storage quota or restricted environment
  }
}

export function isStreamChunk(value: unknown): value is StreamChunk {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  if (typeof item.type !== 'string') return false;
  switch (item.type) {
    case 'token':
    case 'status':
    case 'user':
    case 'error':
      return typeof item.content === 'string';
    case 'approval':
      return (
        typeof item.title === 'string' &&
        typeof item.rewrittenPrompt === 'string' &&
        typeof item.diagramType === 'string' &&
        Array.isArray(item.scope) &&
        Array.isArray(item.assumptions) &&
        Array.isArray(item.questions) &&
        typeof item.checkpointId === 'string' &&
        typeof item.revision === 'number'
      );
    case 'checkpoint':
      return typeof item.checkpointId === 'string' && typeof item.revision === 'number' && typeof item.status === 'string';
    case 'tool_approval':
      return typeof item.callId === 'string' && typeof item.checkpointId === 'string' && typeof item.revision === 'number' && !!item.details && typeof item.details === 'object';
    case 'tool':
      return typeof item.callId === 'string' && typeof item.name === 'string' && typeof item.status === 'string' && typeof item.startedAt === 'number';
    case 'drawio_node':
      return typeof item.id === 'string' && typeof item.xml === 'string';
    case 'drawio_edge':
      return (
        typeof item.id === 'string' &&
        typeof item.xml === 'string' &&
        typeof item.source === 'string' &&
        typeof item.target === 'string'
      );
    case 'drawio_done':
    case 'drawio':
      return typeof item.content === 'string';
    case 'ppt_raw':
      return typeof item.raw === 'string';
    case 'done':
      return true;
    default:
      return false;
  }
}

export function isStreamEnvelope(value: unknown): value is StreamEnvelope {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item.phase === 'string' &&
    phases.has(item.phase as StreamPhase) &&
    isStreamChunk(item.chunk)
  );
}

/**
 * Standards-compliant SSE stream parser handling arbitrary UTF-8 boundaries, CRLF/LF,
 * comments, multiline data, and event / id fields.
 */
export async function parseSseStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: StreamEnvelope, eventId?: string, eventType?: string) => void,
  onMalformed?: (raw: string) => void,
  onComment?: (comment: string) => void,
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventType = 'message';
  let dataLines: string[] = [];
  let eventId: string | undefined = undefined;

  const dispatchFrame = () => {
    if (dataLines.length > 0) {
      const dataStr = dataLines.join('\n');
      try {
        const parsed: unknown = JSON.parse(dataStr);
        if (isStreamEnvelope(parsed)) {
          onEvent(parsed, eventId, eventType);
        } else if (isStreamChunk(parsed)) {
          onEvent({ phase: 'thinking', chunk: parsed }, eventId, eventType);
        } else {
          onMalformed?.(dataStr);
        }
      } catch {
        onMalformed?.(dataStr);
      }
    }
    eventType = 'message';
    dataLines = [];
    eventId = undefined;
  };

  const processLine = (line: string) => {
    if (line === '') {
      dispatchFrame();
      return;
    }
    if (line.startsWith(':')) {
      onComment?.(line.slice(1).trim());
      return;
    }
    const colonIdx = line.indexOf(':');
    let field = line;
    let value = '';
    if (colonIdx >= 0) {
      field = line.slice(0, colonIdx);
      value = line.slice(colonIdx + 1);
      if (value.startsWith(' ')) {
        value = value.slice(1);
      }
    }
    switch (field) {
      case 'event':
        eventType = value;
        break;
      case 'data':
        dataLines.push(value);
        break;
      case 'id':
        if (!value.includes('\0')) {
          eventId = value;
        }
        break;
      default:
        break;
    }
  };

  const consumeLines = (flush: boolean) => {
    let start = 0;
    while (start < buffer.length) {
      let lineEnd = -1;
      let nextStart = -1;
      for (let i = start; i < buffer.length; i++) {
        const ch = buffer.charCodeAt(i);
        if (ch === 10) { // \n
          lineEnd = i;
          nextStart = i + 1;
          break;
        } else if (ch === 13) { // \r
          if (i + 1 < buffer.length) {
            if (buffer.charCodeAt(i + 1) === 10) { // \r\n
              lineEnd = i;
              nextStart = i + 2;
            } else { // lone \r
              lineEnd = i;
              nextStart = i + 1;
            }
            break;
          } else if (flush) {
            lineEnd = i;
            nextStart = i + 1;
            break;
          } else {
            // Trailing \r at the end of buffer without flush: wait for next chunk
            break;
          }
        }
      }
      if (nextStart !== -1) {
        const line = buffer.slice(start, lineEnd);
        processLine(line);
        start = nextStart;
      } else {
        break;
      }
    }
    buffer = buffer.slice(start);
    if (flush && buffer.length > 0) {
      processLine(buffer);
      buffer = '';
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    consumeLines(false);
  }

  buffer += decoder.decode();
  consumeLines(true);
  dispatchFrame();
}

function sleepWithAbort(ms: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const onAbort = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
      resolve();
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

async function authenticatedFetchWithRefresh(
  url: string,
  init: RequestInit,
  signal?: AbortSignal,
): Promise<Response> {
  const execute = () =>
    fetch(url, {
      ...init,
      credentials: 'include',
      headers: {
        ...authenticatedHeaders(),
        ...(init.headers || {}),
      },
      signal,
    });

  let response = await execute();
  if (response.status === 401 && !signal?.aborted) {
    const refreshed = await fetch(apiUrl('/api/v1/auth/refresh'), {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'application/json' },
      signal,
    });
    if (refreshed.ok) {
      const result = (await refreshed.json()) as {
        code?: string;
        data?: { username: string; displayName?: string; token: string; expiresAt: number };
      };
      if (result.code === '0000' && result.data) {
        localStorage.setItem('draw-io-agent-demo-user', JSON.stringify(result.data));
        response = await execute();
      }
    }
  }

  if (response.status === 401) {
    sessionStorage.removeItem('draw-io-agent-demo-user');
    localStorage.removeItem('draw-io-agent-demo-user');
    window.dispatchEvent(new Event('agent-auth-expired'));
  }

  return response;
}

export async function createChatStreamRun(
  payload: ChatRequest,
  signal?: AbortSignal,
): Promise<ChatStreamRunResponse> {
  const response = await authenticatedFetchWithRefresh(
    apiUrl('/api/v1/chat_stream'),
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    },
    signal,
  );

  if (!response.ok) {
    let code = response.status === 401 ? 'AUTH_REQUIRED' : 'HTTP_ERROR';
    let message =
      response.status === 401 ? '登录会话已失效，请重新登录' : `创建流式任务失败（HTTP ${response.status}）`;
    try {
      const body = (await response.json()) as { code?: string; info?: string };
      code = body.code || code;
      message = body.info || message;
    } catch {
      /* non-JSON response */
    }
    throw new ApiError(message, code, response.status);
  }

  const result = (await response.json()) as { code?: string; info?: string; data?: ChatStreamRunResponse };
  if (result.code !== '0000' || !result.data) {
    throw new ApiError(result.info || '创建流式任务失败', result.code || 'RUN_CREATE_FAILED', response.status);
  }

  return result.data;
}

export interface SubscribeChatStreamOptions {
  runId: string;
  sessionId: string;
  conversationId?: string;
  signal: AbortSignal;
  initialCursor?: number;
  onEvent: (event: StreamEnvelope) => void;
  onMalformed?: (raw: string) => void;
  onHeartbeat?: () => void;
  onReconnect?: (attempt: number, delayMs: number) => void;
}

export async function subscribeChatStream(options: SubscribeChatStreamOptions): Promise<void> {
  const {
    runId,
    sessionId,
    conversationId,
    signal,
    initialCursor = 0,
    onEvent,
    onMalformed,
    onHeartbeat,
    onReconnect,
  } = options;

  let lastSeenSeq = initialCursor;
  let isTerminal = false;
  let attempt = 0;
  const maxAttempts = 6;
  const maxDelayMs = 10000;

  while (!isTerminal && !signal.aborted) {
    try {
      const params = new URLSearchParams();
      if (lastSeenSeq > 0) {
        params.set('after', String(lastSeenSeq));
      }
      const query = params.toString();
      const url = apiUrl(`/api/v1/chat_stream/${encodeURIComponent(runId)}${query ? `?${query}` : ''}`);

      const response = await authenticatedFetchWithRefresh(
        url,
        {
          method: 'GET',
          headers: {
            Accept: 'text/event-stream',
            ...(lastSeenSeq > 0 ? { 'Last-Event-ID': String(lastSeenSeq) } : {}),
          },
        },
        signal,
      );

      if (!response.ok) {
        if (response.status === 404) {
          clearActiveRunMetadata(runId);
          throw new ApiError('流式任务不存在或已过期', 'RUN_NOT_FOUND', 404);
        }
        if (response.status >= 400 && response.status < 500 && response.status !== 408 && response.status !== 429) {
          clearActiveRunMetadata(runId);
          let code = 'HTTP_ERROR';
          let message = `订阅流失败（HTTP ${response.status}）`;
          try {
            const body = (await response.json()) as { code?: string; info?: string };
            code = body.code || code;
            message = body.info || message;
          } catch {
            /* ignore */
          }
          throw new ApiError(message, code, response.status);
        }
        // Retryable status (5xx, 408, 429)
        attempt++;
        if (attempt > maxAttempts) {
          clearActiveRunMetadata(runId);
          throw new ApiError(`重连失败（HTTP ${response.status}）`, 'STREAM_RETRY_EXHAUSTED', response.status);
        }
        const delay = Math.min(maxDelayMs, 500 * Math.pow(2, attempt - 1));
        onReconnect?.(attempt, delay);
        await sleepWithAbort(delay, signal);
        continue;
      }

      if (!response.body) {
        throw new ApiError('浏览器无法读取流式响应', 'EMPTY_STREAM', response.status);
      }

      await parseSseStream(
        response.body,
        (envelope, eventId) => {
          if (eventId !== undefined) {
            const seq = Number(eventId);
            if (!Number.isNaN(seq)) {
              if (seq <= lastSeenSeq) {
                // Duplicate suppression
                return;
              }
              lastSeenSeq = seq;
            }
          }
          attempt = 0; // Observable forward progress: reset failure counter

          saveActiveRunMetadata({
            runId,
            sessionId,
            conversationId,
            lastSequenceNo: lastSeenSeq,
            updatedAt: Date.now(),
          });

          if (envelope.chunk.type === 'done' || envelope.chunk.type === 'error') {
            isTerminal = true;
            clearActiveRunMetadata(runId);
          }
          onEvent(envelope);
        },
        onMalformed,
        onHeartbeat,
      );

      if (isTerminal || signal.aborted) {
        break;
      }

      // Stream closed (EOF) before terminal event: reconnect with backoff
      attempt++;
      if (attempt > maxAttempts) {
        clearActiveRunMetadata(runId);
        throw new ApiError('重连次数已达上限', 'STREAM_RETRY_EXHAUSTED', response.status);
      }
      const delay = Math.min(maxDelayMs, 500 * Math.pow(2, attempt - 1));
      onReconnect?.(attempt, delay);
      await sleepWithAbort(delay, signal);
    } catch (error) {
      if (signal.aborted) {
        break;
      }
      if (
        error instanceof ApiError &&
        typeof error.status === 'number' &&
        (error.status === 404 ||
          (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429))
      ) {
        clearActiveRunMetadata(runId);
        throw error;
      }
      if (error instanceof ApiError && error.code === 'STREAM_RETRY_EXHAUSTED') {
        clearActiveRunMetadata(runId);
        throw error;
      }
      attempt++;
      if (attempt > maxAttempts) {
        clearActiveRunMetadata(runId);
        throw error;
      }
      const delay = Math.min(maxDelayMs, 500 * Math.pow(2, attempt - 1));
      onReconnect?.(attempt, delay);
      await sleepWithAbort(delay, signal);
    }
  }
}

export async function streamChat(
  payload: ChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEnvelope) => void,
  onMalformed?: (line: string) => void,
): Promise<void> {
  const run = await createChatStreamRun(payload, signal);
  saveActiveRunMetadata({
    runId: run.runId,
    sessionId: payload.sessionId,
    conversationId: payload.conversationId,
    lastSequenceNo: 0,
    updatedAt: Date.now(),
  });

  await subscribeChatStream({
    runId: run.runId,
    sessionId: payload.sessionId,
    conversationId: payload.conversationId,
    signal,
    initialCursor: 0,
    onEvent,
    onMalformed,
  });
}

export async function reattachChatStream(
  runId: string,
  sessionId: string,
  conversationId: string | undefined,
  initialCursor: number,
  signal: AbortSignal,
  onEvent: (event: StreamEnvelope) => void,
  onMalformed?: (line: string) => void,
): Promise<void> {
  await subscribeChatStream({
    runId,
    sessionId,
    conversationId,
    signal,
    initialCursor,
    onEvent,
    onMalformed,
  });
}

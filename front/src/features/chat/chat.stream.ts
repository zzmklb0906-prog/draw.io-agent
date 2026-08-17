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

export async function parseNdjsonStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: StreamEnvelope) => void,
  onMalformed?: (line: string) => void,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const processLine = (line: string) => {
    if (!line.trim()) return;
    try {
      const parsed: unknown = JSON.parse(line);
      if (isStreamEnvelope(parsed)) onEvent(parsed);
      else onMalformed?.(line);
    } catch {
      onMalformed?.(line);
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    lines.forEach(processLine);
  }
  buffer += decoder.decode();
  processLine(buffer);
}

export async function streamChat(
  payload: ChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEnvelope) => void,
  onMalformed?: (line: string) => void,
) {
  const execute = () => fetch(apiUrl('/api/v1/chat_stream'), {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/x-ndjson',
      'Content-Type': 'application/json',
      ...authenticatedHeaders(),
    },
    body: JSON.stringify(payload),
    signal,
  });
  let response = await execute();
  if (response.status === 401 && !signal.aborted) {
    const refreshed = await fetch(apiUrl('/api/v1/auth/refresh'), { method: 'POST', credentials: 'include', headers: { Accept: 'application/json' }, signal });
    if (refreshed.ok) {
      const result = await refreshed.json() as { code?: string; data?: { username: string; displayName?: string; token: string; expiresAt: number } };
      if (result.code === '0000' && result.data) {
        localStorage.setItem('draw-io-agent-demo-user', JSON.stringify(result.data));
        response = await execute();
      }
    }
  }
  if (!response.ok) {
    if (response.status === 401) {
      sessionStorage.removeItem('draw-io-agent-demo-user');
      localStorage.removeItem('draw-io-agent-demo-user');
      window.dispatchEvent(new Event('agent-auth-expired'));
    }
    let code=response.status === 401 ? 'AUTH_REQUIRED' : 'HTTP_ERROR';
    let message=response.status === 401 ? '登录会话已失效，请重新登录' : `流式请求失败（HTTP ${response.status}）`;
    try { const body=await response.json() as {code?:string;info?:string};code=body.code||code;message=body.info||message; } catch { /* NDJSON/proxy error */ }
    throw new ApiError(message,code,response.status);
  }
  if (!response.body) {
    throw new ApiError('浏览器无法读取流式响应', 'EMPTY_STREAM', response.status);
  }
  await parseNdjsonStream(response.body, onEvent, onMalformed);
}

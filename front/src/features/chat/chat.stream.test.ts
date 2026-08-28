import { describe, expect, it, vi, beforeEach } from 'vitest';
import {
  isStreamChunk,
  isStreamEnvelope,
  parseSseStream,
  saveActiveRunMetadata,
  getActiveRunMetadata,
  clearActiveRunMetadata,
  subscribeChatStream,
  reattachChatStream,
} from './chat.stream';

const encoder = new TextEncoder();
const makeStream = (chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });

describe('SSE stream parser', () => {
  it('parses an SSE event frame split across arbitrary chunks', async () => {
    const events: unknown[] = [];
    const eventIds: (string | undefined)[] = [];
    await parseSseStream(
      makeStream([
        'id: 1\nevent: token\n',
        'data: {"phase":"draw',
        'ing","chunk":{"type":"drawio_node","id":"2",',
        '"xml":"<mxCell/>"}}\n\n',
      ]),
      (event, id) => {
        events.push(event);
        eventIds.push(id);
      },
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ phase: 'drawing', chunk: { type: 'drawio_node', id: '2' } });
    expect(eventIds[0]).toBe('1');
  });

  it('parses frame when CRLF is split exactly between chunk boundaries without premature emission', async () => {
    const events: unknown[] = [];
    const eventIds: (string | undefined)[] = [];
    // Chunk 1 ends with '\r', chunk 2 starts with '\n'
    await parseSseStream(
      makeStream([
        'id: 10\r\nevent: token\r\ndata: {"phase":"thinking","chunk":{"type":"token","content":"part1"}}\r',
        '\n\r\n',
      ]),
      (event, id) => {
        events.push(event);
        eventIds.push(id);
      },
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toEqual({ phase: 'thinking', chunk: { type: 'token', content: 'part1' } });
    expect(eventIds[0]).toBe('10');
  });

  it('handles CRLF line endings and comment heartbeats', async () => {
    const events: unknown[] = [];
    const comments: string[] = [];
    await parseSseStream(
      makeStream([
        ':heartbeat\r\n',
        'id: 2\r\nevent: token\r\ndata: {"phase":"thinking","chunk":{"type":"token","content":"Hello"}}\r\n\r\n',
        ': idle\r\n',
        'id: 3\r\nevent: done\r\ndata: {"phase":"done","chunk":{"type":"done"}}\r\n\r\n',
      ]),
      (event) => events.push(event),
      undefined,
      (comment) => comments.push(comment),
    );
    expect(events).toHaveLength(2);
    expect(comments).toEqual(['heartbeat', 'idle']);
    expect(events[0]).toEqual({ phase: 'thinking', chunk: { type: 'token', content: 'Hello' } });
    expect(events[1]).toEqual({ phase: 'done', chunk: { type: 'done' } });
  });

  it('parses multiline data and reports malformed frames without crashing', async () => {
    const events: unknown[] = [];
    const malformed = vi.fn();
    await parseSseStream(
      makeStream([
        'id: 1\ndata: invalid json\n\n',
        'id: 2\ndata: {"phase":"thinking",\ndata: "chunk":{"type":"status","content":"ok"}}\n\n',
      ]),
      (event) => events.push(event),
      malformed,
    );
    expect(malformed).toHaveBeenCalledWith('invalid json');
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ phase: 'thinking', chunk: { type: 'status', content: 'ok' } });
  });

  it('parses raw chunk payload wrapped into envelope', async () => {
    const events: unknown[] = [];
    await parseSseStream(
      makeStream([
        'id: 1\ndata: {"type":"token","content":"streamed"}\n\n',
      ]),
      (event) => events.push(event),
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ phase: 'thinking', chunk: { type: 'token', content: 'streamed' } });
  });
});

describe('Active run metadata helpers', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('saves, retrieves and clears active run metadata correctly', () => {
    expect(getActiveRunMetadata('s1', 'c1')).toBeNull();

    saveActiveRunMetadata({
      runId: 'run-123',
      sessionId: 's1',
      conversationId: 'c1',
      lastSequenceNo: 42,
      updatedAt: Date.now(),
    });

    const stored = getActiveRunMetadata('s1', 'c1');
    expect(stored).not.toBeNull();
    expect(stored?.runId).toBe('run-123');
    expect(stored?.lastSequenceNo).toBe(42);

    // Mismatched session/conv returns null
    expect(getActiveRunMetadata('s2', 'c1')).toBeNull();

    // Clear by runId
    clearActiveRunMetadata('run-123');
    expect(getActiveRunMetadata('s1', 'c1')).toBeNull();
  });
});

describe('stream chunk guard', () => {
  it('rejects incomplete edge payloads', () => {
    expect(isStreamChunk({ type: 'drawio_edge', id: 'e1', xml: '<mxCell/>' })).toBe(false);
    expect(isStreamChunk({ type: 'done' })).toBe(true);
  });

  it('accepts a complete human approval request', () => {
    expect(isStreamChunk({
      type: 'approval',
      title: '登录流程',
      rewrittenPrompt: '绘制完整登录流程',
      diagramType: 'flowchart',
      scope: ['前端', '认证服务'],
      assumptions: ['账号密码认证'],
      questions: [],
      checkpointId: '4ddc44a6-4cbc-4f93-aab0-e79fd937db82',
      revision: 2,
    })).toBe(true);
  });

  it('validates stream envelope', () => {
    expect(isStreamEnvelope({ phase: 'thinking', chunk: { type: 'done' } })).toBe(true);
    expect(isStreamEnvelope({ phase: 'invalid_phase', chunk: { type: 'done' } })).toBe(false);
  });
});

describe('tool lifecycle chunk', () => {
  it('accepts a running tool event', () => {
    expect(isStreamChunk({ type: 'tool', callId: 'call-1', name: 'load_skill', status: 'RUNNING', startedAt: 100 })).toBe(true);
  });
});

describe('subscribeChatStream retry and reconnect', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('stops retrying at configured bound on repeated HTTP 200 with empty body EOF, using only GET and clearing metadata', async () => {
    vi.useFakeTimers();
    const fetchCalls: { url: string; method: string; headers: Record<string, string> }[] = [];
    const onReconnect = vi.fn();
    const onEvent = vi.fn();

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      fetchCalls.push({
        url,
        method: (init?.method as string) || 'GET',
        headers: (init?.headers as Record<string, string>) || {},
      });
      return new Response(makeStream([]), {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      });
    });

    saveActiveRunMetadata({
      runId: 'run-eof-test',
      sessionId: 'sess-1',
      lastSequenceNo: 5,
      updatedAt: Date.now(),
    });

    const controller = new AbortController();

    const promise = subscribeChatStream({
      runId: 'run-eof-test',
      sessionId: 'sess-1',
      signal: controller.signal,
      initialCursor: 5,
      onEvent,
      onReconnect,
    });
    const assertion = expect(promise).rejects.toThrow();

    // Advance fake timers through all backoffs
    for (let i = 0; i < 10; i++) {
      await vi.advanceTimersByTimeAsync(15000);
    }

    await assertion;

    // 1 initial attempt + 6 retries = 7 GET requests
    expect(fetchCalls.length).toBe(7);
    fetchCalls.forEach((call) => {
      expect(call.method).toBe('GET');
      expect(call.url).toContain('/api/v1/chat_stream/run-eof-test');
      expect(call.url).toContain('after=5');
    });

    expect(getActiveRunMetadata('sess-1')).toBeNull();
    expect(onEvent).not.toHaveBeenCalled();
    expect(onReconnect).toHaveBeenCalledTimes(6);
    vi.useRealTimers();
  });

  it('suppresses duplicate event IDs across reconnects and updates cursor', async () => {
    vi.useFakeTimers();
    const fetchCalls: { url: string; method: string; headers: Record<string, string> }[] = [];
    let callCount = 0;
    const events: unknown[] = [];

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      callCount++;
      const url = typeof input === 'string' ? input : (input as Request).url;
      fetchCalls.push({
        url,
        method: (init?.method as string) || 'GET',
        headers: (init?.headers as Record<string, string>) || {},
      });

      if (callCount === 1) {
        return new Response(
          makeStream([
            'id: 1\nevent: token\ndata: {"phase":"thinking","chunk":{"type":"token","content":"first"}}\n\n',
            'id: 2\nevent: token\ndata: {"phase":"thinking","chunk":{"type":"token","content":"second"}}\n\n',
          ]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      } else {
        return new Response(
          makeStream([
            'id: 2\nevent: token\ndata: {"phase":"thinking","chunk":{"type":"token","content":"duplicate-second"}}\n\n',
            'id: 3\nevent: token\ndata: {"phase":"drawing","chunk":{"type":"token","content":"third"}}\n\n',
            'id: 4\nevent: done\ndata: {"phase":"done","chunk":{"type":"done"}}\n\n',
          ]),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        );
      }
    });

    const controller = new AbortController();
    const promise = subscribeChatStream({
      runId: 'run-dup-test',
      sessionId: 'sess-dup',
      signal: controller.signal,
      initialCursor: 0,
      onEvent: (e) => events.push(e),
    });

    for (let i = 0; i < 5; i++) {
      await vi.advanceTimersByTimeAsync(5000);
    }
    await promise;

    expect(fetchCalls.length).toBe(2);
    expect(fetchCalls[0].method).toBe('GET');
    expect(fetchCalls[1].method).toBe('GET');
    expect(fetchCalls[1].url).toContain('after=2');

    expect(events).toHaveLength(4);
    expect(events[0]).toEqual({ phase: 'thinking', chunk: { type: 'token', content: 'first' } });
    expect(events[1]).toEqual({ phase: 'thinking', chunk: { type: 'token', content: 'second' } });
    expect(events[2]).toEqual({ phase: 'drawing', chunk: { type: 'token', content: 'third' } });
    expect(events[3]).toEqual({ phase: 'done', chunk: { type: 'done' } });

    expect(getActiveRunMetadata('sess-dup')).toBeNull();
    vi.useRealTimers();
  });

  it('preserves active run metadata on signal abort and does not wait indefinitely', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const signal = init?.signal as AbortSignal | undefined;
      const stream = new ReadableStream({
        start(controller) {
          if (signal) {
            signal.addEventListener('abort', () => {
              try {
                controller.close();
              } catch {}
            });
          }
        },
      });
      return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
    });

    saveActiveRunMetadata({
      runId: 'run-abort-test',
      sessionId: 'sess-abort',
      lastSequenceNo: 10,
      updatedAt: Date.now(),
    });

    const controller = new AbortController();
    const subscribePromise = subscribeChatStream({
      runId: 'run-abort-test',
      sessionId: 'sess-abort',
      signal: controller.signal,
      initialCursor: 10,
      onEvent: vi.fn(),
    });

    controller.abort();
    await subscribePromise;

    const meta = getActiveRunMetadata('sess-abort');
    expect(meta).not.toBeNull();
    expect(meta?.runId).toBe('run-abort-test');
    expect(meta?.lastSequenceNo).toBe(10);
  });

  it('replays full journal from sequence 0 across server restoration without POST or creating run', async () => {
    const fetchCalls: { url: string; method: string; headers: Record<string, string>; body?: string }[] = [];
    const events: unknown[] = [];

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      fetchCalls.push({
        url,
        method: (init?.method as string) || 'GET',
        headers: (init?.headers as Record<string, string>) || {},
        body: init?.body as string | undefined,
      });

      return new Response(
        makeStream([
          'id: 1\nevent: token\ndata: {"phase":"thinking","chunk":{"type":"token","content":"Prefix "}}\n\n',
          'id: 2\nevent: token\ndata: {"phase":"thinking","chunk":{"type":"token","content":"assistant output"}}\n\n',
          'id: 3\nevent: done\ndata: {"phase":"done","chunk":{"type":"done"}}\n\n',
        ]),
        { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
      );
    });

    const controller = new AbortController();
    await reattachChatStream(
      'run-restore-test',
      'sess-restore',
      'conv-restore',
      0,
      controller.signal,
      (e) => events.push(e),
    );

    expect(fetchCalls).toHaveLength(1);
    expect(fetchCalls[0].method).toBe('GET');
    expect(fetchCalls[0].url).toContain('/api/v1/chat_stream/run-restore-test');
    expect(fetchCalls[0].url).not.toContain('after=');
    expect(fetchCalls[0].body).toBeUndefined();

    expect(events).toEqual([
      { phase: 'thinking', chunk: { type: 'token', content: 'Prefix ' } },
      { phase: 'thinking', chunk: { type: 'token', content: 'assistant output' } },
      { phase: 'done', chunk: { type: 'done' } },
    ]);
  });
});

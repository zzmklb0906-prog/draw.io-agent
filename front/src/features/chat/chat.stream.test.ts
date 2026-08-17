import { describe, expect, it, vi } from 'vitest';
import { isStreamChunk, parseNdjsonStream } from './chat.stream';

const encoder = new TextEncoder();
const makeStream = (chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });

describe('NDJSON stream parser', () => {
  it('parses an event split across arbitrary chunks', async () => {
    const events: unknown[] = [];
    await parseNdjsonStream(
      makeStream(['{"phase":"draw', 'ing","chunk":{"type":"drawio_node","id":"2",', '"xml":"<mxCell/>"}}\n']),
      (event) => events.push(event),
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ phase: 'drawing', chunk: { type: 'drawio_node', id: '2' } });
  });

  it('parses multiple lines and reports malformed lines without failing', async () => {
    const events: unknown[] = [];
    const malformed = vi.fn();
    await parseNdjsonStream(
      makeStream(['{"phase":"thinking","chunk":{"type":"status","content":"A"}}\ninvalid\n{"phase":"done","chunk":{"type":"done"}}\n']),
      (event) => events.push(event),
      malformed,
    );
    expect(events).toHaveLength(2);
    expect(malformed).toHaveBeenCalledWith('invalid');
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
});

describe('tool lifecycle chunk', () => {
  it('accepts a running tool event', () => {
    expect(isStreamChunk({ type: 'tool', callId: 'call-1', name: 'load_skill', status: 'RUNNING', startedAt: 100 })).toBe(true);
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { updateEvalCase } from '../eval/eval.api';
import { queryMemoryRetrieve } from '../memory/memory.api';
import { queryMonitorSummary, submitCapabilityFeedback } from './monitor.api';

describe('monitor and eval Phase 1 & 2 APIs', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('submits capability feedback with exact payload and auth headers', async () => {
    localStorage.setItem(
      'draw-io-agent-demo-user',
      JSON.stringify({ username: 'tester', token: 'test-jwt', expiresAt: Date.now() + 60000 })
    );

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0000', data: true }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await submitCapabilityFeedback('inv-999', {
      searchId: 'search-1',
      capabilityId: 'cap-web-search',
      judgment: 'NO_IMPACT',
      note: '精准命中目标',
    });

    expect(result).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/monitor/invocations/inv-999/capability-feedback'),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-User-Id': 'tester',
          Authorization: 'Bearer test-jwt',
        }),
        body: JSON.stringify({
          searchId: 'search-1',
          capabilityId: 'cap-web-search',
          judgment: 'NO_IMPACT',
          note: '精准命中目标',
        }),
      })
    );

    const mismatchResult = await submitCapabilityFeedback('inv-999', {
      searchId: 'search-2',
      capabilityId: 'cap-bad-tool',
      judgment: 'WRONG_SELECTION',
    });

    expect(mismatchResult).toBe(true);
    expect(fetchMock).toHaveBeenLastCalledWith(
      expect.stringContaining('/api/v1/monitor/invocations/inv-999/capability-feedback'),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-User-Id': 'tester',
          Authorization: 'Bearer test-jwt',
        }),
        body: JSON.stringify({
          searchId: 'search-2',
          capabilityId: 'cap-bad-tool',
          judgment: 'WRONG_SELECTION',
        }),
      })
    );
  });

  it('updates eval case via PUT /api/v1/eval/cases/{id}', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0000', data: { updated: true } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await updateEvalCase('case-123', {
      name: '更新后用例',
      agentId: '300000',
      prompt: '画一个微服务调用图',
      expectations: { passScore: 80 },
      rubric: { contentWeight: 50 },
      tags: ['diagram'],
      enabled: true,
    });

    expect(result).toEqual({ updated: true });
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/eval/cases/case-123'),
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          name: '更新后用例',
          agentId: '300000',
          prompt: '画一个微服务调用图',
          expectations: { passScore: 80 },
          rubric: { contentWeight: 50 },
          tags: ['diagram'],
          enabled: true,
        }),
      })
    );
  });

  it('queries memory retrieve via GET /api/v1/memories/retrieve with params', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: [{ memoryId: 'mem-1', content: '用户偏好深色主题' }],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await queryMemoryRetrieve('alice', 'project-x', '深色', 5);
    expect(result).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/memories/retrieve?userId=alice&projectId=project-x&query=%E6%B7%B1%E8%89%B2&limit=5'),
      expect.anything()
    );
  });

  it('queries monitor summary with time window hours and session params', async () => {
    const mockSummary = {
      windowHours: 24,
      total: 10,
      success: 8,
      errors: 1,
      active: 1,
      successRate: 0.8889,
      averageDurationMs: 250,
      p95DurationMs: 500,
      inputTokens: 1000,
      outputTokens: 200,
      totalTokens: 1200,
      estimatedCost: 0,
      registeredTools: ['drawio'],
    };

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0000', data: mockSummary }),
    });
    vi.stubGlobal('fetch', fetchMock);

    // 1. Without params
    const res1 = await queryMonitorSummary();
    expect(res1).toEqual(mockSummary);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/v1/monitor/summary', expect.anything());

    // 2. With hours only
    await queryMonitorSummary(undefined, 24);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/v1/monitor/summary?hours=24', expect.anything());

    // 3. With session and 1 hour
    await queryMonitorSummary('sess-100', 1);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/v1/monitor/summary?sessionId=sess-100&hours=1', expect.anything());

    // 4. With session and 168 hours (7d)
    await queryMonitorSummary('sess-200', 168);
    expect(fetchMock).toHaveBeenLastCalledWith('/api/v1/monitor/summary?sessionId=sess-200&hours=168', expect.anything());
  });
});

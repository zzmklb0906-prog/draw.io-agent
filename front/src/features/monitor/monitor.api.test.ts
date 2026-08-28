import { beforeEach, describe, expect, it, vi } from 'vitest';
import { updateEvalCase } from '../eval/eval.api';
import { queryMemoryRetrieve } from '../memory/memory.api';
import { submitCapabilityFeedback } from './monitor.api';

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
      judgment: 'GOOD',
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
          judgment: 'GOOD',
          note: '精准命中目标',
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
});

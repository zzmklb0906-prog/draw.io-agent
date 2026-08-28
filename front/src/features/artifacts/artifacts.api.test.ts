import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  branchArtifact,
  queryArtifact,
  queryArtifactDiff,
  queryArtifacts,
  rollbackArtifact,
} from './artifacts.api';

describe('artifacts.api', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('queries artifacts list for conversationId', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: [
          {
            id: 'art-1',
            conversationId: 'conv-1',
            invocationId: 'inv-1',
            lineageId: 'lin-1',
            branchName: 'main',
            artifactType: 'DRAWIO',
            name: '架构图',
            mimeType: 'application/vnd.jgraph.mxfile',
            content: '<mxfile></mxfile>',
            contentHash: 'hash1',
            sizeBytes: 100,
            versionNo: 1,
            status: 'ACTIVE',
            createdAt: '2026-08-21T00:00:00Z',
          },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await queryArtifacts('conv-1');
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('架构图');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/artifacts?conversationId=conv-1'),
      expect.anything()
    );
  });

  it('queries single artifact detail', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: { id: 'art-1', name: '详情' },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await queryArtifact('art-1');
    expect(result.id).toBe('art-1');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/artifacts/art-1'),
      expect.anything()
    );
  });

  it('queries artifact diff between target and base', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: '@@ -1 +1 @@\n-old\n+new',
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const diff = await queryArtifactDiff('target-id', 'base-id');
    expect(diff).toContain('+new');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/artifacts/target-id/diff/base-id'),
      expect.anything()
    );
  });

  it('posts rollback with invocationId and idempotencyKey', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: { id: 'art-rollback', versionNo: 3, branchName: 'main' },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await rollbackArtifact('art-target', {
      invocationId: 'inv-123',
      idempotencyKey: 'idem-rollback-key',
    });

    expect(result.versionNo).toBe(3);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/artifacts/art-target/rollback'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          invocationId: 'inv-123',
          idempotencyKey: 'idem-rollback-key',
        }),
      })
    );
  });

  it('posts branch creation with branchName and idempotencyKey', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: { id: 'art-branch', versionNo: 1, branchName: 'feature-exp' },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await branchArtifact('art-source', {
      invocationId: 'inv-456',
      idempotencyKey: 'idem-branch-key',
      branchName: 'feature-exp',
    });

    expect(result.branchName).toBe('feature-exp');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/artifacts/art-source/branches'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          invocationId: 'inv-456',
          idempotencyKey: 'idem-branch-key',
          branchName: 'feature-exp',
        }),
      })
    );
  });
});

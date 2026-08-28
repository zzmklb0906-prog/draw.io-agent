import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createWorkspace,
  deleteWorkspaceMember,
  putWorkspaceMember,
  queryWorkspaceMembers,
  queryWorkspaces,
} from './workspaces.api';

describe('workspaces.api', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('queries workspaces via GET /api/v1/workspaces', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: [
          {
            id: 'ws-100',
            name: '默认工作区',
            description: '主工作区',
            role: 'OWNER',
            createdAt: '2026-08-21T00:00:00Z',
          },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await queryWorkspaces();
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('默认工作区');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/workspaces'),
      expect.anything()
    );
  });

  it('creates workspace via POST /api/v1/workspaces', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: {
          id: 'ws-new',
          name: '新图表协作区',
          description: '测试',
          role: 'OWNER',
          createdAt: '2026-08-21T00:00:00Z',
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await createWorkspace({
      name: '新图表协作区',
      description: '测试',
    });

    expect(result.id).toBe('ws-new');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/workspaces'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          name: '新图表协作区',
          description: '测试',
        }),
      })
    );
  });

  it('queries workspace members via GET /api/v1/workspaces/{id}/members', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: [
          {
            username: 'alice',
            displayName: 'Alice',
            role: 'OWNER',
            createdAt: '2026-08-21T00:00:00Z',
          },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const members = await queryWorkspaceMembers('ws-100');
    expect(members).toHaveLength(1);
    expect(members[0].username).toBe('alice');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/workspaces/ws-100/members'),
      expect.anything()
    );
  });

  it('puts workspace member via PUT /api/v1/workspaces/{id}/members', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: true,
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await putWorkspaceMember('ws-100', {
      username: 'bob',
      role: 'EDITOR',
    });

    expect(result).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/workspaces/ws-100/members'),
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          username: 'bob',
          role: 'EDITOR',
        }),
      })
    );
  });

  it('deletes workspace member via DELETE /api/v1/workspaces/{id}/members/{username}', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0000',
        data: true,
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await deleteWorkspaceMember('ws-100', 'bob@test.com');
    expect(result).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/workspaces/ws-100/members/bob%40test.com'),
      expect.objectContaining({
        method: 'DELETE',
      })
    );
  });
});

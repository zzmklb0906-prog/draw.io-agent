import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './auth.store';

describe('demo auth store', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    useAuthStore.setState({ user: null });
  });

  it('persists only the issued token, not the password', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>({code:'0000',data:{username:'admin',token:'opaque-token',expiresAt:Date.now()+60000}})}));
    const result = await useAuthStore.getState().login('admin', 'admin');
    expect(result).toEqual({ success: true });
    const stored = localStorage.getItem('draw-io-agent-demo-user');
    expect(stored).not.toContain('password');
    expect(stored).not.toContain('admin/admin');
    expect(stored).toContain('opaque-token');
    expect(sessionStorage.getItem('draw-io-agent-demo-user')).toBeNull();
  });

  it('calls server logout with bearer token and clears local authentication state', async () => {
    localStorage.setItem(
      'draw-io-agent-demo-user',
      JSON.stringify({ username: 'admin', token: 'bearer-token-123', expiresAt: Date.now() + 60000 })
    );
    useAuthStore.setState({
      user: { username: 'admin', token: 'bearer-token-123', expiresAt: Date.now() + 60000 },
    });

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0000', data: { loggedOut: true } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().logout();

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/auth/logout'),
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          Authorization: 'Bearer bearer-token-123',
        }),
      })
    );
    expect(localStorage.getItem('draw-io-agent-demo-user')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('guarantees local auth cleanup even if server logout rejects or fails', async () => {
    localStorage.setItem(
      'draw-io-agent-demo-user',
      JSON.stringify({ username: 'admin', token: 'bearer-token-456', expiresAt: Date.now() + 60000 })
    );
    useAuthStore.setState({
      user: { username: 'admin', token: 'bearer-token-456', expiresAt: Date.now() + 60000 },
    });

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')));

    await useAuthStore.getState().logout();

    expect(localStorage.getItem('draw-io-agent-demo-user')).toBeNull();
    expect(sessionStorage.getItem('draw-io-agent-demo-user')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });
});

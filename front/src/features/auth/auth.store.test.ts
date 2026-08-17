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
    expect(await useAuthStore.getState().login('admin', 'admin')).toBe(true);
    const stored = localStorage.getItem('draw-io-agent-demo-user');
    expect(stored).not.toContain('password');
    expect(stored).not.toContain('admin/admin');
    expect(stored).toContain('opaque-token');
    expect(sessionStorage.getItem('draw-io-agent-demo-user')).toBeNull();
  });
});

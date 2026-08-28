import { create } from 'zustand';
import type { AuthUser } from './auth.types';
import { apiUrl } from '../../shared/api/httpClient';

const STORAGE_KEY = 'draw-io-agent-demo-user';

function storedAuth(): string | null {
  // localStorage is shared by same-origin tabs; sessionStorage is not reliable when a
  // monitor tab is opened with noopener. Migrate existing sessions transparently.
  const persistent = localStorage.getItem(STORAGE_KEY);
  if (persistent) return persistent;
  const legacy = sessionStorage.getItem(STORAGE_KEY);
  if (legacy) localStorage.setItem(STORAGE_KEY, legacy);
  return legacy;
}

function readUser(): AuthUser | null {
  try {
    const raw = storedAuth();
    const user=raw ? (JSON.parse(raw) as AuthUser) : null;return user&&user.token&&user.expiresAt>Date.now()?user:null;
  } catch {
    return null;
  }
}

export interface LoginResult {
  success: boolean;
  error?: string;
}

interface AuthState {
  user: AuthUser | null;
  login: (username: string, password: string) => Promise<LoginResult>;
  logout: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: readUser(),
  login: async (username, password) => {
    try {
      const response = await fetch(apiUrl('/api/v1/auth/login'), {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ username, password })
      });
      const payload = await response.json().catch(() => ({})) as { code?: string; info?: string; message?: string; data?: AuthUser };
      
      if (!response.ok || payload.code !== '0000' || !payload.data) {
        const errorMsg = payload.info || payload.message || (response.status === 429 ? '登录尝试过多，账号已被暂时限流，请稍后重试' : '登录失败，请检查账号密码');
        return { success: false, error: errorMsg };
      }

      localStorage.setItem(STORAGE_KEY, JSON.stringify(payload.data));
      sessionStorage.removeItem(STORAGE_KEY);
      set({ user: payload.data });
      return { success: true };
    } catch {
      return { success: false, error: '网络连接失败，请检查后端服务是否启动' };
    }
  },
  logout: async () => {
    try {
      const authRaw = storedAuth();
      const auth = authRaw ? (JSON.parse(authRaw) as AuthUser) : null;
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (auth?.token) {
        headers.Authorization = `Bearer ${auth.token}`;
      }
      await fetch(apiUrl('/api/v1/auth/logout'), {
        method: 'POST',
        credentials: 'include',
        headers,
      }).catch(() => {});
    } finally {
      sessionStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem(STORAGE_KEY);
      set({ user: null });
    }
  },
}));

if (typeof window !== 'undefined') {
  window.addEventListener('agent-auth-expired', () => useAuthStore.setState({ user: null }));
}

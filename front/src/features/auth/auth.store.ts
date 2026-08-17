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

interface AuthState {
  user: AuthUser | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: readUser(),
  login: async (username, password) => {
    try { const response=await fetch(apiUrl('/api/v1/auth/login'),{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify({username,password})});if(!response.ok)return false;const payload=await response.json() as {code:string;data?:AuthUser};if(payload.code!=='0000'||!payload.data)return false;localStorage.setItem(STORAGE_KEY,JSON.stringify(payload.data));sessionStorage.removeItem(STORAGE_KEY);set({user:payload.data});return true; } catch { return false; }
  },
  logout: () => {
    sessionStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(STORAGE_KEY);
    set({ user: null });
  },
}));

if (typeof window !== 'undefined') {
  window.addEventListener('agent-auth-expired', () => useAuthStore.setState({ user: null }));
}

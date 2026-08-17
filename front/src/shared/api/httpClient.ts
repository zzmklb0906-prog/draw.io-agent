import { env } from '../config/env';

export interface ApiResponse<T> {
  code: string;
  info: string;
  data?: T;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const apiUrl = (path: string) => `${env.apiBaseUrl}${path}`;

const AUTH_STORAGE_KEY = 'draw-io-agent-demo-user';

export function authenticatedHeaders(): Record<string, string> {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY) ?? sessionStorage.getItem(AUTH_STORAGE_KEY) ?? '{}';
    const auth = JSON.parse(raw) as { username?: string; token?: string };
    return {
      ...(auth.username ? { 'X-User-Id': auth.username } : {}),
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
    };
  } catch {
    return {};
  }
}

function expireAuthentication() {
  sessionStorage.removeItem(AUTH_STORAGE_KEY);
  localStorage.removeItem(AUTH_STORAGE_KEY);
  window.dispatchEvent(new Event('agent-auth-expired'));
}

export async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let response = await fetch(apiUrl(path), {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...authenticatedHeaders(),
      ...init?.headers,
    },
  });

  if (response.status === 401 && path !== '/api/v1/auth/refresh') {
    const refreshed = await fetch(apiUrl('/api/v1/auth/refresh'), { method: 'POST', credentials: 'include', headers: { Accept: 'application/json' } });
    if (refreshed.ok) {
      const payload = await refreshed.json() as ApiResponse<{ username: string; displayName?: string; token: string; expiresAt: number }>;
      if (payload.code === '0000' && payload.data) {
        localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(payload.data));
        response = await fetch(apiUrl(path), { ...init, credentials: 'include', headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...authenticatedHeaders(), ...init?.headers } });
      }
    }
  }

  if (!response.ok) {
    if (response.status === 401) expireAuthentication();
    let errorCode = response.status === 401 ? 'AUTH_REQUIRED' : 'HTTP_ERROR';
    let message = response.status === 401 ? '登录会话已失效，请重新登录' : `服务请求失败（HTTP ${response.status}）`;
    try {
      const body = await response.json() as Partial<ApiResponse<unknown>>;
      errorCode = body.code || errorCode;
      message = body.info || message;
    } catch { /* non-JSON proxy/server error */ }
    throw new ApiError(message, errorCode, response.status);
  }

  const payload = (await response.json()) as ApiResponse<T>;
  if (payload.code !== '0000') {
    throw new ApiError(payload.info || '业务请求失败', payload.code, response.status);
  }
  if (payload.data === undefined) {
    throw new ApiError('服务端未返回有效数据', 'EMPTY_DATA', response.status);
  }
  return payload.data;
}

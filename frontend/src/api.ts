export type Row = Record<string, any>;
let csrf = '';
export async function refreshCsrf() { const r = await fetch('/api/auth/csrf', { credentials: 'same-origin' }); if (!r.ok) throw new Error('无法初始化请求校验'); csrf = (await r.json()).token; }
export async function api<T = any>(path: string, options: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, ...init } = options;
  const headers = new Headers(init.headers);
  const method = init.method || (json === undefined ? 'GET' : 'POST');
  if (method !== 'GET' && method !== 'HEAD') { if (!csrf) await refreshCsrf(); headers.set('X-XSRF-TOKEN', csrf); }
  if (json !== undefined) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, method, headers, credentials: 'same-origin', body: json === undefined ? init.body : JSON.stringify(json) });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '请求未完成' }));
    if (response.status === 401 && path !== '/api/auth/login') window.dispatchEvent(new Event('moss-auth-required'));
    throw new Error(`${error.message || '请求未完成'}${error.requestId ? `（请求 ${error.requestId.slice(0, 8)}）` : ''}`);
  }
  return response.json();
}
export const listUrl = (type: string, q = '', state = '', cursor = '') => `/api/${type}?${new URLSearchParams({ q, state, cursor, limit: '20' })}`;
export const date = (value: unknown) => value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(String(value))) : '—';
export const pretty = (value: unknown) => JSON.stringify(value, null, 2);

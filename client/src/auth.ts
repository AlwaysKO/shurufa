import { ref } from 'vue';
export const authenticated = ref(false);
export const loginName = ref('');
// 认证操作开始及完成都换代，旧请求不得写回新的登录状态。
let authEpoch = 0;
let onExpired: (() => void) | undefined;
export function onSessionExpired(callback: () => void) { onExpired = callback; }
export async function dashboardFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const epoch = authEpoch;
  const response = await fetch(url, { ...options, credentials: 'same-origin', headers: { ...Object.fromEntries(new Headers(options.headers).entries()), 'X-Dashboard-Request': '1' } });
  if (response.status === 401 && epoch === authEpoch) {
    authEpoch++;
    authenticated.value = false; loginName.value = ''; onExpired?.();
  }
  return response;
}
export async function checkSession(): Promise<boolean> {
  const epoch = authEpoch;
  try {
    const response = await dashboardFetch('/api/v1/auth/session');
    const username = response.ok ? (await response.json()).username : '';
    if (epoch === authEpoch) {
      authenticated.value = response.ok;
      loginName.value = username;
    }
  } catch {
    if (epoch === authEpoch) { authenticated.value = false; loginName.value = ''; }
  }
  return authenticated.value;
}
export async function login(username: string, password: string): Promise<void> {
  const epoch = ++authEpoch;
  const response = await dashboardFetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
  if (!response.ok) throw new Error(response.status === 429 ? '尝试过多，请 15 分钟后重试' : response.status === 401 ? '账号或密码错误' : '登录失败，请检查后台配置');
  const name = (await response.json()).username;
  if (epoch !== authEpoch) throw new Error('登录状态已改变，请重试');
  authEpoch++; loginName.value = name; authenticated.value = true;
}
export async function logout(): Promise<void> {
  const epoch = ++authEpoch;
  const response = await dashboardFetch('/api/v1/auth/logout', { method: 'POST' });
  if (!response.ok) throw new Error('退出失败，请检查网络后重试');
  if (epoch !== authEpoch) throw new Error('登录状态已改变，请重试');
  authEpoch++; authenticated.value = false; loginName.value = ''; onExpired?.();
}

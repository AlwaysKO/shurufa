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
/** 原文件上传，不在主线程生成Base64；进度仅表示已传输字节，响应成功后才算保存。 */
export function dashboardUpload(url: string, file: File, onProgress: (percent: number) => void, method = 'POST', metadata?: unknown): Promise<Response> {
  const epoch = authEpoch;
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const id = crypto.randomUUID(), started = performance.now();
    let transferMs: number | null = null;
    const report = (outcome: string) => {
      const data = { id, kind: url.includes('synthesis-library') ? 'synthesis' : 'sticker', outcome, totalMs: Math.round(performance.now() - started), transferMs, bytes: file.size, status: xhr.status || 0 };
      // 仅上报耗时，不等待诊断请求，不影响上传结果。
      const user = new URLSearchParams(url.split('?')[1] ?? '').get('user_id');
      void dashboardFetch('/api/v1/dashboard/upload-diagnostics' + (user ? `?user_id=${encodeURIComponent(user)}` : ''), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }).catch(() => {});
    };
    xhr.upload.onload = () => { transferMs = Math.round(performance.now() - started); };
    xhr.open(method, url);
    xhr.timeout = 120_000;
    xhr.setRequestHeader('X-Dashboard-Request', '1');
    xhr.setRequestHeader('X-Upload-Id', id);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    if (metadata) xhr.setRequestHeader('X-Upload-Metadata', btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(metadata)))));
    xhr.upload.onprogress = event => {
      if (event.lengthComputable) onProgress(Math.round(event.loaded / event.total * 100));
    };
    xhr.onload = () => {
      report('load');
      if (xhr.status === 401 && epoch === authEpoch) {
        authEpoch++; authenticated.value = false; loginName.value = ''; onExpired?.();
      }
      resolve(new Response(xhr.responseText, { status: xhr.status }));
    };
    xhr.onerror = () => { report('error'); reject(new Error('上传连接中断，请刷新核对是否已保存后再重试')); };
    xhr.ontimeout = () => { report('timeout'); reject(new Error('上传超时，请刷新核对是否已保存后再重试')); };
    xhr.onabort = () => { report('abort'); reject(new Error('上传已取消')); };
    xhr.send(file);
  });
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

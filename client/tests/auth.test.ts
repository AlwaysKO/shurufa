import { readFileSync } from 'node:fs';
import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
const source = (name: string) => readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8');
it('路由在挂载页面之前验证会话，登录页无需用户目录', () => {
  expect(source('main.ts')).toContain('router.beforeEach');
  expect(source('main.ts')).toContain('router.isReady()');
  expect(source('App.vue')).toContain('watch(authenticated');
  expect(source('App.vue')).not.toContain('onMounted(() => void initializeUsers())');
  expect(source('App.vue')).toContain('v-if="authenticated"');
});
it('所有 API 统一处理登录失效且发送防跨站头', () => {
  expect(source('api/index.ts')).toContain('dashboardFetch(url');
  expect(source('auth.ts')).toContain("'X-Dashboard-Request': '1'");
  expect(source('auth.ts')).toContain('response.status === 401');
});

it('会话、登录和 401 清理在真实认证状态模块中生效', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth');
  const expired = vi.fn(); auth.onSessionExpired(expired);
  const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ username: 'admin' }), { status: 200 }))
    .mockResolvedValueOnce(new Response('{}', { status: 401 }))
    .mockRejectedValueOnce(new Error('offline'));
  vi.stubGlobal('fetch', fetchMock);
  try {
    await auth.login('admin', 'adminhaha');
    expect(auth.authenticated.value).toBe(true);
    expect(auth.loginName.value).toBe('admin');
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ credentials: 'same-origin', headers: { 'X-Dashboard-Request': '1' } });
    await auth.dashboardFetch('/api/v1/dashboard/users');
    expect(auth.authenticated.value).toBe(false); expect(expired).toHaveBeenCalled();
    expect(await auth.checkSession()).toBe(false);
  } finally { vi.unstubAllGlobals(); }
});
it('退出失败不假装销毁会话，成功退出才清理状态', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth'); auth.authenticated.value = true;
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(new Response(null, { status: 204 })));
  try {
    await expect(auth.logout()).rejects.toThrow('offline'); expect(auth.authenticated.value).toBe(true);
    await auth.logout(); expect(auth.authenticated.value).toBe(false);
  } finally { vi.unstubAllGlobals(); }
});
it('会话失效跳转间隙不挂载受保护页面', () => {
  expect(source('App.vue')).toContain('v-if="!authenticated && route.path === \'/login\'"');
});

it('退出成功后旧会话 200 不得恢复登录', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth');
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(new Response(null, { status: 204 })));
  try {
    auth.authenticated.value = true;
    const pending = auth.checkSession(); await auth.logout();
    finish(new Response(JSON.stringify({ username: 'old' }), { status: 200 }));
    expect(await pending).toBe(false); expect(auth.loginName.value).toBe('');
  } finally { vi.unstubAllGlobals(); }
});
it('新登录后旧 API 的 401 不得清理新会话', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth');
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'new' }), { status: 200 })));
  try {
    const pending = auth.dashboardFetch('/api/v1/dashboard/users'); await auth.login('new', 'secret');
    finish(new Response('{}', { status: 401 })); await pending;
    expect(auth.authenticated.value).toBe(true); expect(auth.loginName.value).toBe('new');
  } finally { vi.unstubAllGlobals(); }
});
it('新登录后旧会话查询不得覆盖账号', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth');
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'new' }), { status: 200 })));
  try {
    const pending = auth.checkSession(); await auth.login('new', 'secret');
    finish(new Response(JSON.stringify({ username: 'old' }), { status: 200 })); await pending;
    expect(auth.loginName.value).toBe('new');
  } finally { vi.unstubAllGlobals(); }
});
it('较晚发起的登录优先，旧登录响应不能覆盖它', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const auth = await import('../src/auth');
  let finish!: (value: Response) => void;
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'new' }), { status: 200 })));
  try {
    const oldLogin = auth.login('old', 'secret');
    const cancelled = expect(oldLogin).rejects.toThrow('登录状态已改变');
    await auth.login('new', 'secret');
    finish(new Response(JSON.stringify({ username: 'old' }), { status: 200 })); await cancelled;
    expect(auth.loginName.value).toBe('new');
  } finally { vi.unstubAllGlobals(); }
});
it('登出期间返回的用户初始化响应不得回填用户或本地选择', async () => {
  const { vi } = await import('../../server/node_modules/vitest/dist/index.js');
  const body = source('App.vue').split('async function initializeUsers()')[1]!.split('watch(authenticated')[0]!;
  const authenticated = { value: true }; const selected = { value: null }; const ready = { value: false };
  const current = { value: '' }; const error = { value: '' }; const persist = vi.fn();
  let finish!: (value: unknown) => void;
  const api = { users: () => new Promise(resolve => { finish = resolve; }) };
  const initialize = new Function('api', 'authenticated', 'selectedUser', 'usersReady', 'currentUserId', 'initializationError', 'setCurrentUserId',
    `let initializationRequestId = 0; async function initializeUsers() ${body}; return initializeUsers;`)(api, authenticated, selected, ready, current, error, persist);
  const pending = initialize(); authenticated.value = false;
  finish({ users: [{ id: 'old-user' }] }); await pending;
  expect(selected.value).toBeNull(); expect(persist).not.toHaveBeenCalled(); expect(ready.value).toBe(false);
});

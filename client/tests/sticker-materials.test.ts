import { readFileSync, existsSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
const mounted: Vue.App[] = [];
const settle = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await Vue.nextTick();
};
afterEach(() => {
  mounted.splice(0).forEach((app) => app.unmount());
  vi.useRealTimers();
});
const material = {
  sha256: 'a'.repeat(64),
  ids: [1, 2],
  keywords: ['开心', '高兴'],
  url: '/uploads/stickers/a.gif',
  format: 'gif',
  width: 2,
  height: 2,
  assigned: true,
};
const job = (id = 'j1', status = 'running') => ({
  id,
  agentId: 'a1',
  status,
  createdAt: new Date().toISOString(),
  counts: { existing: 5, imported: 2, missing: 1, failed: 0 },
  discovered: 8,
  validated: 8,
  validationFailed: 0,
  sourceErrors: {},
  errors: [],
  jobErrorCode: null,
});
async function setup(overrides: Record<string, any> = {}) {
  const api = {
    list: vi.fn(async (q: any) => ({
      items: [material],
      total: 61,
      page: q.page,
      pageSize: 30,
      warnings: [],
    })),
    keywords: vi.fn(async () => ({ material: { ...material, keywords: ['开心'] } })),
    agents: vi.fn(async () => ({
      agents: [{ id: 'a1', name: '电脑', lastSeenAt: new Date().toISOString(), revokedAt: null }],
    })),
    jobs: vi.fn(async () => ({ jobs: [job()] })),
    job: vi.fn(async (id: string) => ({ job: job(id) })),
    pair: vi.fn(async () => ({
      code: '12345678',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
    })),
    start: vi.fn(async () => ({ job: job() })),
    cancel: vi.fn(async () => ({ job: job('j1', 'cancelled') })),
    revoke: vi.fn(async () => ({ ok: true })),
    ...overrides,
  };
  const file = new URL('../src/views/StickerMaterials.vue', import.meta.url);
  expect(existsSync(file), '素材库组件应存在').toBe(true);
  const { descriptor } = parse(readFileSync(file, 'utf8'));
  const script = compileScript(descriptor, { id: 'materials-test' });
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} as { default: { setup: Function } } };
  new Function('require', 'module', 'exports', code)(
    (id: string) => {
      if (id === 'vue') return Vue;
      if (id === '../api/stickerMaterials') return { stickerMaterials: api };
      if (id === '../api') return { scopedAssetUrl: (url: string) => url };
      if (id === '../confirmation') return { useConfirmation: () => async () => true };
      throw Error(id);
    },
    module,
    module.exports,
  );
  let state: any;
  const emit = vi.fn();
  const renderer = Vue.createRenderer<any, any>({
    createElement: () => ({}),
    createText: () => ({}),
    createComment: () => ({}),
    setText() {},
    setElementText() {},
    parentNode: () => null,
    nextSibling: () => null,
    patchProp() {},
    insert() {},
    remove() {},
    insertStaticContent: () => [{}, {}],
  });
  const app = renderer.createApp({
    setup() {
      state = module.exports.default.setup({}, { expose() {}, emit });
      return () => null;
    },
  });
  app.mount({});
  mounted.push(app);
  await settle();
  return { state, api, emit, app };
}
it('筛选、搜索重置分页并显示同图全部历史关键词', async () => {
  const { state, api } = await setup();
  expect(state.items.value[0].keywords).toEqual(['开心', '高兴']);
  await state.loadMaterials(2);
  expect(api.list).toHaveBeenLastCalledWith({ state: 'all', q: '', page: 2, page_size: 30 });
  state.filter.value = 'unassigned';
  state.search.value = '猫';
  await state.applyFilter();
  expect(api.list).toHaveBeenLastCalledWith({
    state: 'unassigned',
    q: '猫',
    page: 1,
    page_size: 30,
  });
});
it('关键词编辑只发送显式差量并使用服务端返回', async () => {
  const { state, api, emit } = await setup();
  await state.changeKeywords(material, [], ['高兴']);
  expect(api.keywords).toHaveBeenCalledWith(material.sha256, { add: [], remove: ['高兴'] });
  expect(emit).toHaveBeenCalledWith('changed');
});
it('已有跳过与新增分开统计，离线不能开始导入', async () => {
  const { state, api } = await setup();
  expect(state.currentJob.value.counts).toMatchObject({ existing: 5, imported: 2 });
  state.agents.value[0].lastSeenAt = '2000-01-01';
  expect(state.online.value).toBe(false);
  await state.startImport();
  expect(api.start).not.toHaveBeenCalled();
});
it('配对码过期、取消与任务失败有明确状态', async () => {
  vi.useFakeTimers();
  const { state, api } = await setup();
  await state.createPairing();
  expect(state.pairing.value.code).toBe('12345678');
  state.now.value += 600001;
  expect(state.pairingExpired.value).toBe(true);
  await state.cancelImport();
  expect(api.cancel).toHaveBeenCalledWith('j1');
  expect(state.currentJob.value.status).toBe('cancelled');
  expect(state.statusText('failed')).toBe('导入失败');
});
it('过时素材成功或失败不可覆盖新筛选结果', async () => {
  const { state, api } = await setup();
  let resolve!: (v: any) => void;
  api.list.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  const old = state.loadMaterials(2);
  await state.applyFilter();
  resolve({ items: [], total: 0, page: 2, pageSize: 30, warnings: [] });
  await old;
  expect(state.items.value).toHaveLength(1);
  let reject!: (e: any) => void;
  api.list.mockImplementationOnce(() => new Promise((_, r) => (reject = r)));
  const bad = state.loadMaterials(2);
  await state.applyFilter();
  reject(Error('old'));
  await bad;
  expect(state.materialError.value).toBe('');
});
it('切换任务后旧响应失效，卸载停止轮询且忽略pending', async () => {
  vi.useFakeTimers();
  const { state, api, app } = await setup();
  let resolve!: (v: any) => void;
  api.job.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  const old = state.selectJob('j1');
  await state.selectJob('j2');
  resolve({ job: job('j1') });
  await old;
  expect(state.currentJob.value.id).toBe('j2');
  app.unmount();
  const calls = api.agents.mock.calls.length;
  await vi.advanceTimersByTimeAsync(20000);
  expect(api.agents).toHaveBeenCalledTimes(calls);
});
it('失败修改不假更新，多次点击同卡片不重复提交', async () => {
  const { state, api } = await setup();
  let reject!: (e: any) => void;
  api.keywords.mockImplementationOnce(() => new Promise((_, r) => (reject = r)));
  const first = state.changeKeywords(material, ['猫'], []);
  await state.changeKeywords(material, ['狗'], []);
  expect(api.keywords).toHaveBeenCalledTimes(1);
  reject(Error('保存失败'));
  await first;
  expect(state.items.value[0].keywords).toEqual(['开心', '高兴']);
  expect(state.cardErrors.value[material.sha256]).toContain('保存失败');
});
it('开始导入成功，刷新后恢复任务，并在终态刷新素材', async () => {
  const { state, api, emit } = await setup({ jobs: vi.fn(async () => ({ jobs: [] })) });
  await state.startImport();
  expect(api.start).toHaveBeenCalledWith('a1');
  expect(state.currentJob.value.status).toBe('running');
  const before = api.list.mock.calls.length;
  api.jobs.mockResolvedValue({ jobs: [job('j1', 'completed')] });
  await state.refreshImport();
  expect(api.list.mock.calls.length).toBeGreaterThan(before);
  expect(emit).toHaveBeenCalledWith('changed');
  expect(state.currentJob.value.status).toBe('completed');
});
it('切换助手使旧任务失败响应失效', async () => {
  const { state, api } = await setup();
  let reject!: (e: any) => void;
  api.job.mockImplementationOnce(() => new Promise((_, r) => (reject = r)));
  const pending = state.selectJob('j1');
  state.selectedAgent.value = 'a2';
  await settle();
  reject(Error('旧助手失败'));
  await pending;
  expect(state.controlError.value).toBe('');
  expect(state.currentJob.value).toBe(null);
});
it('任务选择等待期间，轮询不能退回其他任务', async () => {
  const { state, api } = await setup();
  let resolve!: (v: any) => void;
  api.job.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  const pending = state.selectJob('j2');
  await state.refreshImport();
  expect(state.currentJob.value).toBe(null);
  resolve({ job: job('j2') });
  await pending;
  expect(state.currentJob.value.id).toBe('j2');
});
it('卸载时pending列表成功和配对失败都不得写回', async () => {
  const { state, api, app } = await setup();
  let resolve!: (v: any) => void, reject!: (e: any) => void;
  api.list.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  api.pair.mockImplementationOnce(() => new Promise((_, r) => (reject = r)));
  const list = state.loadMaterials(2),
    pair = state.createPairing();
  app.unmount();
  resolve({ items: [], total: 0, page: 2, warnings: [] });
  reject(Error('late'));
  await Promise.all([list, pair]);
  expect(state.items.value).toHaveLength(1);
  expect(state.controlError.value).toBe('');
});
function loadApi(fetch: ReturnType<typeof vi.fn>) {
  const file = new URL('../src/api/stickerMaterials.ts', import.meta.url);
  expect(existsSync(file)).toBe(true);
  const code = ts.transpileModule(readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} as any };
  new Function('require', 'module', 'exports', code)(
    (id: string) => {
      if (id === '../auth') return { dashboardFetch: fetch };
      throw Error(id);
    },
    module,
    module.exports,
  );
  return module.exports.stickerMaterials;
}
it('所有素材与助手API复用登录/CSRF wrapper，路径与差量body正确且不绑定设备', async () => {
  const fetch = vi.fn(async () => new Response('{}'));
  const api = loadApi(fetch);
  await api.list({ state: 'unassigned', q: '开心', page: 2, page_size: 30 });
  await api.keywords('abc', { add: ['开心'], remove: ['难过'] });
  await api.start('agent');
  await api.pair();
  await api.agents();
  await api.jobs();
  await api.job('j/1');
  await api.cancel('j/1');
  await api.revoke('a/1');
  expect(fetch.mock.calls.map((c: any) => c[0])).toEqual([
    '/api/v1/dashboard/sticker-materials?state=unassigned&q=%E5%BC%80%E5%BF%83&page=2&page_size=30',
    '/api/v1/dashboard/sticker-materials/abc/keywords',
    '/api/v1/dashboard/sticker-import/jobs',
    '/api/v1/dashboard/sticker-import/pairings',
    '/api/v1/dashboard/sticker-import/agents',
    '/api/v1/dashboard/sticker-import/jobs',
    '/api/v1/dashboard/sticker-import/jobs/j%2F1',
    '/api/v1/dashboard/sticker-import/jobs/j%2F1/cancel',
    '/api/v1/dashboard/sticker-import/agents/a%2F1/revoke',
  ]);
  expect(fetch.mock.calls[1][1]).toMatchObject({
    method: 'PATCH',
    body: JSON.stringify({ add: ['开心'], remove: ['难过'] }),
  });
  expect(fetch.mock.calls[2][1].body).toBe(JSON.stringify({ agentId: 'agent' }));
});
it.each(['oops', '{}', '{"error":{}}', 'null'])('非法错误响应仍包含HTTP状态：%s', async (body) => {
  const api = loadApi(vi.fn(async () => new Response(body, { status: 502 })));
  await expect(api.agents()).rejects.toThrow('502');
});
it('创建任务等待期间切换历史任务不被旧创建响应抢回', async () => {
  const { state, api } = await setup({ jobs: vi.fn(async () => ({ jobs: [] })) });
  let resolve!: (v: any) => void;
  api.start.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  const pending = state.startImport();
  await state.selectJob('history');
  resolve({ job: job('new') });
  await pending;
  expect(state.currentJob.value.id).toBe('history');
});
it.each([
  ['agent_limit', '助手数量已达上限'],
  ['pairing_limit', '配对请求过多'],
  ['job_limit', '任务数量已达上限'],
  ['rate_limited', '请求过于频繁'],
])('控制面错误%s转换中文', async (code, message) => {
  const api = loadApi(
    vi.fn(async () => new Response(JSON.stringify({ error: code }), { status: 429 })),
  );
  await expect(api.pair()).rejects.toThrow(message);
});
async function setupParent(stickerLibrary: ReturnType<typeof vi.fn>) {
  const { descriptor } = parse(
    readFileSync(new URL('../src/views/Stickers.vue', import.meta.url), 'utf8'),
  );
  const script = compileScript(descriptor, { id: 'stickers-tabs-test' });
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} as any };
  new Function('require', 'module', 'exports', code)(
    (id: string) => {
      if (id === 'vue') return Vue;
      if (id === '../api') return { api: { stickerLibrary }, scopedAssetUrl: (s: string) => s };
      if (id === '../confirmation') return { useConfirmation: () => async () => true };
      if (id.endsWith('.css') || id === './StickerMaterials.vue') return {};
      throw Error(id);
    },
    module,
    module.exports,
  );
  let state: any;
  const renderer = Vue.createRenderer<any, any>({
    createElement: () => ({}),
    createText: () => ({}),
    createComment: () => ({}),
    setText() {},
    setElementText() {},
    parentNode: () => null,
    nextSibling: () => null,
    patchProp() {},
    insert() {},
    remove() {},
    insertStaticContent: () => [{}, {}],
  });
  const app = renderer.createApp({
    setup() {
      state = module.exports.default.setup({}, { expose() {} });
      return () => null;
    },
  });
  app.mount({});
  mounted.push(app);
  await settle();
  return state;
}
it('默认按词，素材页切回按词重新加载且旧请求不能覆盖新关联', async () => {
  let resolve!: (v: any) => void;
  const latest = { groups: [], systemCount: 0, personalCount: 2, warnings: [] };
  const api = vi
    .fn()
    .mockImplementationOnce(() => new Promise((r) => (resolve = r)))
    .mockResolvedValue(latest);
  const state = await setupParent(api);
  expect(state.activeTab.value).toBe('keywords');
  state.activeTab.value = 'materials';
  await settle();
  state.activeTab.value = 'keywords';
  await settle();
  expect(api).toHaveBeenCalledTimes(2);
  expect(state.library.value.personalCount).toBe(2);
  resolve({ ...latest, personalCount: 0 });
  await settle();
  expect(state.library.value.personalCount).toBe(2);
});
it('轮询请求未完成时不重叠启动下一轮', async () => {
  vi.useFakeTimers();
  const { state, api } = await setup();
  let resolve!: (v: any) => void;
  api.agents.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
  await vi.advanceTimersByTimeAsync(4000);
  const calls = api.agents.mock.calls.length;
  await vi.advanceTimersByTimeAsync(20000);
  expect(api.agents).toHaveBeenCalledTimes(calls);
  resolve({ agents: state.agents.value });
  await settle();
  await vi.advanceTimersByTimeAsync(4000);
  expect(api.agents).toHaveBeenCalledTimes(calls + 1);
});
it('开始失败不伪报成功，撤销助手后禁止再次导入', async () => {
  const { state, api } = await setup({ jobs: vi.fn(async () => ({ jobs: [] })) });
  api.start.mockRejectedValueOnce(Error('请求失败'));
  await state.startImport();
  expect(state.currentJob.value).toBe(null);
  expect(state.controlError.value).toBe('请求失败');
  api.agents.mockResolvedValue({
    agents: [
      {
        id: 'a1',
        name: '电脑',
        lastSeenAt: new Date().toISOString(),
        revokedAt: new Date().toISOString(),
      },
    ],
  });
  await state.revokeAgent();
  expect(api.revoke).toHaveBeenCalledWith('a1');
  expect(state.online.value).toBe(false);
  await state.startImport();
  expect(api.start).toHaveBeenCalledTimes(1);
});
it('创建任务等待期间切换历史任务，旧创建失败不能污染当前错误', async () => {
  const { state, api } = await setup({ jobs: vi.fn(async () => ({ jobs: [] })) });
  let reject!: (error: Error) => void;
  api.start.mockImplementationOnce(() => new Promise((_, fail) => (reject = fail)));
  const pending = state.startImport();
  await state.selectJob('history');
  reject(new Error('旧创建失败'));
  await pending;
  expect(state.currentJob.value.id).toBe('history');
  expect(state.controlError.value).toBe('');
});
it('同任务详情先返回终态，较旧列表后返回不能倒退为运行中', async () => {
  const { state, api } = await setup();
  let resolveDetail!: (value: any) => void;
  let resolveList!: (value: any) => void;
  api.job.mockImplementationOnce(() => new Promise((resolve) => (resolveDetail = resolve)));
  api.jobs.mockImplementationOnce(() => new Promise((resolve) => (resolveList = resolve)));
  const detail = state.selectJob('j1');
  const list = state.refreshImport();
  resolveDetail({ job: job('j1', 'completed') });
  await detail;
  resolveList({ jobs: [job('j1', 'running')] });
  await list;
  expect(state.currentJob.value.status).toBe('completed');
});
it('撤销助手成功后的新状态不能被撤销期间的旧轮询倒写', async () => {
  const { state, api } = await setup();
  const originalAgent = { ...state.agents.value[0] };
  let resolveRevoke!: (value: any) => void;
  let resolveOldAgents!: (value: any) => void;
  let resolveOldJobs!: (value: any) => void;
  api.revoke.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveRevoke = resolve;
      }),
  );
  const revoke = state.revokeAgent();
  await settle();
  api.agents.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOldAgents = resolve;
      }),
  );
  api.jobs.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOldJobs = resolve;
      }),
  );
  const oldPoll = state.refreshImport();
  api.agents.mockResolvedValue({
    agents: [{ ...originalAgent, revokedAt: new Date().toISOString() }],
  });
  api.jobs.mockResolvedValue({ jobs: [job('j1', 'cancelled')] });
  resolveRevoke({ ok: true });
  await revoke;
  expect(state.online.value).toBe(false);
  expect(state.currentJob.value.status).toBe('cancelled');
  resolveOldAgents({ agents: [originalAgent] });
  resolveOldJobs({ jobs: [job('j1', 'running')] });
  await oldPoll;
  expect(state.online.value).toBe(false);
  expect(state.currentJob.value.status).toBe('cancelled');
});

import { readFileSync, existsSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import * as Batch from '../src/api/stickerMaterialBatch';
import { webcrypto } from 'node:crypto';
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
    confirm: vi.fn(async () => true),
    remove: vi.fn(async () => ({ deleted: 1 })),
    stickerLibrary: vi.fn(async () => ({ groups: [
      { keyword: '开心', aliases: ['高兴'] }, { keyword: '早安', aliases: ['早上好'] },
      { keyword: '早点休息', aliases: ['早睡'] }, { keyword: '猫', aliases: [] }, { keyword: '狗', aliases: [] },
    ] })),
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
      if (id === '../api/stickerMaterialBatch') return Batch;
      if (id === './content-library.css') return {};
      if (id === '../api/stickerMaterials') return { stickerMaterials: api };
      if (id === '../api') return { scopedAssetUrl: (url: string) => url, api };
      if (id === '../confirmation') return { useConfirmation: () => api.confirm };
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
  expect(api.list).toHaveBeenLastCalledWith({ state: 'unassigned', q: '', page: 2, page_size: 30 });
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
  expect(api.keywords).toHaveBeenCalledWith(material.sha256, { add: [], remove: ['高兴'], requireExistingGroups: true });
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
      if (id === '../api/stickerMaterialBatch') return Batch;
      if (id === './content-library.css') return {};
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

it('素材库具有独立菜单、路由和无设备访问资格，配对默认折叠', () => {
 const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8');
 const main = readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8');
 expect(app).toContain("path: '/sticker-materials', label: '表情素材库'");
 expect(app).toMatch(/route.path === '\/sticker-materials'/);
 expect(main).toContain("path: '/sticker-materials', component: StickerMaterials");
 const component = readFileSync(new URL('../src/views/StickerMaterials.vue', import.meta.url), 'utf8');
 expect(component).toMatch(/<details class="library-panel import-panel">/);
 expect(component).toContain('multiple');
});
it('批量选择后显示已有关键词和新增未分配，成功后刷新图库并通知推荐页', async () => {
 vi.stubGlobal('crypto', webcrypto);
 const apiMatch = vi.fn(async (hashes: string[]) => ({ items: hashes.map(sha256 => ({ sha256, status: 'existing', material: { ...material, sha256 } })) }));
 const { state, api, emit } = await setup({ match: apiMatch, upload: vi.fn() });
 const input = { files: [{name:'a.gif',size:3,arrayBuffer: async()=>new Uint8Array([1,2,3]).buffer}],value:'chosen' };
 await state.chooseBatch({ target: input });
 expect(state.batchRows.value[0].status).toBe('existing');
 expect(state.batchRows.value[0].material.keywords).toEqual(['开心','高兴']);
 expect(input.value).toBe(''); expect(api.upload).not.toHaveBeenCalled(); expect(emit).toHaveBeenCalledWith('changed');
 vi.unstubAllGlobals();
});
it('离开素材页后匹配响应不得触发上传、刷新和推荐页通知', async () => {
 vi.stubGlobal('crypto', webcrypto);
 let resolve!: (value: any) => void;
 const apiMatch=vi.fn(()=>new Promise(r=>resolve=r));
 const { state, api, emit, app }=await setup({match:apiMatch,upload:vi.fn()});
 const work=state.chooseBatch({target:{files:[{name:'a.gif',size:3,arrayBuffer:async()=>new Uint8Array([1,2,3]).buffer}],value:'chosen'}});
 for(let i=0;i<20 && !apiMatch.mock.calls.length;i++) await new Promise(r=>setTimeout(r,5));
 expect(apiMatch).toHaveBeenCalledTimes(1);
 const before=api.list.mock.calls.length, emitted=emit.mock.calls.length;
 app.unmount();
 const sha256=state.batchRows.value[0].sha256;
 resolve({items:[{sha256,status:'missing'}]}); await work;
 expect(api.upload).not.toHaveBeenCalled(); expect(api.list).toHaveBeenCalledTimes(before); expect(emit).toHaveBeenCalledTimes(emitted);
 vi.unstubAllGlobals();
});
it('批量API通过 dashboardFetch 发送 SHA 列表和原始字节，并限制请求等待', async () => {
 const fetch = vi.fn(async () => new Response('{}'));
 const api=loadApi(fetch), file = {name:'原图.gif'} as File, sha='a'.repeat(64);
 await api.match([sha]); await api.upload(file,sha);
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/sticker-materials/match');
 expect(JSON.parse((fetch.mock.calls[0] as any)[1].body)).toEqual({sha256s:[sha]});
 expect((fetch.mock.calls[0] as any)[1].signal).toBeInstanceOf(AbortSignal);
 const [url,options]=fetch.mock.calls[1] as any;
 expect(url).toContain('filename=%E5%8E%9F%E5%9B%BE.gif'); expect(url).toContain(`sha256=${sha}`);
 expect(options.body).toBe(file); expect(options.headers['Content-Type']).toBe('application/octet-stream'); expect(options.signal).toBeInstanceOf(AbortSignal);
});
it('批量运行期间禁止关键词编辑，避免同批缓存回显旧标签', async () => {
 const { state, api } = await setup();
 state.batchBusy.value = true;
 await state.changeKeywords(material, ['新词'], []);
 expect(api.keywords).not.toHaveBeenCalled();
 expect(state.cardBusy.value[material.sha256]).toBeUndefined();
});
it('关键词编辑尚未结束时不允许开始、重试或选择新批次', async () => {
 const { state } = await setup();
 state.cardBusy.value[material.sha256] = true;
 const original = [{ file: {name:'待重试.gif',size:3}, status:'failed' }];
 state.batchRows.value = original;
 await state.runBatch();
 expect(state.batchBusy.value).toBe(false);
 expect(state.batchRows.value[0].status).toBe('failed');
 const input={ files:[{name:'新图.gif',size:3}], value:'chosen' };
 await state.chooseBatch({target:input});
 expect(state.batchRows.value[0].file.name).toBe('待重试.gif');
 expect(state.batchBusy.value).toBe(false);
});
it('批量入口和关键词表单双向禁用，页面筛选仍可用', () => {
 const source=readFileSync(new URL('../src/views/StickerMaterials.vue',import.meta.url),'utf8');
 expect(source).toContain(':disabled="deleteBusy || batchBusy || keywordBusy"');
 expect(source).toContain(':disabled="deleteBusy || keywordBusy" @click="runBatch"');
 expect(source).toContain(':disabled="deleteBusy || batchBusy || cardBusy[m.sha256] || loading"');
 expect(source).toContain(':disabled="deleteBusy || batchBusy || cardBusy[m.sha256] || groupLoading || !!groupError"');
});
it('独立素材页自带共享样式作用域，文件选择只显示中文入口', () => {
 const source=readFileSync(new URL('../src/views/StickerMaterials.vue',import.meta.url),'utf8');
 expect(source).toContain('class="content-library materials-page"');
 expect(source).toMatch(/<input ref="batchInput"[^>]*\bhidden\b/);
 expect(source).toContain('aria-label="选择批量上传图片"');
 expect(source).toContain('选择图片批量上传</button>');
});

it('默认未分配，搜索组名或别名，仅选择已有主组关联', async () => {
 const { state, api } = await setup();
 expect(state.filter.value).toBe('unassigned');
 expect(api.list).toHaveBeenCalledWith({state:'unassigned',q:'',page:1,page_size:30});
 state.drafts.value[material.sha256] = '早';
 expect(state.groupCandidates(material).map((g: any) => g.keyword)).toEqual(['早安','早点休息']);
 state.drafts.value[material.sha256] = '早上好';
 expect(state.groupCandidates(material).map((g: any) => g.keyword)).toEqual(['早安']);
 await state.selectGroup(material, '早安');
 expect(api.keywords).toHaveBeenCalledWith(material.sha256, {add:['早安'],remove:[],requireExistingGroups:true});
 expect(api.stickerLibrary).toHaveBeenCalledTimes(2);
});
it('同组历史标签归一去重，已关联组及任意新词不能再次添加', async () => {
 const {state,api} = await setup();
 expect(state.materialGroups(material)).toEqual(['开心']);
 await state.selectGroup(material,'开心');
 await state.selectGroup(material,'不存在');
 expect(api.keywords).not.toHaveBeenCalled();
 state.drafts.value[material.sha256] = '不存在';
 expect(state.groupCandidates(material)).toEqual([]);
});
it('关键词组加载失败禁止分配，可重试；卸载后忽略组响应', async () => {
 const {state,api,app} = await setup({stickerLibrary:vi.fn().mockRejectedValueOnce(Error('组加载失败')).mockResolvedValue({groups:[{keyword:'早安',aliases:[]}]})});
 expect(state.groupError.value).toBe('组加载失败');
 await state.selectGroup(material,'早安');
 expect(api.keywords).not.toHaveBeenCalled();
 await state.loadGroups();
 expect(state.groupError.value).toBe('');
 expect(state.groups.value[0].keyword).toBe('早安');
 let resolve!: (v:any)=>void;
 api.stickerLibrary.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 const pending=state.loadGroups(); app.unmount(); resolve({groups:[]}); await pending;
 expect(state.groups.value).toHaveLength(1);
});

it('推荐词仍在加载或批量上传中不能选择，选中失败不伪造关联', async () => {
 const {state,api} = await setup();
 let resolve!: (v:any)=>void;
 api.stickerLibrary.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 const pending=state.loadGroups();
 await state.selectGroup(material,'早安');
 expect(api.keywords).not.toHaveBeenCalled();
 resolve({groups:[{keyword:'早安',aliases:[]}]}); await pending;
 state.batchBusy.value=true;
 await state.selectGroup(material,'早安');
 expect(api.keywords).not.toHaveBeenCalled();
 state.batchBusy.value=false;
 api.keywords.mockRejectedValueOnce(Error('推荐词已删除，请刷新'));
 await state.selectGroup(material,'早安');
 expect(state.materialGroups(material)).not.toContain('早安');
 expect(state.cardErrors.value[material.sha256]).toContain('推荐词已删除');
});
it('分配后沿用未分配筛选刷新，卡片移出；刷新同时更新候选组', async () => {
 const {state,api} = await setup();
 api.list.mockResolvedValue({items:[],total:0,page:1,pageSize:30,warnings:[]});
 await state.selectGroup(material,'早安');
 expect(api.list).toHaveBeenLastCalledWith({state:'unassigned',q:'',page:1,page_size:30});
 expect(state.items.value).toEqual([]);
 await state.refreshMaterials();
 expect(api.stickerLibrary).toHaveBeenCalledTimes(3);
});

it('别名删除后的历史图片按推荐库实际资产归组，支持一图多组并禁重', async () => {
 const legacy = {...material, keywords:['高兴','晚安']};
 const groups = [
  {keyword:'开心',aliases:['开心'],assets:[{id:1,source:'personal',keywords:['高兴']}]},
  {keyword:'晚安',aliases:['晚安'],assets:[{id:2,source:'personal',keywords:['晚安']}]},
 ];
 const {state,api}=await setup({stickerLibrary:vi.fn(async()=>({groups}))});
 expect(state.materialGroups(legacy)).toEqual(['开心','晚安']);
 await state.selectGroup(legacy,'开心');
 expect(api.keywords).not.toHaveBeenCalled();
});
it('移除组后旧资产快照不复活标签，编辑成功刷新组列表并拒绝编辑前晚到响应', async () => {
 const legacy={...material,keywords:['高兴','晚安']};
 const oldGroups=[{keyword:'开心',aliases:['开心'],assets:[{id:1,source:'personal',keywords:['高兴']}]},
  {keyword:'晚安',aliases:['晚安'],assets:[{id:2,source:'personal',keywords:['晚安']}]}];
 const next={...legacy,keywords:['晚安']};
 const {state,api}=await setup({stickerLibrary:vi.fn(async()=>({groups:oldGroups}))});
 expect(state.materialGroups(next)).toEqual(['晚安']);
 let resolve!: (v:any)=>void;
 api.stickerLibrary.mockImplementationOnce(()=>new Promise(r=>resolve=r));
 const oldRefresh=state.loadGroups();
 const nextGroups=[{keyword:'开心',aliases:['开心'],assets:[]},oldGroups[1]];
 api.stickerLibrary.mockResolvedValue({groups:nextGroups});
 api.keywords.mockResolvedValue({material:next});
 api.list.mockResolvedValue({items:[next],total:1,page:1,pageSize:30,warnings:[]});
 await state.changeKeywords(legacy,[],['开心']);
 expect(api.stickerLibrary).toHaveBeenCalledTimes(3);
 resolve({groups:oldGroups}); await oldRefresh;
 expect(state.groups.value).toEqual(nextGroups);
 expect(state.materialGroups(state.items.value[0])).toEqual(['晚安']);
});


it('只选当前页，全不选及翻页/筛选清空选择', async () => {
 const {state}=await setup();
 state.selectAllMaterials(); expect([...state.selectedMaterials.value]).toEqual([material.sha256]);
 state.clearMaterialSelection(); expect(state.selectedMaterials.value.size).toBe(0);
 state.selectAllMaterials(); await state.loadMaterials(2); expect(state.selectedMaterials.value.size).toBe(0);
 state.selectAllMaterials(); await state.applyFilter(); expect(state.selectedMaterials.value.size).toBe(0);
});
it('确认删除当前页素材后刷新列表和分组，取消则不删除', async () => {
 const {state,api,emit}=await setup();
 state.selectAllMaterials(); api.confirm.mockResolvedValueOnce(false);
 await state.deleteSelectedMaterials(); expect(api.remove).not.toHaveBeenCalled();
 expect(state.selectedMaterials.value.size).toBe(1);
 await state.deleteSelectedMaterials();
 expect(api.confirm).toHaveBeenLastCalledWith(expect.stringMatching(/1 张.*所有设备/),expect.anything());
 expect(api.remove).toHaveBeenCalledWith([material.sha256]);
 expect(state.selectedMaterials.value.size).toBe(0); expect(emit).toHaveBeenCalledWith('changed');
 expect(api.stickerLibrary).toHaveBeenCalledTimes(2); expect(api.list).toHaveBeenCalledTimes(2);
});
it('删除失败保留选择并提示，确认期间阻止重复提交和选择变化', async () => {
 let finish!:(accepted:boolean)=>void;
 const {state,api}=await setup({confirm:vi.fn(()=>new Promise<boolean>(resolve=>{finish=resolve;})),remove:vi.fn(async()=>{throw Error('删除未确认');})});
 state.selectAllMaterials(); const pending=state.deleteSelectedMaterials();
 expect(state.deleteBusy.value).toBe(true);
 state.clearMaterialSelection(); expect(state.selectedMaterials.value.size).toBe(1);
 await state.deleteSelectedMaterials(); expect(api.confirm).toHaveBeenCalledTimes(1);
 finish(true); await pending;
 expect(state.deleteError.value).toBe('删除未确认'); expect(state.selectedMaterials.value.size).toBe(1);
 expect(state.deleteBusy.value).toBe(false);
});
it('空选择、加载中及离开页面后的确认不发送删除', async () => {
 let finish!:(accepted:boolean)=>void;
 const {state,api,app}=await setup({confirm:vi.fn(()=>new Promise<boolean>(resolve=>{finish=resolve;}))});
 await state.deleteSelectedMaterials(); expect(api.confirm).not.toHaveBeenCalled();
 state.selectAllMaterials(); state.loading.value=true; await state.deleteSelectedMaterials(); expect(api.confirm).not.toHaveBeenCalled();
 state.loading.value=false; const pending=state.deleteSelectedMaterials(); app.unmount(); mounted.splice(mounted.indexOf(app),1);
 finish(true); await pending; expect(api.remove).not.toHaveBeenCalled();
});
it('模板提供当前页选择、全不选和删除入口',()=>{
 const text=readFileSync(new URL('../src/views/StickerMaterials.vue',import.meta.url),'utf8');
 for(const label of ['全选当前页','全不选','删除选中','type="checkbox"','toggleMaterialSelection']) expect(text).toContain(label);
});
it('单选可切换且全选不包括另一页，删除空页自动回到有效页', async () => {
 const second={...material,sha256:'b'.repeat(64),ids:[3]};
 let deleted=false;
 const {state,api}=await setup({list:vi.fn(async(q:any)=>({items:deleted?[]:[q.page===1?material:second],total:deleted?0:31,page:q.page,warnings:[]})),remove:vi.fn(async()=>{deleted=true;return{deleted:1};})});
 state.toggleMaterialSelection(material.sha256); expect(state.selectedMaterials.value.has(material.sha256)).toBe(true);
 state.toggleMaterialSelection(material.sha256); expect(state.selectedMaterials.value.size).toBe(0);
 state.toggleMaterialSelection(second.sha256); expect(state.selectedMaterials.value.size).toBe(0);
 state.selectAllMaterials(); await state.loadMaterials(2); state.selectAllMaterials();
 expect([...state.selectedMaterials.value]).toEqual([second.sha256]);
 await state.deleteSelectedMaterials(); expect(api.remove).toHaveBeenCalledWith([second.sha256]);
 expect(state.page.value).toBe(1); expect(state.total.value).toBe(0); expect(state.items.value).toEqual([]);
});

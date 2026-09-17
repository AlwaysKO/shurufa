import { readFileSync } from 'node:fs';
import { expect, it, vi, afterEach } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

type Node = { tag: string; text: string; children: Node[]; parent: Node | null; props: Record<string, any>; value: string; options: Node[]; files?: File[]; addEventListener: () => void; removeEventListener: () => void; click: () => void; tagName: string; getRootNode: () => object };
const mounted: Vue.App[] = [];
afterEach(() => { mounted.splice(0).forEach(app => app.unmount()); vi.unstubAllGlobals(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick(); } }
async function mount(name: string, api: Record<string, any>) {
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true });
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const node = (tag = '', text = ''): Node => ({ tag, text, children: [], parent: null, props: {}, value: '', get options() { return this.children.filter(n => n.tag === 'option'); }, addEventListener() {}, removeEventListener() {}, click() {}, getRootNode: () => ({ activeElement: null }), tagName: tag.toUpperCase() });
  function insert(n: Node, p: Node, anchor?: Node | null) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = p; const i = anchor ? p.children.indexOf(anchor) : -1; if (i < 0) p.children.push(n); else p.children.splice(i, 0, n); }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: tag => node(tag), createText: text => node('', text), createComment: text => node('', text),
    setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; n.children = []; },
    parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp: (n, key, _prev, value) => { n.props[key] = value; }, insert,
    remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null; },
    insertStaticContent(text, p, anchor) { const n = node('static', text); insert(n, p, anchor); return [n, n]; },
  });
  vi.stubGlobal('document', { activeElement: null });
  vi.stubGlobal('Document', class {}); vi.stubGlobal('ShadowRoot', class {});
  const presets = compiled.content.includes('../data/phrasePresets') ? await import('../src/data/phrasePresets') : {};
  const module = { exports: {} as { default: Vue.Component } };
  const require = (id: string) => {
    if (id === 'vue') return Vue;
    if (id === 'vue-router') return { useRoute: () => ({ query: {} }) };
    if (id === '../api') return { api, appName: (s: string) => s, deviceDetailLines: () => [], deviceLabel: () => '', eventTypeName: (s: string) => s, networkName: (s: string) => s };
    if (id === '../data/phrasePresets') return presets;
    if (id.endsWith('.css')) return {};
    throw new Error(`Unexpected import: ${id}`);
  };
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root'); const app = renderer.createApp(module.exports.default); app.mount(root); mounted.push(app); await settle();
  const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
  const find = (id: string) => all(root).find(n => n.props['data-testid'] === id);
  return { root, find, all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
}

it('默认保留原始操作并显示编辑前后，区分空输入框和没有快照', async () => {
  const events = vi.fn().mockResolvedValue({ total: 2, items: [
    { id: '1', device_id: 'device-1', occurred_at: '2026-09-16', event_type: 'delete', text: '八', text_before: '八', text_after: '' },
    { id: '2', device_id: 'device-1', occurred_at: '2026-09-16', event_type: 'external_delete', text: '', text_before: null, text_after: '剩余' },
  ] });
  const view = await mount('Activity', { events, devices: vi.fn().mockResolvedValue({ devices: [] }) });
  expect(view.all().filter(n => n.tag === 'details')).toHaveLength(2);
  expect(view.text()).toContain('编辑前');
  expect(view.text()).toContain('编辑后');
  expect(view.text()).toContain('（空输入框）');
  expect(view.text()).toContain('（未采集）');
  expect(view.text()).toContain('删除内容未采集');
  expect(events.mock.calls[0][0].all).toBe(false);
  const deletion = view.all().find(n => n.tag === 'button' && n.text === '删除');
  expect(deletion).toBeDefined();
  deletion!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0].type).toBe('delete');
});

it('旧记录没有快照时不伪造完整句子或编辑过程', async () => {
  const view = await mount('Activity', { devices: async () => ({ devices: [] }), events: async () => ({ total: 1, items: [
    { id: 'old', device_id: 'device-1', occurred_at: '2026-09-16', event_type: 'commit', text: '片段', text_before: null, text_after: null },
  ] }) });
  expect(view.all().filter(n => n.tag === 'details')).toHaveLength(0);
  expect(view.text()).toContain('片段');
});

const editHistory = [
  { id: 'a', occurred_at: '2026-09-16', event_type: 'commit', text: '晚上八点见', text_before: '', text_after: '晚上八点见', sequence_no: 1 },
  { id: 'b', occurred_at: '2026-09-16', event_type: 'delete', text: '八', text_before: '晚上八点见', text_after: '晚上点见', sequence_no: 2 },
  { id: 'c', occurred_at: '2026-09-16', event_type: 'commit', text: '九', text_before: '晚上点见', text_after: '晚上九点见', sequence_no: 3 },
];
const editGroup = { ...editHistory[2], device_id: 'device-1', edit_count: 3, edit_complete: true, edit_events: editHistory };

it('默认按整段请求并显示完整末态，展开所有原始操作而非拼接片段', async () => {
  const events = vi.fn().mockResolvedValue({ total: 1, items: [editGroup] });
  const view = await mount('Activity', { events, devices: async () => ({ devices: [] }) });
  expect(events.mock.calls[0][0].grouped).toBe(true);
  expect(view.all().find(n => n.props.class === 'event-text')?.text).toBe('晚上九点见');
  expect(view.text()).toContain('完整快照');
  expect(view.text()).toContain('不代表消息已发送');
  expect(view.text()).toContain('3 次操作');
  expect(view.all().filter(n => n.props.class === 'edit-operation')).toHaveLength(3);
  expect(view.text()).toContain('晚上八点见');
  expect(view.text()).toContain('晚上点见');
  expect(view.text()).not.toContain('晚上八点见八九');
});

it('整段末态为空时显示已清空，但历史仍保留删除原文', async () => {
  const view = await mount('Activity', { devices: async () => ({ devices: [] }), events: async () => ({ total: 1, items: [
    { ...editGroup, text: '晚上九点见', text_after: '', edit_count: 4, edit_events: [...editHistory, { id: 'd', occurred_at: '2026-09-16', event_type: 'delete', text: '晚上九点见', text_before: '晚上九点见', text_after: '' }] },
  ] }) });
  expect(view.all().find(n => n.props.class === 'event-text')?.text).toBe('（已清空输入框）');
  expect(view.all().filter(n => n.props.class === 'edit-operation')).toHaveLength(4);
  expect(view.text()).toContain('晚上九点见');
});

it('不完整组与旧记录只展示片段且明确标记，不冒充整句', async () => {
  const oldRow = { id: 'old', device_id: 'device-1', occurred_at: '2026-09-16', event_type: 'commit', text: '旧片段' };
  const view = await mount('Activity', { devices: async () => ({ devices: [] }), events: async () => ({ total: 2, items: [
    { ...editGroup, edit_complete: false, text_after: '不可确信的全句' },
    { ...oldRow, edit_count: 1, edit_complete: false, edit_events: [oldRow] },
  ] }) });
  expect(view.all().filter(n => n.props.class === 'event-text').map(n => n.text)).toEqual(['九', '旧片段']);
  expect(view.all().filter(n => n.text === '片段 / 缺少完整编辑证据')).toHaveLength(2);
  expect(view.all().filter(n => n.props.class === 'edit-operation')).toHaveLength(4);
});

it('模式切换和底层事件开关重置分页，底层事件只能使用原始模式', async () => {
  const events = vi.fn().mockResolvedValue({ total: 45, items: [editGroup] });
  const view = await mount('Activity', { events, devices: async () => ({ devices: [] }) });
  view.all().find(n => n.tag === 'button' && n.text === '下一页')!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0].page).toBe(2);
  view.find('mode-raw')!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ grouped: false, page: 1 });
  view.find('mode-grouped')!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0].grouped).toBe(true);
  const checkbox = view.find('show-all')!;
  checkbox.props['onUpdate:modelValue'](true);
  checkbox.props.onChange(); await settle();
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ all: true, grouped: false, page: 1 });
  expect(view.find('mode-grouped')!.props.disabled).toBe(true);
});

it('快速切换时较早的整段响应不能覆盖当前原始模式', async () => {
  let resolveGrouped!: (value: unknown) => void;
  const events = vi.fn()
    .mockImplementationOnce(() => new Promise(resolve => { resolveGrouped = resolve; }))
    .mockResolvedValueOnce({ total: 1, items: [{ ...editHistory[0], device_id: 'device-1', text: '当前原始记录' }] });
  const view = await mount('Activity', { events, devices: async () => ({ devices: [] }) });
  view.find('mode-raw')!.props.onClick(); await settle();
  resolveGrouped({ total: 9, items: [editGroup] }); await settle();
  expect(view.text()).toContain('当前原始记录');
  expect(view.text()).not.toContain('晚上九点见');
});

it('每行删除前确认整段摘要和原始记录数量，取消不发请求', async () => {
  const confirm = vi.fn().mockReturnValue(false); vi.stubGlobal('confirm', confirm);
  const deleteActivity = vi.fn();
  const view = await mount('Activity', { deleteActivity, devices: async () => ({ devices: [] }), events: async () => ({ total: 1, items: [editGroup] }) });
  const button = view.find('delete-activity-c'); expect(button).toBeDefined();
  button!.props.onClick(); await settle();
  expect(confirm.mock.calls[0][0]).toContain('晚上九点见');
  expect(confirm.mock.calls[0][0]).toContain('3 条');
  expect(confirm.mock.calls[0][0]).toContain('永久删除');
  expect(deleteActivity).not.toHaveBeenCalled(); expect(view.text()).toContain('晚上九点见');
});

it('确认整段删除发送准确ID列表，成功后刷新且保持筛选', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  const events = vi.fn().mockResolvedValueOnce({ total: 1, items: [editGroup] }).mockResolvedValue({ total: 0, items: [] });
  const deleteActivity = vi.fn().mockResolvedValue({ deleted: 3 });
  const view = await mount('Activity', { events, deleteActivity, devices: async () => ({ devices: [] }) });
  expect(view.find('delete-activity-c')).toBeDefined(); view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(deleteActivity).toHaveBeenCalledWith('c', { confirm: 'DELETE', mode: 'group', event_ids: ['a', 'b', 'c'] });
  expect(events).toHaveBeenCalledTimes(2);
  expect(events.mock.calls[1][0]).toEqual(events.mock.calls[0][0]);
  expect(view.text()).toContain('已删除 3 条'); expect(view.text()).not.toContain('晚上九点见');
});

it('原始模式只确认并发送所点单条ID', async () => {
  const confirm = vi.fn().mockReturnValue(true); vi.stubGlobal('confirm', confirm);
  const deleteActivity = vi.fn().mockResolvedValue({ deleted: 1 });
  const view = await mount('Activity', { deleteActivity, devices: async () => ({ devices: [] }), events: async () => ({ total: 1, items: [editGroup] }) });
  view.find('mode-raw')!.props.onClick(); await settle();
  expect(view.find('delete-activity-c')).toBeDefined(); view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(deleteActivity).toHaveBeenCalledWith('c', { confirm: 'DELETE', mode: 'single', event_ids: ['c'] });
  expect(confirm.mock.calls[0][0]).toContain('1 条');
});

it('删除失败保留列表和原文，展示错误而非假装已删除', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  const deleteActivity = vi.fn().mockRejectedValue(Error('记录已变化，请刷新后重新确认'));
  const events = vi.fn().mockResolvedValue({ total: 1, items: [editGroup] });
  const view = await mount('Activity', { events, deleteActivity, devices: async () => ({ devices: [] }) });
  expect(view.find('delete-activity-c')).toBeDefined(); view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(view.text()).toContain('删除失败'); expect(view.text()).toContain('记录已变化');
  expect(view.text()).toContain('晚上九点见'); expect(events).toHaveBeenCalledTimes(1);
  expect(view.find('delete-activity-c')?.props.disabled).toBe(false);
});

it('请求进行中防止重复删除，加载中的旧行不能被删除', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  let resolveDelete!: (value: unknown) => void;
  const deleteActivity = vi.fn(() => new Promise(resolve => { resolveDelete = resolve; }));
  const view = await mount('Activity', { deleteActivity, devices: async () => ({ devices: [] }), events: async () => ({ total: 1, items: [editGroup] }) });
  expect(view.find('delete-activity-c')).toBeDefined();
  view.find('delete-activity-c')!.props.onClick(); view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(deleteActivity).toHaveBeenCalledTimes(1); expect(view.find('delete-activity-c')?.props.disabled).toBe(true);
  resolveDelete({ deleted: 3 }); await settle();
});

it('删除末页最后一行后回到有效页，不留空白分页', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  const events = vi.fn()
    .mockResolvedValueOnce({ total: 21, items: [editGroup] })
    .mockResolvedValueOnce({ total: 21, items: [editGroup] })
    .mockResolvedValueOnce({ total: 20, items: [] })
    .mockResolvedValue({ total: 20, items: [{ ...editGroup, id: 'remaining' }] });
  const view = await mount('Activity', { events, deleteActivity: async () => ({ deleted: 3 }), devices: async () => ({ devices: [] }) });
  view.all().find(n => n.tag === 'button' && n.text === '下一页')!.props.onClick(); await settle();
  expect(view.find('delete-activity-c')).toBeDefined(); view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(events.mock.calls.map(call => call[0].page)).toEqual([1, 2, 2, 1]);
  expect(view.find('delete-activity-remaining')).toBeDefined();
});

it('切换模式正在加载时，旧行不可发起删除', async () => {
  const confirm = vi.fn().mockReturnValue(true); vi.stubGlobal('confirm', confirm);
  let respond!: (value: unknown) => void;
  const events = vi.fn().mockResolvedValueOnce({ total: 1, items: [editGroup] })
    .mockImplementationOnce(() => new Promise(resolve => { respond = resolve; }));
  const deleteActivity = vi.fn();
  const view = await mount('Activity', { events, deleteActivity, devices: async () => ({ devices: [] }) });
  view.find('mode-raw')!.props.onClick(); await settle();
  expect(view.find('delete-activity-c')?.props.disabled).toBe(true);
  view.find('delete-activity-c')!.props.onClick(); await settle();
  expect(confirm).not.toHaveBeenCalled(); expect(deleteActivity).not.toHaveBeenCalled();
  respond({ total: 1, items: [editGroup] }); await settle();
});

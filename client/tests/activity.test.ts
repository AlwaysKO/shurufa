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
    if (id === '../confirmation') return { useConfirmation: () => async (message: string) => Boolean(await globalThis.confirm?.(message)) };
    if (id === 'vue-router') return { useRoute: () => ({ query: {} }) };
    if (id === '../api') return { api, currentUserId: Vue.ref('user-a'), appName: (s: string) => s, deviceDetailLines: () => [], deviceLabel: () => '', eventTypeName: (s: string) => s, networkName: (s: string) => s };
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
  expect(view.find('delete-activity-c')).toBeUndefined();
  expect(view.text()).not.toContain('晚上九点见');
  await settle();
  expect(confirm).not.toHaveBeenCalled(); expect(deleteActivity).not.toHaveBeenCalled();
  respond({ total: 1, items: [editGroup] }); await settle();
});

it('行为明细每页20条，整段及原始模式翻页均固定请求20', async () => {
  const events = vi.fn().mockResolvedValue({ total: 41, items: [] });
  const view = await mount('Activity', { events, devices: async () => ({ devices: [] }) });
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ page: 1, page_size: 20 });
  expect(view.text()).toContain('每页 20');
  view.all().find(n => n.tag === 'button' && n.text === '下一页')!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ page: 2, page_size: 20 });
  view.find('mode-raw')!.props.onClick(); await settle();
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ page: 1, page_size: 20, grouped: false });
});

it('批量清理保留DELETE校验，必须经自定义确认，取消不提交且使用确认时的筛选快照', async () => {
  const cleanup = vi.fn().mockResolvedValue({ scope: 'events', deleted: { events: 1 } });
  const confirm = vi.fn().mockReturnValue(false); vi.stubGlobal('confirm', confirm);
  const view = await mount('DataManage', { cleanup, collectorSetting: async () => ({ collector_base_url: '' }) });
  const action = view.all().find(n => n.tag === 'button' && n.text === '确认清理')!;
  await action.props.onClick(); await settle();
  expect(cleanup).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled();
  const input = view.all().find(n => n.tag === 'input' && n.props.placeholder?.includes('DELETE'))!;
  input.props['onUpdate:modelValue']('DELETE'); await settle();
  await action.props.onClick(); await settle();
  expect(confirm).toHaveBeenCalled(); expect(cleanup).not.toHaveBeenCalled();
  expect(confirm.mock.calls[0][0]).toContain('全部时间');
  expect(confirm.mock.calls[0][0]).toContain('全部应用');
  confirm.mockReturnValue(true);
  await action.props.onClick(); await settle();
  expect(cleanup).toHaveBeenCalledTimes(1);
  expect(cleanup).toHaveBeenCalledWith({ confirm: 'DELETE', scope: 'events', from: undefined, to: undefined, package_name: undefined });
});


it('底层事件是临时原始视图，取消后恢复此前整段或原始模式', async () => {
  const events = vi.fn().mockResolvedValue({ total: 0, items: [] });
  const view = await mount('Activity', { events, devices: async () => ({ devices: [] }) });
  const toggle = async (value: boolean) => { const checkbox = view.find('show-all')!; checkbox.props['onUpdate:modelValue'](value); checkbox.props.onChange(); await settle(); };
  await toggle(true); expect(events.mock.calls.at(-1)![0]).toMatchObject({ all: true, grouped: false });
  await toggle(false); expect(events.mock.calls.at(-1)![0]).toMatchObject({ all: false, grouped: true });
  view.find('mode-raw')!.props.onClick(); await settle();
  await toggle(true); await toggle(false);
  expect(events.mock.calls.at(-1)![0]).toMatchObject({ all: false, grouped: false });
});
const batchRows = () => [editGroup, { ...editGroup, id: 'other', text_after: '第二段', edit_count: 1, edit_events: [{ ...editHistory[0], id: 'other' }] }];
it('全选仅选择本页并显示数量，全不选清空且不发删除请求', async () => {
  const deleteActivities = vi.fn();
  const view = await mount('Activity', { deleteActivities, events: async () => ({ total: 45, items: batchRows() }), devices: async () => ({ devices: [] }) });
  expect(view.find('delete-selected')!.props.disabled).toBe(true);
  view.find('select-page')!.props.onClick(); await settle();
  expect(view.find('selection-count')!.text).toContain('2');
  expect(view.find('delete-selected')!.props.disabled).toBe(false);
  view.find('clear-selection')!.props.onClick(); await settle();
  expect(view.find('selection-count')!.text).toContain('0'); expect(view.find('delete-selected')!.props.disabled).toBe(true);
  expect(deleteActivities).not.toHaveBeenCalled();
});
it('整段批量确认显示行数和原始条数，只发送选中行的完整快照', async () => {
  const confirm = vi.fn().mockReturnValue(true); vi.stubGlobal('confirm', confirm);
  const deleteActivities = vi.fn().mockResolvedValue({ deleted: 4 });
  const events = vi.fn().mockResolvedValueOnce({ total: 2, items: batchRows() }).mockResolvedValue({ total: 0, items: [] });
  const view = await mount('Activity', { deleteActivities, events, devices: async () => ({ devices: [] }) });
  view.find('select-page')!.props.onClick(); await settle(); view.find('delete-selected')!.props.onClick(); await settle();
  expect(confirm.mock.calls[0][0]).toContain('2 组'); expect(confirm.mock.calls[0][0]).toContain('4 条');
  expect(deleteActivities).toHaveBeenCalledWith({ confirm: 'DELETE', mode: 'group', records: [
    { id: 'c', event_ids: ['a','b','c'] }, { id: 'other', event_ids: ['other'] },
  ] });
  expect(events).toHaveBeenCalledTimes(2); expect(view.text()).toContain('已删除 4 条');
});
it('原始模式只删除勾选的行，取消确认保留选择，失败也不清空列表或选择', async () => {
  const confirm = vi.fn().mockReturnValue(false); vi.stubGlobal('confirm', confirm);
  const deleteActivities = vi.fn().mockRejectedValue(Error('记录已变化'));
  const view = await mount('Activity', { deleteActivities, events: async () => ({ total: 2, items: batchRows() }), devices: async () => ({ devices: [] }) });
  view.find('mode-raw')!.props.onClick(); await settle();
  view.find('select-activity-c')!.props['onUpdate:modelValue'](['c']); await settle();
  view.find('delete-selected')!.props.onClick(); await settle(); expect(deleteActivities).not.toHaveBeenCalled();
  expect(view.find('selection-count')!.text).toContain('1');
  confirm.mockReturnValue(true); view.find('delete-selected')!.props.onClick(); await settle();
  expect(deleteActivities).toHaveBeenCalledWith({ confirm: 'DELETE', mode: 'single', records: [{ id: 'c', event_ids: ['c'] }] });
  expect(view.find('selection-count')!.text).toContain('1'); expect(view.text()).toContain('记录已变化'); expect(view.find('select-activity-c')).toBeDefined();
});
it('翻页、切换模式、底层事件和修改筛选均清空选择，加载时旧行不可見', async () => {
  const view = await mount('Activity', { events: async () => ({ total: 45, items: batchRows() }), devices: async () => ({ devices: [] }) });
  const actions = [
    () => view.all().find(n => n.tag === 'button' && n.text === '下一页')!.props.onClick(),
    () => view.find('mode-raw')!.props.onClick(),
    () => { const c = view.find('show-all')!; c.props['onUpdate:modelValue'](true); c.props.onChange(); },
    () => view.all().find(n => n.tag === 'input' && n.props.type === 'search')!.props['onUpdate:modelValue']('新筛选'),
  ];
  for (const action of actions) {
    view.find('select-page')!.props.onClick(); await settle(); expect(view.find('selection-count')!.text).toContain('2');
    action(); await settle(); expect(view.find('selection-count')!.text).toContain('0');
  }
});
it('批量确认期间切换范围使旧确认失效，不能把旧选择删到新列表', async () => {
  let answer!: (value: boolean) => void;
  vi.stubGlobal('confirm', vi.fn(() => new Promise(resolve => { answer = resolve; })));
  const deleteActivities = vi.fn();
  const view = await mount('Activity', { deleteActivities, events: async () => ({ total: 2, items: batchRows() }), devices: async () => ({ devices: [] }) });
  view.find('select-page')!.props.onClick(); await settle(); view.find('delete-selected')!.props.onClick(); await settle();
  view.find('mode-raw')!.props.onClick(); await settle(); answer(true); await settle();
  expect(deleteActivities).not.toHaveBeenCalled();
});
it('批量删除进行中防止重复提交，失败保持选择可重试', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true)); let reject!: (e: Error) => void;
  const deleteActivities = vi.fn(() => new Promise((_resolve, fail) => { reject = fail; }));
  const view = await mount('Activity', { deleteActivities, events: async () => ({ total: 2, items: batchRows() }), devices: async () => ({ devices: [] }) });
  view.find('select-page')!.props.onClick(); await settle();
  const click = view.find('delete-selected')!.props.onClick; click(); click(); await settle();
  expect(deleteActivities).toHaveBeenCalledOnce(); expect(view.find('delete-selected')!.props.disabled).toBe(true);
  reject(Error('测试失败')); await settle(); expect(view.find('selection-count')!.text).toContain('2'); expect(view.find('delete-selected')!.props.disabled).toBe(false);
});
it('批量删除末页全部选中行后回到有效页并清空选择', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  const events = vi.fn().mockResolvedValueOnce({ total: 22, items: batchRows() }).mockResolvedValueOnce({ total: 22, items: batchRows() })
    .mockResolvedValueOnce({ total: 20, items: [] }).mockResolvedValue({ total: 20, items: [editGroup] });
  const view = await mount('Activity', { events, deleteActivities: async () => ({ deleted: 4 }), devices: async () => ({ devices: [] }) });
  view.all().find(n => n.tag === 'button' && n.text === '下一页')!.props.onClick(); await settle();
  view.find('select-page')!.props.onClick(); await settle(); view.find('delete-selected')!.props.onClick(); await settle();
  expect(events.mock.calls.map(call => call[0].page)).toEqual([1,2,2,1]); expect(view.find('selection-count')!.text).toContain('0');
});

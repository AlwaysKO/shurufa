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
    if (id === '../confirmation') return { useConfirmation: () => async (message: string) => Boolean(globalThis.confirm?.(message)) };
    if (id === 'vue-router') return { useRoute: () => ({ query: {} }) };
    if (id === '../api/personalDictionary') return { dictionaryApi: api };
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

const devices = [
  { device_id: 'old', name: '旧手机', in_group: true, synced: false, migration_status: 'complete', imported: 3 },
  { device_id: 'new', name: '新手机', in_group: false, synced: false, migration_status: 'unavailable', imported: 0 },
];
function mockApi() { return {
  devices: vi.fn().mockResolvedValue({ devices }),
  entries: vi.fn().mockResolvedValue({ total: 51, page: 1, entries: [{device_id:'old',text:'充电宝',kind:'choice',source:'selection',code:'2466434262',pinyin:'',count:3,weight:2.7,last_used:1000,status:'enabled'}] }),
  addWord: vi.fn().mockResolvedValue({ok:true,created:true}), sync: vi.fn().mockResolvedValue({ok:true,words:1,queued:1,skipped:0,devices:1}),
  bind: vi.fn().mockResolvedValue({ok:true}), decisions: vi.fn().mockResolvedValue({ok:true}),
}; }
it('展示原始来源、未知拼音、等待手机确认，支持按设备查询和分页', async () => {
  const api = mockApi(); const view = await mount('PersonalDictionary', api);
  expect(view.text()).toContain('旧手机'); expect(view.text()).toContain('新手机');
  expect(view.text()).toContain('等待手机同步'); expect(view.text()).toContain('未记录');
  view.find('device-old')!.props.onClick(); await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({device_id:'old',page:1});
  view.find('next-page')!.props.onClick(); await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({page:2});
});
it('绑定必须确认，删除发出后台决策而不是抹掉上报明细', async () => {
  const api = mockApi(); vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));
  const view = await mount('PersonalDictionary', api);
  view.find('bind-new')!.props.onClick(); await settle(); expect(api.bind).not.toHaveBeenCalled();
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  view.find('bind-new')!.props.onClick(); await settle(); expect(api.bind).toHaveBeenCalledWith('new');
  view.find('delete-0')!.props.onClick(); await settle();
  expect(api.decisions).toHaveBeenCalledWith(['充电宝'],'deleted');
  expect(view.text()).toContain('充电宝');
});
it('镜像展示上报而不显示等待应用或允许无效管理操作',async()=>{
  const api=mockApi();api.devices.mockResolvedValue({devices:devices.map(d=>({...d,restore_enabled:false}))});
  const view=await mount('PersonalDictionary',api);
  expect(view.text()).toContain('仅备份');
  expect(view.text()).not.toContain('等待手机同步');
  expect(view.find('delete-0')!.props.disabled).toBe(true);
  expect(view.find('bind-new')!.props.disabled).toBe(true);
});

function update(view: Awaited<ReturnType<typeof mount>>, id: string, value: string) {
  view.find(id)!.props['onUpdate:modelValue'](value);
}
function click(view: Awaited<ReturnType<typeof mount>>, id: string) { view.find(id)!.props.onClick(); }
function check(view: Awaited<ReturnType<typeof mount>>, id: string, checked = true) { view.find(id)!.props.onChange({target:{checked}}); }
it('默认合并查看，字段分列，所有按钮采用已有统一样式', async () => {
  const api=mockApi(); const view=await mount('PersonalDictionary',api);
  expect(api.entries.mock.calls[0][0].view).toBe('merged');
  const headers=view.all().filter(n=>n.tag==='th').map(n=>n.text);
  expect(headers).toEqual(['选择','词语','状态','手机','来源','拼音','输入码','真实选词次数','原始权重','最近使用','管理']);
  expect(view.all().filter(n=>n.tag==='button').every(n=>String(n.props.class).includes('library-button'))).toBe(true);
});
it('备份端也可手工添加并向指定未绑定手机纯加法同步，不调用绑定', async () => {
  const api=mockApi(); api.devices.mockResolvedValue({devices:devices.map(d=>({...d,restore_enabled:false,additions_supported:false,additions_pending:0}))});
  const entry=(await api.entries()).entries[0];api.entries.mockResolvedValue({entries:[{...entry,text:'泰鲮'}],total:1,page:1});
  const view=await mount('PersonalDictionary',api);
  update(view,'new-word',' 泰鲮 '); update(view,'new-pinyin','tai ling');
  view.find('add-form')!.props.onSubmit({preventDefault(){}}); await settle();
  expect(api.addWord).toHaveBeenCalledWith({text:'泰鲮',pinyin:'tai ling'});
  check(view,'target-new'); await settle(); click(view,'sync-selected'); await settle();
  expect(api.sync).toHaveBeenCalledWith({device_ids:['new'],texts:['泰鲮']});
  expect(api.bind).not.toHaveBeenCalled();
  expect(view.text()).toContain('等待手机确认'); expect(view.text()).toContain('需升级');
  expect(view.find('delete-0')!.props.disabled).toBe(true);
});
it('当前页全选按词去重，全不选彻底清除',async()=>{
  const api=mockApi();const first=(await api.entries()).entries[0];api.entries.mockResolvedValue({entries:[first,{...first,source:'rime'}],total:2,page:1});
  const view=await mount('PersonalDictionary',api);check(view,'target-old');click(view,'select-page');await settle();
  expect(view.text()).toContain('已选 1 词');click(view,'sync-selected');await settle();
  expect(api.sync).toHaveBeenLastCalledWith({device_ids:['old'],texts:['充电宝']});
  click(view,'select-none');await settle();expect(view.find('sync-selected')!.props.disabled).toBe(true);
});
it('全部筛选结果跨页保留，通过筛选快照而非当前页列表同步，筛选变化清理',async()=>{
  const api=mockApi();const view=await mount('PersonalDictionary',api);
  update(view,'search','充电');await settle();check(view,'target-old');click(view,'select-all');await settle();
  click(view,'next-page');await settle();click(view,'sync-selected');await settle();
  expect(api.sync).toHaveBeenCalledWith({device_ids:['old'],all:true,filter:{q:'充电',status:'enabled'}});
  update(view,'search','泰鲮');await settle();
  expect(view.find('sync-selected')!.props.disabled).toBe(true);
  click(view,'select-page');await settle();update(view,'status-filter','disabled');await settle();
  expect(view.find('sync-selected')!.props.disabled).toBe(true);
});
it('添加和同步失败显示错误，不冒充应用成功，不清除可重试选择',async()=>{
  const api=mockApi();api.addWord.mockRejectedValue(new Error('拼音不匹配'));
  const view=await mount('PersonalDictionary',api);update(view,'new-word','泰鲮');update(view,'new-pinyin','tai wo');
  view.find('add-form')!.props.onSubmit({preventDefault(){}});await settle();
  expect(view.text()).toContain('拼音不匹配');expect(view.text()).not.toContain('已添加');
  api.sync.mockRejectedValue(new Error('网络失败'));check(view,'target-old');click(view,'select-page');await settle();click(view,'sync-selected');await settle();
  expect(view.text()).toContain('网络失败');expect(view.text()).not.toContain('已排队');expect(view.find('sync-selected')!.props.disabled).toBe(false);
});
it('同步请求期间锁定目标与选择，重复点击不会重复发送',async()=>{
  const api=mockApi();let resolve!: (v:any)=>void;api.sync.mockImplementation(()=>new Promise(r=>{resolve=r;}));
  const view=await mount('PersonalDictionary',api);check(view,'target-old');click(view,'select-page');await settle();click(view,'sync-selected');await settle();
  expect(view.find('target-new')!.props.disabled).toBe(true);expect(view.find('select-none')!.props.disabled).toBe(true);
  check(view,'target-new');click(view,'sync-selected');expect(api.sync).toHaveBeenCalledTimes(1);
  resolve({ok:true,words:1,queued:1,skipped:0,devices:1});await settle();
  expect(api.sync).toHaveBeenCalledWith({device_ids:['old'],texts:['充电宝']});
});
it('较慢的旧筛选结果不能覆盖新筛选，也不能启用错误的全选',async()=>{
  const api=mockApi();const view=await mount('PersonalDictionary',api);let resolve!:(v:any)=>void;
  api.entries.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));update(view,'search','旧查询');await settle();
  expect(view.find('select-all')!.props.disabled).toBe(true);
  api.entries.mockResolvedValue({entries:[],total:0,page:1});update(view,'search','新查询');await settle();
  resolve({entries:[{text:'错误旧结果'}],total:1,page:1});await settle();expect(view.text()).not.toContain('错误旧结果');
  expect(view.find('select-all')!.props.disabled).toBe(true);
});
it('合并后的纯手工词不伪装手机、点击次数或多种来源',async()=>{
  const api=mockApi();api.entries.mockResolvedValue({entries:[{device_id:'',device_ids:[],kind:'merged',text:'泰鲮',pinyin:'tai ling',code:'',source:'merged',sources:['dashboard'],has_choices:false,count:0,weight:0,last_used:0,status:'enabled'}],total:1,total_words:1,page:1});
  const view=await mount('PersonalDictionary',api);
  const row=view.all().find(n=>n.tag==='tbody')!.children.find(n=>n.tag==='tr')!;
  const values=row.children.filter(n=>n.tag==='td').map(n=>n.text);
  expect(values[3]).toBe('—（后台添加）');expect(values[4]).toBe('后台手动添加');expect(values[7]).toBe('—（非点击记录）');
});
it('合并视图非后台来源的手机信息缺失不应冒充后台添加',async()=>{
  const api=mockApi();api.entries.mockResolvedValue({entries:[{device_id:'',device_ids:[],kind:'merged',text:'充电宝',pinyin:'chong dian bao',code:'',source:'merged',sources:['selection'],has_choices:true,count:2,weight:1,last_used:0,status:'enabled'}],total:1,page:1});
  const view=await mount('PersonalDictionary',api);
  expect(view.text()).toContain('手机来源未记录');expect(view.text()).not.toContain('—（后台添加）');
});
it('卸载后旧添加响应不再加载当前手机词库',async()=>{
  const api=mockApi();let resolve!:(v:any)=>void;api.addWord.mockImplementation(()=>new Promise(r=>{resolve=r;}));
  const view=await mount('PersonalDictionary',api);update(view,'new-word','泰鲮');update(view,'new-pinyin','tai ling');
  view.find('add-form')!.props.onSubmit({preventDefault(){}});await settle();
  mounted.pop()!.unmount();const count=api.entries.mock.calls.length;
  resolve({ok:true,created:true});await settle();expect(api.entries).toHaveBeenCalledTimes(count);
});
it('查询失败后旧页内容不可选中或发送，重试成功再恢复',async()=>{
  const api=mockApi();const view=await mount('PersonalDictionary',api);check(view,'target-old');click(view,'select-page');await settle();
  api.entries.mockRejectedValueOnce(new Error('查询失败'));update(view,'search','新词');await settle();
  expect(view.find('select-page')!.props.disabled).toBe(true);expect(view.find('select-all')!.props.disabled).toBe(true);expect(view.find('sync-selected')!.props.disabled).toBe(true);
  click(view,'select-page');click(view,'sync-selected');await settle();expect(api.sync).not.toHaveBeenCalled();
});

it('一键同步全部习惯和手工词只需选手机，不受筛选分页或勾选词影响',async()=>{
  const api={...mockApi(),syncAll:vi.fn().mockResolvedValue({ok:true,words:20,queued:2,skipped:0,devices:1,habits:10,habits_queued:3})};
  const view=await mount('PersonalDictionary',api);
  expect(view.find('sync-all-habits')).toBeDefined();
  expect(view.find('sync-all-habits')!.props.disabled).toBe(true);
  update(view,'search','不存在的筛选');await settle();
  check(view,'target-new');await settle();
  expect(view.find('sync-all-habits')!.props.disabled).toBe(false);
  click(view,'sync-all-habits');await settle();
  expect(api.syncAll).toHaveBeenCalledWith({device_ids:['new']});
  expect(api.sync).not.toHaveBeenCalled();expect(api.bind).not.toHaveBeenCalled();
  expect(view.text()).toContain('10 条真实习惯');
  expect(view.text()).toContain('等待手机确认');
  expect(view.text()).not.toContain('全部习惯已应用');
});
it('习惯和词条独立展示接收状态，旧手机不能冒称已支持习惯',async()=>{
  const api=mockApi();api.devices.mockResolvedValue({devices:[
    {...devices[0],additions_supported:true,additions_pending:0,additions_applied_at:'2026-09-22T00:00:00Z',habits_supported:true,habits_pending:3,habits_applied_at:null},
    {...devices[1],additions_supported:true,additions_pending:0,habits_supported:false},
  ]});
  const view=await mount('PersonalDictionary',api);
  expect(view.text()).toContain('习惯待应用：3 条');
  expect(view.text()).toContain('需升级：尚未支持习惯接收');
  expect(view.text()).not.toContain('全部习惯已应用');
});
it('一键同步请求中阻止重复点击，失败可重试且不清手机选择',async()=>{
  let reject!:(reason:Error)=>void;
  const api={...mockApi(),syncAll:vi.fn(()=>new Promise((_resolve,r)=>{reject=r;}))};
  const view=await mount('PersonalDictionary',api);check(view,'target-new');await settle();
  expect(view.find('sync-all-habits')).toBeDefined();
  click(view,'sync-all-habits');click(view,'sync-all-habits');await settle();
  expect(api.syncAll).toHaveBeenCalledTimes(1);expect(view.find('sync-all-habits')!.props.disabled).toBe(true);
  reject(new Error('习惯同步网络失败'));await settle();
  expect(view.text()).toContain('习惯同步网络失败');
  expect(view.find('target-new')!.props.checked).toBe(true);
  expect(view.find('sync-all-habits')!.props.disabled).toBe(false);
});

it('默认只查询启用词，新增后回到启用列表并明确显示保存的词语',async()=>{
  const api=mockApi();const entry=(await api.entries()).entries[0];
  api.entries.mockResolvedValue({entries:[{...entry,text:'也正常'}],total:1,page:1});
  const view=await mount('PersonalDictionary',api);
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({status:'enabled'});
  update(view,'status-filter','deleted');await settle();
  update(view,'new-word','也正常');update(view,'new-pinyin','ye zheng chang');
  view.find('add-form')!.props.onSubmit({preventDefault(){}});await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({q:'也正常',status:'enabled',page:1});
  expect(view.text()).toContain('「也正常」');
});
it('删除末页最后一词后回到有效页，已删除词可通过状态筛选查看并恢复',async()=>{
  const api=mockApi();vi.stubGlobal('confirm',vi.fn().mockReturnValue(true));
  let deleted=false;
  const entry=(await api.entries()).entries[0];
  api.entries.mockImplementation(async(f:any)=>{
    if(f.status==='deleted') return {entries:deleted?[{...entry,status:'deleted'}]:[],total:deleted?1:0};
    return {entries:deleted && f.page===2?[]:[entry],total:deleted?50:51};
  });
  api.decisions.mockImplementation(async(_texts:any,status:string)=>{deleted=status==='deleted';return {ok:true};});
  const view=await mount('PersonalDictionary',api);click(view,'next-page');await settle();
  click(view,'delete-0');await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({status:'enabled',page:1});
  expect(view.text()).toContain('已删除');
  update(view,'status-filter','deleted');await settle();
  expect(view.text()).toContain('充电宝');
  const restore=view.all().find(n=>n.tag==='button' && n.text==='保留/恢复')!;
  restore.props.onClick();await settle();
  expect(api.decisions).toHaveBeenLastCalledWith(['充电宝'],'enabled');
  expect(view.text()).not.toContain('充电宝');
});
it.each(['deleted','disabled'])('重新添加 %s 同名词时展示其原状态，不冒充恢复或选中不可见词',async(status)=>{
  const api=mockApi();api.addWord.mockResolvedValue({ok:true,created:false});
  const entry=(await api.entries()).entries[0];
  api.entries.mockImplementation(async(f:any)=>({entries:f.status==='enabled'?[]:[{...entry,text:'也正常',status}],total:f.status==='enabled'?0:1}));
  const view=await mount('PersonalDictionary',api);
  update(view,'new-word','也正常');update(view,'new-pinyin','ye zheng chang');
  view.find('add-form')!.props.onSubmit({preventDefault(){}});await settle();
  expect(view.text()).toContain('已删除');
  expect(view.text()).toContain('已选 1 词');
  expect(view.all().filter(n=>n.tag==='td').map(n=>n.text)).toContain(status==='deleted'?'已删除':'停用');
  expect(view.text()).toContain('须先在主后台恢复后再同步');
  expect(api.entries.mock.calls.at(-1)?.[0].status).toBeUndefined();
  expect(api.decisions).not.toHaveBeenCalled();
});

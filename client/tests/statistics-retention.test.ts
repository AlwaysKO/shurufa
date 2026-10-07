import { readFileSync } from 'node:fs';
import { expect, it, vi, afterEach } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

type Node = { tag: string; text: string; children: Node[]; parent: Node | null; props: Record<string, any>; value: string; options: Node[]; files?: File[]; addEventListener: () => void; removeEventListener: () => void; click: () => void; tagName: string; getRootNode: () => object };
const mounted: Vue.App[] = [];
afterEach(() => { mounted.splice(0).forEach(app => app.unmount()); vi.unstubAllGlobals(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick(); } }
async function mount(api: Record<string, any> = {}, initial: Record<string, any> = {}) {
  const currentUserId = Vue.ref('user-a'); const props = Vue.reactive({ dataset: 'input', label: '输入记录', filters: {}, ...initial }); const changed = vi.fn();
  api = { previewStatisticsCleanup: vi.fn().mockResolvedValue(preview), deleteStatisticsCleanupBatch: vi.fn().mockResolvedValue(done), ...api };
  const { descriptor } = parse(readFileSync(new URL('../src/components/RetentionCleanup.vue', import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: 'retention', inlineTemplate: true });
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
    if (id === '../api') return { api, currentUserId, appName: (s: string) => s, deviceDetailLines: () => [], deviceLabel: () => '', eventTypeName: (s: string) => s, networkName: (s: string) => s };
    if (id === '../data/phrasePresets') return presets;
    if (id.endsWith('.css')) return {};
    throw new Error(`Unexpected import: ${id}`);
  };
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root'); const app = renderer.createApp({ render: () => Vue.h(module.exports.default, { ...props, onChanged: changed }) }); app.mount(root); mounted.push(app); await settle();
  const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
  const find = (id: string) => all(root).find(n => n.props['data-testid'] === id);
  return { api, props, currentUserId, changed, unmount: () => app.unmount(), root, find, all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
}


const preview = { token: 'frozen', cutoff: '2026-10-01T01:00:00Z', total_records: 401, total_files: 2, first_at: '2020-01-01T00:00:00Z', last_at: '2026-09-30T23:00:00Z' };
const done = { processed: 401, total: 401, deleted_records: 400, skipped_records: 1, done: true };
function deferred<T>() { let resolve!: (v:T)=>void; const promise = new Promise<T>(r=>resolve=r); return {promise,resolve}; }
it.each([1,7,30])('保留%s天先预览，确认一次跨页批次并刷新',async days=>{
 const confirm=vi.fn().mockResolvedValue(true);vi.stubGlobal('confirm',confirm);
 const batch=vi.fn().mockResolvedValueOnce({...done,processed:200,deleted_records:200,skipped_records:0,done:false}).mockResolvedValueOnce(done);
 const view=await mount({deleteStatisticsCleanupBatch:batch},{dataset:'navigation',label:'导航记录',filters:{platform:'amap'},scopeLabel:'高德地图'});
 await view.find(`retention-keep-${days}`)!.props.onClick();await settle();
 expect(view.api.previewStatisticsCleanup).toHaveBeenCalledWith('navigation',{days,filters:{platform:'amap'}});
 expect(confirm).toHaveBeenCalledTimes(1);expect(confirm.mock.calls[0][0]).toContain('401');expect(confirm.mock.calls[0][0]).toContain('高德地图');
 expect(batch.mock.calls).toEqual([['navigation',{confirm:'DELETE',token:'frozen',offset:0}],['navigation',{confirm:'DELETE',token:'frozen',offset:200}]]);
 expect(view.changed).toHaveBeenCalledTimes(1);expect(view.text()).toContain('400');expect(view.text()).toContain('1');
});
it('取消或空预览不执行删除',async()=>{
 vi.stubGlobal('confirm',vi.fn().mockResolvedValue(false));const view=await mount();await view.find('retention-keep-7')!.props.onClick();expect(view.api.deleteStatisticsCleanupBatch).not.toHaveBeenCalled();
 view.api.previewStatisticsCleanup.mockResolvedValue({...preview,total_records:0});await view.find('retention-keep-1')!.props.onClick();await settle();expect(view.text()).toContain('没有');expect(globalThis.confirm).toHaveBeenCalledTimes(1);
});
it('局部失败也刷新且显示已处理数量',async()=>{
 vi.stubGlobal('confirm',()=>true);const view=await mount({deleteStatisticsCleanupBatch:vi.fn().mockResolvedValueOnce({...done,processed:200,deleted_records:200,skipped_records:0,done:false}).mockRejectedValueOnce(Error('网络中断'))});
 await view.find('retention-keep-7')!.props.onClick();await settle();expect(view.changed).toHaveBeenCalledTimes(1);expect(view.text()).toContain('200');expect(view.text()).toContain('网络中断');
});
it.each(['user','filter','unmount'])('进行中%s变化停止后续批次；A→B→A不复用旧范围',async kind=>{
 vi.stubGlobal('confirm',()=>true);const pending=deferred<any>();const batch=vi.fn().mockReturnValue(pending.promise);const view=await mount({deleteStatisticsCleanupBatch:batch});
 const running=view.find('retention-keep-7')!.props.onClick();await settle();
 if(kind==='user'){view.currentUserId.value='user-b';view.currentUserId.value='user-a';}
 if(kind==='filter'){view.props.filters={q:'乙'};await Vue.nextTick();view.props.filters={};await Vue.nextTick();}
 if(kind==='unmount')view.unmount();
 pending.resolve({...done,processed:200,done:false});await running;await settle();expect(batch).toHaveBeenCalledTimes(1);
});
it('预览中重复点击只产生一个任务，预览失败显示服务端说明',async()=>{
 const pending=deferred<any>();const view=await mount({previewStatisticsCleanup:vi.fn().mockReturnValue(pending.promise)});
 const first=view.find('retention-keep-7')!.props.onClick();await settle();await view.find('retention-keep-1')!.props.onClick();expect(view.api.previewStatisticsCleanup).toHaveBeenCalledTimes(1);
 pending.resolve({...preview,total_records:0});await first;
 view.api.previewStatisticsCleanup.mockRejectedValueOnce(Error('记录过多'));await view.find('retention-keep-30')!.props.onClick();await settle();expect(view.text()).toContain('记录过多');
});
it('所有历史统计页面接入对应清理类别',()=>{
 const pages:Record<string,string[]>={Overview:['input'],Timeline:['input'],Applications:['input'],WordCloud:['input'],Clipboard:['clipboard'],ClipboardHistory:['clipboard'],AppUsage:['app-usage'],LocationTrack:['locations'],NavigationRecords:['navigation'],Report:['input','locations'],CallRecordingsView:['call-recordings'],PhoneCallLogs:['call-logs']};
 for(const [name,datasets] of Object.entries(pages)){const source=readFileSync(new URL(`../src/views/${name}.vue`,import.meta.url),'utf8');for(const dataset of datasets)expect(source,`${name} ${dataset}`).toMatch(new RegExp(`<RetentionCleanup[^>]*dataset="${dataset}"`));}
});
it('通用清理API绑定当前手机并传递服务端错误',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(preview))).mockResolvedValueOnce(new Response(JSON.stringify({error:'预览已失效'}),{status:410}));
 const module={exports:{} as any};const code=ts.transpileModule(readFileSync(new URL('../src/api/index.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 new Function('require','module','exports','localStorage','window',code)((id:string)=>id==='vue'?Vue:{dashboardFetch:fetch},module,module.exports,{getItem:()=> 'user-a'},{location:{origin:'http://test.local'}});
 const api=module.exports.api;await api.previewStatisticsCleanup('locations',{days:1,filters:{}});
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/retention/locations/preview?user_id=user-a');
 expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({days:1,filters:{}});
 await expect(api.deleteStatisticsCleanupBatch('locations',{confirm:'DELETE',token:'frozen',offset:0})).rejects.toThrow('预览已失效');
});
it('补全清理确认说明累计次数和手机缓存边界',async()=>{
 const confirm=vi.fn().mockResolvedValue(false);vi.stubGlobal('confirm',confirm);const view=await mount({}, {dataset:'completions',label:'补全候选与学习统计'});
 await view.find('retention-keep-7')!.props.onClick();expect(confirm.mock.calls[0][0]).toContain('最后使用时间');expect(confirm.mock.calls[0][0]).toContain('累计次数不能按天拆分');expect(confirm.mock.calls[0][0]).toContain('手机既有缓存');
});
it('数据管理提供全部八类的集中入口',()=>{
 const source=readFileSync(new URL('../src/views/DataManage.vue',import.meta.url),'utf8');expect(source).toContain('按保留期限清理');expect(source).toContain('<RetentionCleanup');
 for(const category of ['input','clipboard','app-usage','locations','navigation','call-logs','call-recordings','completions']) expect(source).toContain(`'${category}'`);
});
it.each(['filter', 'date', 'user', 'dataset'])('在途批次后%s变化只刷新同手机同类别，不再提交下一批', async kind => {
 vi.stubGlobal('confirm', () => true); const pending=deferred<any>(); const batch=vi.fn().mockReturnValue(pending.promise); const view=await mount({deleteStatisticsCleanupBatch:batch});
 const running=view.find('retention-keep-7')!.props.onClick();await settle();
 if(kind==='filter')view.props.filters={q:'新关键词'};
 if(kind==='date')Object.assign(view.props,{context:'2026-10-06'});
 if(kind==='user')view.currentUserId.value='user-b';
 if(kind==='dataset')view.props.dataset='clipboard';
 await settle();pending.resolve({...done,processed:200,done:false});await running;
 expect(batch).toHaveBeenCalledTimes(1);expect(view.changed).toHaveBeenCalledTimes(kind==='filter'||kind==='date'?1:0);
});
async function mountStatisticsPage(name: string, api: Record<string, unknown>) {
 const {descriptor}=parse(readFileSync(new URL(`../src/views/${name}.vue`,import.meta.url),'utf8'));
 const script=compileScript(descriptor,{id:'statistics-page-race'});
 const code=ts.transpileModule(script.content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as any};const chart={setOption:vi.fn(),resize(){},dispose(){}};
 new Function('require','module','exports','document',code)((id:string)=>{
  if(id==='vue')return Vue;
  if(id==='../api')return {api,appName:(v:string)=>v};
  if(id==='echarts')return {init:()=>chart,getInstanceByDom:()=>chart};
  if(id==='../components/RetentionCleanup.vue')return {default:{render:()=>null}};
  throw Error(id);
 },module,module.exports,{getElementById:()=>({})});
 let state:any;const renderer=Vue.createRenderer<any,any>({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){},insert(){},remove(){},insertStaticContent:()=>[{},{}]});
 const app=renderer.createApp({setup(){state=module.exports.default.setup({}, {expose(){}});return ()=>null;}});app.mount({});mounted.push(app);await settle();return {state,chart,unmount:()=>app.unmount()};
}
it.each(['Overview','ClipboardHistory'])('%s 清理刷新后迟到旧查询不得恢复被删记录',async name=>{
 const old=deferred<any>();const fresh=name==='Overview'?{total_chars:'0',source_distribution:{typed:0,pasted:0,external:0,voice:0}}:{items:[],total:0};
 const stale=name==='Overview'?{total_chars:'999',source_distribution:{typed:999,pasted:0,external:0,voice:0}}:{items:[{id:'deleted',package_name:'old.app'}],total:999};
 const query=vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(fresh);
 const view=await mountStatisticsPage(name,name==='Overview'?{overview:query}:{events:query,apps:async()=>({apps:[]})});
 await view.state.load();old.resolve(stale);await settle();
 if(name==='Overview'){expect(view.state.data.value.total_chars).toBe('0');expect(view.chart.setOption).toHaveBeenCalledTimes(1);}
 else {expect(view.state.items.value).toEqual([]);expect(view.state.total.value).toBe(0);expect([...view.state.appOptions.value]).toEqual([]);}
});
it.each(['Overview','ClipboardHistory'])('%s 卸载后迟到查询不更新状态',async name=>{
 const old=deferred<any>();const query=vi.fn().mockReturnValue(old.promise);const view=await mountStatisticsPage(name,name==='Overview'?{overview:query}:{events:query,apps:async()=>({apps:[]})});view.unmount();
 old.resolve(name==='Overview'?{total_chars:'999',source_distribution:{}}:{items:[{id:'deleted'}],total:999});await settle();
 if(name==='Overview'){expect(view.state.data.value).toBeNull();expect(view.chart.setOption).not.toHaveBeenCalled();}else expect(view.state.total.value).toBe(0);
});

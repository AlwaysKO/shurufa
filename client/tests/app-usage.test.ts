import { readFileSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
const mounted: Vue.App[]=[];
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve();await Vue.nextTick();};
afterEach(()=>{vi.useRealTimers();mounted.splice(0).forEach(app=>app.unmount());});
async function setup(){
 const current=Vue.ref('phone-a');
 const api={appUsage:vi.fn(async(q:any)=>({overview:{duration_ms:0,count:0,gap_count:0,last_received_at:null},apps:[],daily:[],records:[],total:61,page:q.page,page_size:50})),appUsageDay:vi.fn(async(day:string)=>({day,start_ms:0,end_ms:86400000,total:0,limit:2000,truncated:false,records:[]}))};
 const {descriptor}=parse(readFileSync(new URL('../src/views/AppUsage.vue',import.meta.url),'utf8'));
 const script=compileScript(descriptor,{id:'app-usage-test'});
 const code=ts.transpileModule(script.content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as {default:{setup:Function}}};
 new Function('require','module','exports',code)((id:string)=>{
  if (id === '../components/RetentionCleanup.vue') return { default: { render: () => null } };
    if(id==='vue')return Vue;
  if(id==='../api')return {api,currentUserId:current,appName:(pkg:string,name:string)=>name||pkg};
  throw Error(id);
 },module,module.exports);
 let state:any;
 const renderer=Vue.createRenderer<any,any>({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){},insert(){},remove(){},insertStaticContent:()=>[{},{}]});
 const app=renderer.createApp({setup(){state=module.exports.default.setup({}, {expose(){}});return ()=>null;}});app.mount({});mounted.push(app);await settle();return {state,api,current};
}
it('未提交的App和起止日期不改变翻页查询，回第1页也不能提交草稿',async()=>{
 const {state,api}=await setup();const committed={...api.appUsage.mock.calls[0][0]};
 state.packageName.value='com.unsubmitted';state.from.value='2026-01-01';state.to.value='2026-01-02';
 await state.load(2);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:2});expect(api.appUsageDay).toHaveBeenCalledTimes(1);
 await state.load(1);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:1});expect(api.appUsageDay).toHaveBeenCalledTimes(1);
 await state.load();expect(api.appUsage).toHaveBeenLastCalledWith({from:'2025-12-31T16:00:00.000Z',to:'2026-01-02T16:00:00.000Z',package_name:'com.unsubmitted',page:1});
 expect(api.appUsageDay).toHaveBeenLastCalledWith('2026-01-02','com.unsubmitted');
});
it('非法草稿日期也不阻塞已提交查询的翻页',async()=>{
 const {state,api}=await setup();const committed={...api.appUsage.mock.calls[0][0]};state.from.value='';state.to.value='bad';
 await state.load(2);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:2});expect(state.error.value).toBe('');
});
it('新增结束原因提供中文名称',async()=>{
 const {state}=await setup();expect(state.reasons.switch).toBe('切换应用');expect(state.reasons.process_restart).toBe('进程重启记录中断');
});
it('翻页期间保持整页及上方统计，完成后只替换底部明细',async()=>{
 const {state,api}=await setup();
 const original=state.data.value, overview=original.overview, apps=original.apps, daily=original.daily, timeline=state.timeline.value;
 let finish!:(value:any)=>void;
 api.appUsage.mockImplementationOnce(()=>new Promise<any>(resolve=>{finish=resolve;}));
 const pending=state.load(2);
 expect(state.data.value).toBe(original);expect(state.loading.value).toBe(false);expect(state.recordsLoading.value).toBe(true);
 finish({...original,overview:{...overview,count:999},apps:[{package_name:'changed'}],daily:[{day:'changed'}],records:[{id:'page-2'}],page:2});await pending;
 expect(state.data.value.overview).toBe(overview);expect(state.data.value.apps).toBe(apps);expect(state.data.value.daily).toBe(daily);
 expect(state.timeline.value).toBe(timeline);expect(state.data.value.records).toEqual([{id:'page-2'}]);expect(state.data.value.page).toBe(2);
 expect(state.recordsLoading.value).toBe(false);expect(api.appUsageDay).toHaveBeenCalledTimes(1);
});
it('分页失败仅显示局部错误，保留原明细且可以重试',async()=>{
 const {state,api}=await setup();const original=state.data.value;
 api.appUsage.mockRejectedValueOnce(Error('分页网络失败'));await state.load(2);
 expect(state.data.value).toBe(original);expect(state.error.value).toBe('');expect(state.recordsError.value).toBe('分页网络失败');
 expect(state.recordsLoading.value).toBe(false);await state.load(2);expect(state.recordsError.value).toBe('');expect(state.data.value.page).toBe(2);
});
it('提交新筛选后旧分页错误不能污染新查询',async()=>{
 const {state,api}=await setup();let fail!:(reason:Error)=>void;
 api.appUsage.mockImplementationOnce(()=>new Promise<any>((_,reject)=>{fail=reject;}));const old=state.load(2);
 state.packageName.value='new.app';await state.load();const newData=state.data.value;
 fail(Error('旧分页失败'));await old;
 expect(state.data.value).toBe(newData);expect(state.recordsError.value).toBe('');expect(state.error.value).toBe('');expect(state.recordsLoading.value).toBe(false);
});
it('切换设备后旧分页和旧时间轴请求不能覆盖新设备结果',async()=>{
 const {state,api,current}=await setup();
 let finishPage!:(value:any)=>void,finishDay!:(value:any)=>void;
 api.appUsage.mockImplementationOnce(()=>new Promise<any>(resolve=>{finishPage=resolve;}));
 api.appUsageDay.mockImplementationOnce(()=>new Promise<any>(resolve=>{finishDay=resolve;}));
 const oldPage=state.load(2),oldDay=state.loadDay();
 current.value='phone-b';await settle();
 const newData=state.data.value,newDay=state.timeline.value;
 finishPage({...newData,page:999});finishDay({...newDay,day:'2000-01-01'});await Promise.all([oldPage,oldDay]);
 expect(state.data.value).toEqual(newData);expect(state.timeline.value).toEqual(newDay);
});

it('默认今天且只显示日期控件',async()=>{
 vi.useFakeTimers();vi.setSystemTime(new Date('2026-09-28T23:30:00Z'));
 const {state,api}=await setup();
 expect(state.from.value).toBe('2026-09-29');expect(state.to.value).toBe('2026-09-29');
 expect(state.activePreset.value).toBe('today');
 expect(api.appUsage).toHaveBeenLastCalledWith({from:'2026-09-28T16:00:00.000Z',to:'2026-09-29T16:00:00.000Z',package_name:undefined,page:1});
 const source=readFileSync(new URL('../src/views/AppUsage.vue',import.meta.url),'utf8');
 expect(source).not.toContain('type="time"');expect(source).not.toContain('type="datetime-local"');
});
it('日期包含结束当天，清空或倒序日期不发请求',async()=>{
 const {state,api}=await setup();state.from.value='2026-09-28';state.to.value='2026-09-29';await state.load();
 expect(api.appUsage).toHaveBeenLastCalledWith({from:'2026-09-27T16:00:00.000Z',to:'2026-09-29T16:00:00.000Z',package_name:undefined,page:1});
 const calls=api.appUsage.mock.calls.length;
 for(const [from,to] of [['','2026-09-29'],['2026-09-30','2026-09-29'],['2026-02-30','2026-03-01']]){
  state.from.value=from;state.to.value=to;await state.load();expect(state.error.value).not.toBe('');
 }
 expect(api.appUsage).toHaveBeenCalledTimes(calls);
});
it('快捷日期立即查第一页且包含今天，跨年按北京时间计算',async()=>{
 vi.useFakeTimers();vi.setSystemTime(new Date('2026-12-31T17:00:00Z'));
 const {state,api}=await setup();state.packageName.value='com.example';
 for(const [key,from,to] of [['yesterday','2026-12-31','2026-12-31'],['7days','2026-12-26','2027-01-01'],['30days','2026-12-03','2027-01-01'],['today','2027-01-01','2027-01-01']]){
  await state.selectPreset(key);expect(state.from.value).toBe(from);expect(state.to.value).toBe(to);
  expect(state.activePreset.value).toBe(key);expect(api.appUsage.mock.calls.at(-1)![0]).toMatchObject({page:1,package_name:'com.example'});
 }
 state.from.value='2026-12-20';await settle();expect(state.activePreset.value).toBe('');
});

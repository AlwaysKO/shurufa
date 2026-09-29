import { readFileSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
const mounted: Vue.App[]=[];
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve();await Vue.nextTick();};
afterEach(()=>{mounted.splice(0).forEach(app=>app.unmount());});
async function setup(){
 const current=Vue.ref('phone-a');
 const api={appUsage:vi.fn(async(q:any)=>({overview:{duration_ms:0,count:0,gap_count:0,last_received_at:null},apps:[],daily:[],records:[],total:61,page:q.page,page_size:50})),appUsageDay:vi.fn(async(day:string)=>({day,start_ms:0,end_ms:86400000,total:0,limit:2000,truncated:false,records:[]}))};
 const {descriptor}=parse(readFileSync(new URL('../src/views/AppUsage.vue',import.meta.url),'utf8'));
 const script=compileScript(descriptor,{id:'app-usage-test'});
 const code=ts.transpileModule(script.content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as {default:{setup:Function}}};
 new Function('require','module','exports',code)((id:string)=>{
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
 state.packageName.value='com.unsubmitted';state.from.value='2026-01-01T10:00';state.to.value='2026-01-02T10:00';
 await state.load(2);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:2});expect(api.appUsageDay).toHaveBeenCalledTimes(1);
 await state.load(1);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:1});expect(api.appUsageDay).toHaveBeenCalledTimes(1);
 await state.load();expect(api.appUsage).toHaveBeenLastCalledWith({from:'2026-01-01T02:00:00.000Z',to:'2026-01-02T02:00:00.000Z',package_name:'com.unsubmitted',page:1});
 expect(api.appUsageDay).toHaveBeenLastCalledWith('2026-01-02','com.unsubmitted');
});
it('非法草稿日期也不阻塞已提交查询的翻页',async()=>{
 const {state,api}=await setup();const committed={...api.appUsage.mock.calls[0][0]};state.from.value='';state.to.value='bad';
 await state.load(2);expect(api.appUsage).toHaveBeenLastCalledWith({...committed,page:2});expect(state.error.value).toBe('');
});
it('新增结束原因提供中文名称',async()=>{
 const {state}=await setup();expect(state.reasons.switch).toBe('切换应用');expect(state.reasons.process_restart).toBe('进程重启记录中断');
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

import { readFileSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import * as confirmation from '../src/confirmation';
const settle = async () => { for (let i=0;i<12;i++) await Promise.resolve(); await Vue.nextTick(); };
const mounted: Vue.App[]=[];
afterEach(()=>{ mounted.splice(0).forEach(app=>app.unmount()); confirmation.cancelConfirmation(); vi.unstubAllGlobals(); });
const A={id:'phone-a',name:'手机A',save_uploads:true,last_seen_at:'2026-09-18T01:00:00Z'}, B={...A,id:'phone-b',name:'手机B'};
async function setup(overrides: Record<string,unknown>={}) {
 const current=Vue.ref(A.id), authenticated=Vue.ref(true), route=Vue.reactive({path:'/',fullPath:'/'});
 const api={users:vi.fn(async()=>({users:[A,B],total:2,page:1})), deleteUser:vi.fn(async()=>({deleted_device_id:A.id,files_pending:false})), setUserSaving:vi.fn(async(id:string,save_uploads:boolean)=>({id,save_uploads})),...overrides};
 const source=readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');
 const {descriptor}=parse(source);const compiled=compileScript(descriptor,{id:'device-test'});
 const code=ts.transpileModule(compiled.content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as {default:{setup:Function}}};
 new Function('require','module','exports',code)((id:string)=>{
  if(id==='vue')return Vue;
  if(id==='./api')return {api,currentUserId:current,setCurrentUserId:(id:string)=>current.value=id,deviceLabel:(u:any)=>u.name??u.id};
  if(id==='./auth')return {authenticated,logout:vi.fn(),loginName:Vue.ref('admin')};
  if(id==='vue-router')return {useRoute:()=>route};
  if(id==='./confirmation')return confirmation;
  if(id.endsWith('.vue'))return {default:{render:()=>null}};
  throw Error(id);
 },module,module.exports);
 let state:any;
 const renderer=Vue.createRenderer<any,any>({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){},insert(){},remove(){},insertStaticContent:()=>[{},{}]});
 const app=renderer.createApp({setup(){state=module.exports.default.setup({}, {expose(){}});return ()=>null;}});app.mount({});mounted.push(app);await settle();
 state.openDirectory();await settle();return {state,api,current,authenticated,route};
}
it('目录提供每台手机的保存开关和删除按钮',()=>{
 const source=readFileSync(new URL('../src/App.vue',import.meta.url),'utf8');
 expect(source).toContain('role="switch"');expect(source).toContain('保存上报数据');expect(source).toContain('deleteUser(user)');
});
it('删除先展示目标手机和范围，取消不请求，确认只提交一次',async()=>{
 const {state,api}=await setup();const first=state.deleteUser(B);await settle();
 expect(confirmation.confirmation.value?.message).toContain('手机B');expect(confirmation.confirmation.value?.message).toContain(B.id);
 confirmation.finishConfirmation(false);await first;expect(api.deleteUser).not.toHaveBeenCalled();
 const second=state.deleteUser(B);await state.deleteUser(B);confirmation.finishConfirmation(true);await second;expect(api.deleteUser).toHaveBeenCalledTimes(1);expect(api.deleteUser).toHaveBeenCalledWith(B.id);
});
it('删除当前手机清除旧页面并选择剩余手机',async()=>{
 let deleted=false;const {state,current}=await setup({users:vi.fn(async(q:any)=>({users:deleted?[B]:[A,B],total:deleted?1:2,page:q?.page??1})),deleteUser:vi.fn(async()=>{deleted=true;return {deleted_device_id:A.id,files_pending:false};})});
 const pending=state.deleteUser(A);confirmation.finishConfirmation(true);await pending;expect(current.value).toBe(B.id);expect(state.selectedUser.value.id).toBe(B.id);
});
it('删除失败保留用户并显示服务端错误，关闭开关失败不假装成功',async()=>{
 const {state,current}=await setup({deleteUser:vi.fn().mockRejectedValue(Error('数据库繁忙')),setUserSaving:vi.fn().mockRejectedValue(Error('网络故障'))});
 const pending=state.deleteUser(A);confirmation.finishConfirmation(true);await pending;expect(current.value).toBe(A.id);expect(state.directoryActionError.value).toContain('数据库繁忙');
 const toggle=state.toggleSaving(A);confirmation.finishConfirmation(true);await toggle;expect(state.directoryUsers.value[0].save_uploads).toBe(true);expect(state.directoryActionError.value).toContain('网络故障');
});
it('关闭开关必须确认丢弃后果，成功后才更新状态；开启直接恢复',async()=>{
 const {state,api}=await setup();const pending=state.toggleSaving(A);expect(confirmation.confirmation.value?.message).toContain('不保存');confirmation.finishConfirmation(true);await pending;
 expect(api.setUserSaving).toHaveBeenCalledWith(A.id,false);expect(state.directoryUsers.value[0].save_uploads).toBe(false);
 await state.toggleSaving({...A,save_uploads:false});expect(api.setUserSaving).toHaveBeenLastCalledWith(A.id,true);
});
it('切换用户或关闭目录会取消尚未执行的删除',async()=>{
 const {state,api,current}=await setup();const first=state.deleteUser(A);current.value=B.id;await settle();confirmation.finishConfirmation(true);await first;expect(api.deleteUser).not.toHaveBeenCalled();
 const second=state.deleteUser(A);state.closeDirectory();confirmation.finishConfirmation(true);await second;expect(api.deleteUser).not.toHaveBeenCalled();
});
it('删除当前页末条时回退页码，附件清理延期明确提示',async()=>{
 const users=vi.fn(async(q:any)=>({users:q?.page===2?[A]:[B],total:13,page:q?.page??1}));
 const {state}=await setup({users,deleteUser:vi.fn(async()=>({deleted_device_id:A.id,files_pending:true}))});state.directoryPage.value=2;
 users.mockImplementation(async(q:any)=>({users:[B],total:12,page:q?.page??1}));const pending=state.deleteUser(A);confirmation.finishConfirmation(true);await pending;
 expect(users.mock.calls.some(([q])=>q?.page===1)).toBe(true);expect(state.directoryNotice.value).toContain('重试');
});
it('新增API使用操作目标而不是当前选中用户，错误提示保留',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({deleted_device_id:'target'}))).mockResolvedValueOnce(new Response(JSON.stringify({id:'target',save_uploads:false}))).mockResolvedValueOnce(new Response(JSON.stringify({error:'附件路径异常'}),{status:409}));
 const code=ts.transpileModule(readFileSync(new URL('../src/api/index.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const mod={exports:{} as any};new Function('require','module','exports','localStorage','window',code)((id:string)=>id==='vue'?Vue:{dashboardFetch:fetch},mod,mod.exports,{getItem:()=>A.id},{location:{origin:'http://fixture.local'}});
 await mod.exports.api.deleteUser('target');await mod.exports.api.setUserSaving('target',false);
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/users/target/delete?user_id=target');expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({confirm:'DELETE'});
 expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({save_uploads:false});await expect(mod.exports.api.deleteUser('target')).rejects.toThrow('附件路径异常');
});

it('编辑手机备注不会把已关闭的保存开关显示成开启',async()=>{
 const off={...A,save_uploads:false};const {state}=await setup({users:vi.fn(async()=>({users:[off],total:1,page:1})),updateUser:vi.fn(async()=>({user:{id:A.id,name:'新备注'}}))});
 state.editUser(off);await state.saveUser();expect(state.directoryUsers.value[0].save_uploads).toBe(false);expect(state.selectedUser.value.save_uploads).toBe(false);
});
it('删除尚未结束时切换用户，返回后不得抢回用户选择',async()=>{
 let finish!:(value:any)=>void;const {state,current}=await setup({deleteUser:vi.fn(()=>new Promise(r=>finish=r))});
 const pending=state.deleteUser(A);confirmation.finishConfirmation(true);await settle();current.value=B.id;await settle();finish({deleted_device_id:A.id,files_pending:false});await pending;expect(current.value).toBe(B.id);
});

it('删除或切换开关进行中，既有编辑表单也不能并发提交',async()=>{
 const updateUser=vi.fn(async()=>({user:A}));const {state}=await setup({updateUser});state.editUser(A);state.userAction.value=B.id;await state.saveUser();expect(updateUser).not.toHaveBeenCalled();
});

import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import { CaptureManagement } from '../src/captureManagement';
import { pageCaptureListUrl } from '../src/pageCaptureBrowser';
import { videoVisitListUrl } from '../src/videoVisitBrowser';
const scope={userId:'phone-a',page:1,platform:'douyin',from:'2026-10-01',to:'2026-10-09',q:'备注'};
const rows=[{id:'a',title:'名称',note:'备注'},{id:'b'}];
function deferred<T>(){let resolve!:(v:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve};}
it('两种列表传递日期、关键词及各自类型状态，不混手机',()=>{
 const p=new URL(pageCaptureListUrl({...scope,platform:'douyin',kind:'payment'}),'https://example.com');
 expect(Object.fromEntries(p.searchParams)).toMatchObject({from:scope.from,to:scope.to,q:'备注',user_id:'phone-a',kind:'payment'});
 const v=new URL(videoVisitListUrl({...scope,platform:'douyin',observation_kind:'unconfirmed_feed',complete:'false',exit_reason:'locked'}),'https://example.com');
 expect(Object.fromEntries(v.searchParams)).toMatchObject({observation_kind:'unconfirmed_feed',complete:'false',exit_reason:'locked',q:'备注'});
});
it('只选择当前页，单条与批量均先确认后发冻结作用域请求',async()=>{
 const calls:any[]=[];const m=new CaptureManagement(async(path,user,body)=>{calls.push({path,user,body});return {deleted:2};});m.setScope(scope);m.setRows(rows);
 m.toggle('foreign');expect(m.selectedIds).toEqual([]);m.selectAll();expect(m.selectedIds).toEqual(['a','b']);m.cancelSelection();expect(m.selectedIds).toEqual([]);
 m.toggle('a');m.requestDelete();expect(calls).toEqual([]);expect(m.pendingDelete).toEqual(['a']);expect(await m.confirmDelete()).toBe(true);
 expect(calls[0]).toEqual({path:'delete',user:'phone-a',body:{ids:['a'],confirm:'DELETE'}});
});
it('切手机或筛选废弃旧确认、旧编辑和旧清理预览',async()=>{
 const calls:any[]=[];const m=new CaptureManagement(async(...args)=>{calls.push(args);return {deleted:1};});m.setScope(scope);m.setRows(rows);m.requestDelete('a');m.edit(rows[0]);
 m.setScope({...scope,q:'新筛选'});expect(m.editor).toBeNull();expect(await m.confirmDelete()).toBe(false);expect(calls).toEqual([]);
});
it('旧删除回调不改新手机状态、不要求新手机刷新',async()=>{
 const d=deferred<any>();const m=new CaptureManagement(()=>d.promise);m.setScope(scope);m.setRows(rows);m.requestDelete('a');const old=m.confirmDelete();m.setScope({...scope,userId:'b'});d.resolve({deleted:1});expect(await old).toBe(false);expect(m.message).toBe('');expect(m.busy).toBe(false);
});
it('清理先预览，一次确认按累计游标自动继续且冻结筛选',async()=>{
 const calls:any[]=[];let batches=0;const m=new CaptureManagement(async(path,user,body)=>{calls.push({path,user,body});return path==='cleanup/preview'?{token:'t',total:3,cutoff:'2026-10-02T00:00:00Z'}:++batches===1?{processed:2,total:3,deleted:1,skipped:1,done:false}:{processed:3,total:3,deleted:2,skipped:1,done:true};});m.setScope(scope);m.setRows(rows);
 expect(await m.confirmCleanup()).toBe(false);await m.previewCleanup(7);expect(calls[0]).toEqual({path:'cleanup/preview',user:'phone-a',body:{days:7,platform:'douyin',from:scope.from,to:scope.to,q:'备注'}});
 expect(await m.confirmCleanup()).toBe(true);expect(calls[1].body).toEqual({token:'t',offset:0,confirm:'DELETE'});expect(calls[2].body.offset).toBe(2);expect(m.cleanup).toBeNull();expect(m.message).toContain('删除 2 条，跳过 1 条');
});
it('切手机或筛选后停止后续清理批次，旧回调不改新范围',async()=>{
 const d=deferred<any>();const calls:any[]=[];const m=new CaptureManagement(async(path,user,body)=>{calls.push({path,user,body});return path==='cleanup/preview'?{token:'t',total:3,cutoff:'x'}:d.promise;});m.setScope(scope);await m.previewCleanup(7);const old=m.confirmCleanup();m.setScope({...scope,userId:'b'});d.resolve({processed:2,total:3,deleted:2,skipped:0,done:false});expect(await old).toBe(false);expect(calls.length).toBe(2);expect(m.cleanup).toBeNull();expect(m.message).toBe('');
});
it('停止后续批次保留已处理游标，继续时从当前进度恢复',async()=>{
 const d=deferred<any>();let batches=0;const offsets:number[]=[];const m=new CaptureManagement(async(path,_user,body:any)=>{if(path==='cleanup/preview')return {token:'t',total:3,cutoff:'x'};offsets.push(body.offset);return ++batches===1?d.promise:{processed:3,total:3,deleted:3,skipped:0,done:true};});m.setScope(scope);await m.previewCleanup(7);const running=m.confirmCleanup();m.stopCleanup();d.resolve({processed:2,total:3,deleted:2,skipped:0,done:false});expect(await running).toBe(true);expect(offsets).toEqual([0]);expect(m.cleanup?.offset).toBe(2);await m.confirmCleanup();expect(offsets).toEqual([0,2]);
});
it('清理最后一批成功只更新当前范围，失败可重试并保留预览',async()=>{
 let fail=true;const m=new CaptureManagement(async(path)=>{if(path==='cleanup/preview')return {token:'t',total:1,cutoff:'x'};if(fail)throw Error('失败');return {processed:1,total:1,deleted:1,skipped:0,done:true};});m.setScope(scope);await m.previewCleanup(1);await m.confirmCleanup();expect(m.error).toBe('失败');expect(m.cleanup?.token).toBe('t');fail=false;expect(await m.confirmCleanup()).toBe(true);expect(m.cleanup).toBeNull();
});
it('编辑只提交名称备注，失败保留内容，成功要求刷新',async()=>{
 const calls:any[]=[];let fail=true;const m=new CaptureManagement(async(path,user,body,method)=>{calls.push({path,user,body,method});if(fail)throw Error('保存失败');return {ok:true};});m.setScope(scope);m.setRows(rows);m.edit(rows[0]);m.editor!.title='新名称';m.editor!.note='新备注';expect(await m.saveEdit()).toBe(false);expect(m.editor?.note).toBe('新备注');fail=false;expect(await m.saveEdit()).toBe(true);expect(calls[1]).toEqual({path:'a',user:'phone-a',body:{title:'新名称',note:'新备注'},method:'PATCH'});expect(m.editor).toBeNull();
});

it('累计清理结果不重复相加，旧预览迟到不恢复确认',async()=>{
 let batch=0;const m=new CaptureManagement(async path=>path==='cleanup/preview'?{token:'t',total:3,cutoff:'x'}:++batch===1?{processed:2,total:3,deleted:1,skipped:1,done:false}:{processed:3,total:3,deleted:2,skipped:1,done:true});
 m.setScope(scope);await m.previewCleanup(7);await m.confirmCleanup();expect(m.message).toContain('删除 2 条，跳过 1 条');
 const d=deferred<any>();const old=new CaptureManagement(()=>d.promise);old.setScope(scope);const request=old.previewCleanup(1);old.setScope({...scope,userId:'b'});d.resolve({token:'old',total:3,cutoff:'x'});await request;expect(old.cleanup).toBeNull();
});
it('无手机、卸载后不发送写入请求',async()=>{
 let calls=0;const m=new CaptureManagement(async()=>{calls++;return {deleted:1};});m.setRows(rows);m.requestDelete('a');await m.confirmDelete();await m.previewCleanup(7);expect(calls).toBe(0);m.setScope(scope);m.setRows(rows);m.requestDelete('a');m.invalidate();await m.confirmDelete();expect(calls).toBe(0);
});

it('中间批次失败保留累计进度，重试从失败offset继续',async()=>{
 const offsets:number[]=[];let attempts=0;const m=new CaptureManagement(async(path,_user,body:any)=>{if(path==='cleanup/preview')return {token:'t',total:3,cutoff:'x'};offsets.push(body.offset);if(++attempts===2)throw Error('中途失败');return body.offset===0?{processed:2,total:3,deleted:2,skipped:0,done:false}:{processed:3,total:3,deleted:3,skipped:0,done:true};});m.setScope(scope);await m.previewCleanup(7);expect(await m.confirmCleanup()).toBe(false);expect(m.cleanup?.offset).toBe(2);expect(m.error).toBe('中途失败');expect(await m.confirmCleanup()).toBe(true);expect(offsets).toEqual([0,2,2]);
});

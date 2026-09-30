import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const root = process.env.APP_USAGE_TEST_CLUSTER;
if (root && (!root.startsWith('/tmp/shurufa-app-usage.') || readFileSync(`${root}/test-instance-only`, 'utf8') !== 'app-usage-only')) throw Error('独立实例校验失败');
const test = root ? it : it.skip;
let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const A=randomUUID(), B=randomUUID();
const rec=(overrides={})=>({id:randomUUID(),kind:'usage',package_name:'com.example.app',app_name:'测试',start_ms:Date.parse('2026-09-20T15:30:00Z'),end_ms:Date.parse('2026-09-20T16:30:00Z'),end_reason:'switch',...overrides});
const post=(records:unknown[],id:string=A)=>request(app).post('/api/v1/mobile/app-usage/batch').set('X-Device-Id',id).send({records});
const get=(extra='',id:string=A)=>agent.get(`/api/v1/dashboard/app-usage?user_id=${id}&from=2026-09-20T15:45:00Z&to=2026-09-20T16:15:00Z${extra}`);
beforeAll(async()=>{if(!root)return;pool=new pg.Pool({host:`${root}/socket`,user:'ko',database:'app_usage_test'});
expect((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir).toBe(`${root}/data`);
for(const f of readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort()) await pool.query(readFileSync(new URL(`../../migrations/${f}`,import.meta.url),'utf8'));
app=createApp(pool);agent=await authenticatedRequest(app);
});
afterAll(async()=>{await pool?.end();});
test('校验批大小、正时长、身份、包名和未来时间',async()=>{
 for(const records of [[],Array.from({length:201},()=>rec()),[rec({end_ms:0})],[rec({start_ms:1.1})],[rec({package_name:''})],[rec({kind:'gap',package_name:'bad'})],[rec({end_ms:Date.now()+3600000})]])expect((await post(records)).status).toBe(400);
 expect((await post([rec()],'bad')).status).toBe(400);
});
test('同设备幂等、跨设备隔离、跨北京午夜裁剪、gap不计使用时长',async()=>{
 const row=rec();expect((await post([row,row])).body).toMatchObject({ok:true,received:2});await post([row]);await post([row],B);
 await post([rec({kind:'gap',package_name:null,app_name:null})]);
 const r=await get();expect(r.status).toBe(200);expect(r.body.overview).toMatchObject({duration_ms:1800000,count:1,gap_count:1});expect(r.body.total).toBe(2);
 expect(r.body.daily).toEqual([{day:'2026-09-20',duration_ms:900000},{day:'2026-09-21',duration_ms:900000}]);
 expect(r.body.records.find((v:any)=>v.kind==='usage')).toMatchObject({start_ms:row.start_ms,end_ms:row.end_ms,duration_ms:1800000});
 expect((await get('',B)).body.total).toBe(1);
 expect((await get('&package_name=com.other')).body.overview).toMatchObject({duration_ms:0,count:0,gap_count:1});
});
test('分页不影响统计，范围上限和页码校验',async()=>{
 await post(Array.from({length:51},()=>rec()));const first=await get(), second=await get('&page=2');expect(first.body.records).toHaveLength(50);expect(second.body.records).toHaveLength(3);expect(second.body.overview).toEqual(first.body.overview);
 expect((await get('&page=-1')).status).toBe(400);expect((await agent.get(`/api/v1/dashboard/app-usage?user_id=${A}&from=2024-01-01T00:00:00Z&to=2026-01-01T00:00:00Z`)).status).toBe(400);
});
test('关闭保存依然校验并返回discarded，不影响另一设备，设备删除清理',async()=>{
 await agent.post(`/api/v1/dashboard/users/${A}/saving?user_id=${A}`).send({save_uploads:false});
 const before=(await get()).body.total;expect((await post([rec()])).body).toMatchObject({ok:true,received:1,discarded:true});expect((await post([])).status).toBe(400);expect((await get()).body.total).toBe(before);
 expect((await post([rec()],B)).body.discarded).toBeUndefined();
 expect((await agent.post(`/api/v1/dashboard/users/${A}/delete?user_id=${A}`).send({confirm:'DELETE'})).status).toBe(200);
 expect((await get()).body.total).toBe(0);expect((await get('',B)).body.total).toBe(2);
});

test('导出包含应用使用，全部清理按App隔离且不影响另一设备',async()=>{
 const exported=await agent.get(`/api/v1/dashboard/export?user_id=${B}`);expect(exported.body.counts.app_usage).toBe(2);expect(exported.body.app_usage).toHaveLength(2);
 const cleaned=await agent.post(`/api/v1/dashboard/cleanup?user_id=${B}`).send({confirm:'DELETE',scope:'all',package_name:'com.example.app'});
 expect(cleaned.status).toBe(200);expect(cleaned.body.deleted.app_usage).toBe(2);expect((await get('',B)).body.total).toBe(0);
});

test('全天时间轴独立于分页，按北京时间裁剪，App筛选仍显示断档，隔离设备',async()=>{
 const C=randomUUID(), rows=Array.from({length:55},()=>rec());
 await post([...rows,rec({kind:'gap',package_name:null,app_name:null})],C);
 const url=`/api/v1/dashboard/app-usage/day?user_id=${C}&day=2026-09-21`;
 const result=await agent.get(url);expect(result.status).toBe(200);
 expect(result.body).toMatchObject({day:'2026-09-21',start_ms:Date.parse('2026-09-20T16:00:00Z'),end_ms:Date.parse('2026-09-21T16:00:00Z'),total:56,limit:2000,truncated:false});
 expect(result.body.records).toHaveLength(56);
 expect(result.body.records[0]).toMatchObject({clipped_start_ms:Date.parse('2026-09-20T16:00:00Z'),clipped_end_ms:Date.parse('2026-09-20T16:30:00Z'),duration_ms:1800000});
 expect((await agent.get(`${url}&package_name=com.other`)).body.records).toHaveLength(1);
 expect((await agent.get(url.replace(C,randomUUID()))).body.total).toBe(0);
 for(const day of ['bad','2026-02-30','2026-13-01'])expect((await agent.get(url.replace('2026-09-21',day))).status).toBe(400);
});
test('全天时间轴超过2000段明确提示截断，不把返回条数当作全天总数',async()=>{
 const C=randomUUID();
 await pool.query(`INSERT INTO app_usage_segment(user_id,id,kind,package_name,start_ms,end_ms,end_reason)
  SELECT $1,md5('timeline-'||n)::uuid,'usage','com.test',1789920000000+n*1000,1789920000000+n*1000+500,'pause' FROM generate_series(1,2051) n`,[C]);
 const result=await agent.get(`/api/v1/dashboard/app-usage/day?user_id=${C}&day=2026-09-21`);
 expect(result.status).toBe(200);expect(result.body).toMatchObject({total:2051,limit:2000,truncated:true});expect(result.body.records).toHaveLength(2000);
});

test('最近收到记录独立于时间和App筛选，无记录设备返回null',async()=>{
 const C=randomUUID();await post([rec()],C);
 const received=(await pool.query('SELECT MAX(received_at) AS received FROM app_usage_segment WHERE user_id=$1',[C])).rows[0].received.toISOString();
 const r=await agent.get(`/api/v1/dashboard/app-usage?user_id=${C}&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&package_name=com.none`);
 expect(r.body.overview.last_received_at).toBe(received);
 expect((await get('',randomUUID())).body.overview.last_received_at).toBeNull();
});
test('范围查询拒绝自动进位的无效日历日期',async()=>{
 const r=await agent.get(`/api/v1/dashboard/app-usage?user_id=${A}&from=2026-02-30T00:00:00Z&to=2026-03-05T00:00:00Z`);
 expect(r.status).toBe(400);
});

test('历史妙言自身从汇总明细时间轴排除但不删除，不误伤相似包名',async()=>{
 const C=randomUUID();
 const own=['com.yuyan.pinyin','com.yuyan.pinyin.debug','com.yuyan.pinyin.release','com.yuyan.pinyin.offline','com.yuyan.pinyin.offline.debug','com.yuyan.pinyin.offline.release'];
 await post([...own.map(package_name=>rec({package_name})),rec({package_name:'com.yuyan.pinyin.other'}),rec({kind:'gap',package_name:null,app_name:null})],C);
 const r=await get('',C);expect(r.status).toBe(200);
 expect(r.body.overview).toMatchObject({duration_ms:1800000,count:1,gap_count:1});expect(r.body.total).toBe(2);
 expect(r.body.apps.map((a:any)=>a.package_name)).toEqual(['com.yuyan.pinyin.other']);
 expect(r.body.daily).toEqual([{day:'2026-09-20',duration_ms:900000},{day:'2026-09-21',duration_ms:900000}]);
 expect(r.body.records.filter((a:any)=>a.kind==='usage').map((a:any)=>a.package_name)).toEqual(['com.yuyan.pinyin.other']);
 const url=`/api/v1/dashboard/app-usage/day?user_id=${C}&day=2026-09-21`;
 expect((await agent.get(url)).body.total).toBe(2);
 for(const pkg of own){
  expect((await get('&package_name='+pkg,C)).body.overview).toMatchObject({count:0,duration_ms:0,gap_count:1});
  expect((await agent.get(url+'&package_name='+pkg)).body.records.map((r:any)=>r.kind)).toEqual(['gap']);
 }
 expect(Number((await pool.query('SELECT COUNT(*) AS n FROM app_usage_segment WHERE user_id=$1',[C])).rows[0].n)).toBe(8);
});

const mergeBase=Date.parse('2026-09-29T16:00:00Z');
const part=(s:number,e:number,extra={})=>rec({package_name:'com.tencent.mm',app_name:'微信',start_ms:mergeBase+s,end_ms:mergeBase+e,...extra});
const mergedGet=(id:string,extra='')=>agent.get(`/api/v1/dashboard/app-usage?user_id=${id}&from=2026-09-29T16:00:00Z&to=2026-09-30T16:00:00Z${extra}`);

test('一分钟内相邻同App四段合并展示，间隔不计时且原始统计时间轴和数据库保留',async()=>{
 const C=randomUUID();
 const rows=[part(-28000,69000),part(70000,81000),part(102000,110000),part(136000,276000)];
 expect((await post(rows,C)).status).toBe(200);
 const result=await mergedGet(C);expect(result.status).toBe(200);
 expect(result.body.total).toBe(1);
 expect(result.body.records).toHaveLength(1);
 expect(result.body.records[0]).toMatchObject({start_ms:mergeBase-28000,end_ms:mergeBase+276000,duration_ms:228000,segment_count:4});
 expect(result.body.overview).toMatchObject({count:4,duration_ms:228000,gap_count:0});
 expect(result.body.apps[0]).toMatchObject({count:4,duration_ms:228000});
 expect(result.body.daily).toEqual([{day:'2026-09-30',duration_ms:228000}]);
 const day=await agent.get(`/api/v1/dashboard/app-usage/day?user_id=${C}&day=2026-09-30`);
 expect(day.body.total).toBe(4);
 expect(Number((await pool.query('SELECT COUNT(*) AS n FROM app_usage_segment WHERE user_id=$1',[C])).rows[0].n)).toBe(4);
 expect((await mergedGet(randomUUID())).body.total).toBe(0);
});
test('恰好60秒可合并，超过60秒或有重叠不合并',async()=>{
 const C=randomUUID();await post([part(0,10000),part(70000,80000),part(140001,150001),part(145000,155000)],C);
 const result=await mergedGet(C);expect(result.status).toBe(200);
 expect(result.body.total).toBe(3);
 expect(result.body.records.map((r:any)=>r.segment_count)).toEqual([1,1,2]);
});
test('筛选前检查其他App与隐藏的自身App，不能跳过中间记录误合并',async()=>{
 for(const middle of ['com.other','com.yuyan.pinyin.offline.debug']){
  const C=randomUUID();await post([part(0,10000),part(11000,12000,{package_name:middle}),part(13000,23000)],C);
  const result=await mergedGet(C,'&package_name=com.tencent.mm');
  expect(result.status).toBe(200);expect(result.body.total).toBe(2);
  expect(result.body.records.map((r:any)=>r.segment_count)).toEqual([1,1]);
 }
});
test('锁屏熄屏和采集中断不能与之后的同App合并，gap也阻断',async()=>{
 for(const reason of ['lock','off','shutdown','reboot','permission_lost','collection_paused','clock_changed','history_gap','process_restart']){
  const C=randomUUID();await post([part(0,10000,{end_reason:reason}),part(15000,25000)],C);
  expect((await mergedGet(C)).body.total).toBe(2);
 }
 const C=randomUUID();await post([part(0,10000),part(11000,12000,{kind:'gap',package_name:null,app_name:null}),part(13000,23000)],C);
 expect((await mergedGet(C)).body.total).toBe(3);
});
test('与更早的长段重叠时不因紧邻同App误并',async()=>{
 const C=randomUUID();await post([part(0,50000,{package_name:'com.other'}),part(10000,20000),part(21000,30000)],C);
 expect((await mergedGet(C,'&package_name=com.tencent.mm')).body.total).toBe(2);
});
test('早先长段占据两段间隙时也必须阻断，不能越过其他App或锁屏',async()=>{
 for(const end_reason of ['switch','lock']){
  for(const previousEnd of [20000,50000]){
  const C=randomUUID();await post([part(0,50000,{package_name:'com.other',end_reason}),part(10000,previousEnd),part(55000,65000)],C);
  const result=await mergedGet(C,'&package_name=com.tencent.mm');
  expect(result.status).toBe(200);expect(result.body.total).toBe(2);
  }
 }
});
test('当前段与后续其他App重叠时也不参与合并',async()=>{
 const C=randomUUID();await post([part(0,10000),part(20000,50000),part(30000,40000,{package_name:'com.other'})],C);
 expect((await mergedGet(C,'&package_name=com.tencent.mm')).body.total).toBe(2);
});
test('合并先于分页，统计不受页码影响且不能把跨原始分页的组合拆开',async()=>{
 const C=randomUUID();const rows=[];
 for(let i=0;i<51;i++){rows.push(part(i*200000,i*200000+10000),part(i*200000+20000,i*200000+30000));}
 expect((await post(rows,C)).status).toBe(200);
 const first=await mergedGet(C),second=await mergedGet(C,'&page=2');
 expect(first.status).toBe(200);expect(first.body.total).toBe(51);expect(first.body.records).toHaveLength(50);
 expect(second.body.total).toBe(51);expect(second.body.records).toHaveLength(1);
 expect([...first.body.records,...second.body.records].every((r:any)=>r.segment_count===2&&r.duration_ms===20000)).toBe(true);
 expect(second.body.overview).toEqual(first.body.overview);
 expect(first.body.overview).toMatchObject({count:102,duration_ms:1020000});
 expect(new Set([...first.body.records,...second.body.records].map((r:any)=>r.id)).size).toBe(51);
});

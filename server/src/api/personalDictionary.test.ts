import { readFileSync, existsSync } from 'node:fs';
import { newDb } from 'pg-mem';
import pg from 'pg';
import request from 'supertest';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const A = '00000000-0000-4000-8000-00000000000a', B = '00000000-0000-4000-8000-00000000000b';
const token = 'a'.repeat(64);
const databaseUrl=process.env.DICTIONARY_TEST_DATABASE_URL;
if(databaseUrl && !['localhost','127.0.0.1','[::1]'].includes(new URL(databaseUrl).hostname)) throw new Error('Dictionary tests require localhost');
let testSchema: string | undefined;
let pool: pg.Pool, app: ReturnType<typeof createApp>;
const choice = (text = '充电宝', count = 3) => ({ kind: 'choice', text, code: '2466434262', pinyin: '', source: 'selection', count, weight: count, last_used: 1000 });
const word = { kind: 'word', text: '充电宝', code: '', pinyin: 'chong dian bao', source: 'selection', count: 0, weight: 0, last_used: 0 };
const mobile = (id: string, method: 'get'|'post', path: string) => request(app)[method](`/api/v1/mobile/dictionary${path}`).set('X-Device-Id', id).set('X-Dictionary-Token', token);
const upload = (id: string, entries = [choice()], sequence = 1) => mobile(id,'post','/report').send({sequence,entries,migration_status:'complete',imported:1});
const dash = () => authenticatedRequest(app);
beforeEach(async () => {
  if (process.env.DICTIONARY_TEST_DATABASE_URL) {
    const schema='dict_'+crypto.randomUUID().replaceAll('-','');
    testSchema=schema;
    const bootstrap=new pg.Pool({connectionString:process.env.DICTIONARY_TEST_DATABASE_URL});
    await bootstrap.query(`CREATE SCHEMA ${schema}`);await bootstrap.end();
    pool=new pg.Pool({connectionString:process.env.DICTIONARY_TEST_DATABASE_URL,options:`-c search_path=${schema}`});
  } else pool = new (newDb().adapters.createPg().Pool)();
  await pool.query('CREATE TABLE device(id UUID PRIMARY KEY, name TEXT, model TEXT, brand TEXT, dashboard_name TEXT)');
  await pool.query('INSERT INTO device(id,name) VALUES($1,$3),($2,$4)', [A,B,'旧手机','新手机']);
  const path = new URL('../../migrations/016_personal_dictionary.sql', import.meta.url);
  if (existsSync(path)) await pool.query(readFileSync(path,'utf8'));
  const rolePath=new URL('../../migrations/017_dictionary_target_role.sql',import.meta.url);
  if(existsSync(rolePath)) await pool.query(readFileSync(rolePath,'utf8'));
  const additionsPath=new URL('../../migrations/023_dictionary_additions.sql',import.meta.url);
  if(existsSync(additionsPath)) await pool.query(readFileSync(additionsPath,'utf8'));
  const habitsPath=new URL('../../migrations/025_dictionary_habits.sql',import.meta.url);
  if(existsSync(habitsPath)) await pool.query(readFileSync(habitsPath,'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/026_dictionary_short_codes.sql',import.meta.url),'utf8'));
  app = createApp(pool);
});
afterEach(async () => {
  try { if(testSchema) await pool.query(`DROP SCHEMA ${testSchema} CASCADE`); }
  finally { testSchema=undefined; await pool.end(); }
});
describe('个人词库后台绑定与同步', () => {
  it('仅备份目标能查看上报但不得假装管理决策会应用到手机', async () => {
    expect((await mobile(A,'post','/register').send({restore_enabled:false})).status).toBe(200);
    await upload(A);
    const admin=await dash();
    const devices=(await admin.get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices;
    expect(devices[0].restore_enabled).toBe(false);
    expect((await admin.get(`/api/v1/dashboard/dictionary/entries?user_id=${A}`)).body.total).toBe(1);
    expect((await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${A}`).send({texts:['充电宝'],status:'deleted'})).status).toBe(409);
    await mobile(B,'post','/register').send({});
    expect((await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${B}`).send({device_id:A})).status).toBe(409);
  });
  it('目标从主控切为备份再切回时旧确认不得冒充本次应用', async () => {
    await mobile(A,'post','/register').send({});await upload(A);
    const revision=(await mobile(A,'get','')).body.revision;
    await mobile(A,'post','/ack').send({revision});
    await mobile(A,'post','/register').send({restore_enabled:false});
    await mobile(A,'post','/register').send({restore_enabled:true});
    const devices=(await (await dash()).get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices;
    expect(devices[0].synced).toBe(false);
  });
  it('合并视图按词去重但来源与真实次数可追溯', async () => {
    for(const id of [A,B]) await mobile(id,'post','/register').send({});
    await upload(A,[choice(),word]); await upload(B,[choice('充电宝',2)]);
    const admin=await dash();await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B});
    const result=await admin.get(`/api/v1/dashboard/dictionary/entries?user_id=${A}&view=merged`);
    expect(result.body.total).toBe(1);
    expect(result.body.entries[0]).toMatchObject({text:'充电宝',count:5,pinyin:'chong dian bao',device_ids:[A,B],sources:['selection'],has_choices:true});
  });
  it('绑定合并时删除决策不能被目标启用覆盖', async () => {
    for(const id of [A,B]) { await mobile(id,'post','/register').send({}); await upload(id); }
    const admin=await dash();
    await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${B}`).send({texts:['充电宝'],status:'deleted'});
    await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${A}`).send({texts:['充电宝'],status:'enabled'});
    await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B});
    expect((await mobile(A,'get','')).body.policies).toEqual([{text:'充电宝',status:'deleted'}]);
  });
  it.runIf(Boolean(process.env.DICTIONARY_TEST_DATABASE_URL))('真实 PostgreSQL 冲突批次原子回滚并且迁移可重复', async () => {
    await pool.query(readFileSync(new URL('../../migrations/016_personal_dictionary.sql',import.meta.url),'utf8'));
    await mobile(A,'post','/register').send({});await upload(A,[choice()],2);
    const result=await upload(A,[choice('怎么'),choice('充电宝',99)],2);
    expect(result.status).toBe(409);
    expect((await mobile(A,'get','')).body.entries.map((e:any)=>e.text)).toEqual(['充电宝']);
  });
  it('设备 ID 不能绕过词库凭据', async () => {
    expect((await mobile(A,'post','/register').send({})).status).toBe(200);
    expect((await request(app).get('/api/v1/mobile/dictionary').set('X-Device-Id',A)).status).toBe(401);
    expect((await request(app).post('/api/v1/mobile/dictionary/register').set('X-Device-Id',A).set('X-Dictionary-Token','b'.repeat(64)).send({})).status).toBe(401);
  });
  it('未绑定不共享，绑定保留来源且重复上报不加权', async () => {
    for (const id of [A,B]) await mobile(id,'post','/register').send({});
    expect((await upload(A,[choice(),word])).status).toBe(200);
    await upload(B,[choice('充电宝',2)]);
    expect((await mobile(B,'get','')).body.entries).toHaveLength(1);
    const admin = await dash();
    expect((await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B})).status).toBe(200);
    const entries = (await mobile(B,'get','')).body.entries;
    expect(entries).toHaveLength(3);
    expect(new Set(entries.map((e: any) => e.device_id))).toEqual(new Set([A,B]));
    await upload(A,[choice(),word]);
    expect((await mobile(B,'get','')).body.entries.filter((e: any) => e.kind==='choice').map((e: any) => e.count).sort()).toEqual([2,3]);
    const rows = await admin.get(`/api/v1/dashboard/dictionary/entries?user_id=${A}&device_id=${B}`);
    expect(rows.body.total).toBe(1); expect(rows.body.entries[0].device_id).toBe(B);
  });
  it('旧批次不回退计数，同版本不同内容拒绝', async () => {
    await mobile(A,'post','/register').send({});
    await upload(A,[choice('充电宝',5)],2); await upload(A,[choice('充电宝',1)],1);
    expect((await mobile(A,'get','')).body.entries[0].count).toBe(5);
    expect((await upload(A,[choice('充电宝',9)],2)).status).toBe(409);
  });
  it('删除保留原始明细，重上报不复活，明确恢复才启用', async () => {
    await mobile(A,'post','/register').send({}); await upload(A);
    const admin = await dash();
    expect((await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${A}`).send({texts:['充电宝'],status:'deleted'})).status).toBe(200);
    await upload(A,[choice('充电宝',4)],2);
    let snapshot = (await mobile(A,'get','')).body;
    expect(snapshot.entries).toHaveLength(1); expect(snapshot.policies).toEqual([{text:'充电宝',status:'deleted'}]);
    await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${A}`).send({texts:['充电宝'],status:'enabled'});
    snapshot = (await mobile(A,'get','')).body; expect(snapshot.policies[0].status).toBe('enabled');
  });
  it('手机应用确认必须对应当前版本，后台区分上报与应用', async () => {
    await mobile(A,'post','/register').send({}); await upload(A);
    const revision = (await mobile(A,'get','')).body.revision;
    expect((await mobile(A,'post','/ack').send({revision})).status).toBe(200);
    const admin = await dash();
    let devices = (await admin.get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices;
    expect(devices[0].synced).toBe(true);
    await admin.post(`/api/v1/dashboard/dictionary/decisions?user_id=${A}`).send({texts:['充电宝'],status:'disabled'});
    expect((await mobile(A,'post','/ack').send({revision})).status).toBe(409);
    devices = (await admin.get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices;
    expect(devices[0].synced).toBe(false);
  });
  it('拒绝未登录管理、跨组明细、无效与敏感条目', async () => {
    await mobile(A,'post','/register').send({}); await mobile(B,'post','/register').send({});
    expect((await request(app).post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B})).status).toBe(401);
    expect((await (await dash()).get(`/api/v1/dashboard/dictionary/entries?user_id=${A}&device_id=${B}`)).status).toBe(403);
    expect((await upload(A,[{...choice(),weight:-1}])).status).toBe(400);
    expect((await upload(A,[choice('密码')])).status).toBe(400);
    expect((await upload(A,[{...choice(),code:93663 as any}])).status).toBe(400);
  });
});

describe('指定手机纯加法词库', () => {
  const endpoint = (path: string) => `/api/v1/dashboard/dictionary${path}?user_id=${A}`;
  const setup = async () => {
    await mobile(A,'post','/register').send({restore_enabled:false,additions_supported:true});
    await mobile(B,'post','/register').send({});
    return dash();
  };
  it('备份后台可规范化手工添加，不伪造来源次数并拒绝错读音', async () => {
    const admin=await setup();
    expect((await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:' TAI  LING '})).body).toEqual({ok:true,created:true});
    expect((await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'})).body).toEqual({ok:true,created:false});
    for(const pinyin of ['tai long','foo bar','tai']) expect((await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin})).status).toBe(400);
    const entries=(await admin.get(endpoint('/entries'))).body.entries;
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({device_id:'',kind:'word',source:'dashboard',count:0,weight:0,pinyin:'tai ling'});
    expect((await mobile(A,'get','')).body.entries).toEqual([]);
    expect((await admin.post(endpoint('/decisions')).send({texts:['泰鲮'],status:'deleted'})).status).toBe(409);
  });
  it('跨组只追加指定手机，重复排队不加次数，旧端显示待升级', async () => {
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    const payload={device_ids:[B],texts:['泰鲮']};
    expect((await admin.post(endpoint('/sync')).send(payload)).body).toEqual({ok:true,words:1,queued:1,skipped:0,devices:1});
    expect((await admin.post(endpoint('/sync')).send(payload)).body.queued).toBe(0);
    expect((await mobile(A,'get','/additions?after=0')).body.entries).toEqual([]);
    const received=(await mobile(B,'get','/additions?after=0')).body;
    expect(received.entries).toEqual([{cursor:received.cursor,text:'泰鲮',pinyin:'tai ling',preferred:true}]);
    expect(received.has_more).toBe(false);
    const devices=(await admin.get(endpoint('/devices'))).body.devices;
    expect(devices.find((d:any)=>d.device_id===B)).toMatchObject({in_group:false,additions_supported:false,additions_pending:1,additions_applied_at:null});
    expect((await pool.query('SELECT * FROM dictionary_entry')).rows).toHaveLength(0);
  });
  it('游标确认只允许已下发给当前手机的批次，幂等单调且鉴权不放松', async () => {
    const admin=await setup();
    expect((await mobile(B,'post','/register').send({additions_supported:true})).body.additions_supported).toBe(true);
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    await admin.post(endpoint('/sync')).send({device_ids:[B],all:true});
    expect((await mobile(B,'post','/additions/ack').send({cursor:1})).status).toBe(409);
    const batch=(await mobile(B,'get','/additions?after=0')).body;
    expect((await mobile(A,'post','/additions/ack').send({cursor:batch.cursor})).status).toBe(409);
    expect((await mobile(B,'post','/additions/ack').send({cursor:batch.cursor+1})).status).toBe(409);
    for(let i=0;i<2;i++) expect((await mobile(B,'post','/additions/ack').send({cursor:batch.cursor})).status).toBe(200);
    expect((await mobile(B,'get',`/additions?after=${batch.cursor}`)).body).toEqual({entries:[],cursor:batch.cursor,has_more:false});
    const target=(await admin.get(endpoint('/devices'))).body.devices.find((d:any)=>d.device_id===B);
    expect(target.additions_pending).toBe(0); expect(target.additions_applied_at).not.toBeNull();
    for(const after of ['-1','1.5','9007199254740992','abc']) expect((await mobile(B,'get',`/additions?after=${after}`)).status).toBe(400);
    expect((await request(app).get('/api/v1/mobile/dictionary/additions?after=0').set('X-Device-Id',B)).status).toBe(401);
  });
  it('全部匹配跨页同步，过滤不泄漏其他组或下发无读音/停用词', async () => {
    const admin=await setup();
    const {pinyin}=await import('pinyin-pro');
    const texts=Array.from({length:55},(_,i)=>'词'+String.fromCharCode(0x4e00+i));
    const values=texts.map(text=>({...word,text,pinyin:pinyin(text,{toneType:'none'})}));
    await upload(A,values);
    await upload(A,[choice('怎么')],2);
    await pool.query("INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,'disabled')",[A,texts[0]]);
    expect((await admin.get(endpoint('/entries'))).body.entries).toHaveLength(50);
    const result=await admin.post(endpoint('/sync')).send({device_ids:[B],all:true});
    expect(result.body).toMatchObject({words:54,queued:54,skipped:2});
    expect((await mobile(B,'get','/additions?after=0')).body.entries).toHaveLength(54);
    expect((await admin.post(endpoint('/sync')).send({device_ids:[B],all:true,filter:{device_id:B}})).status).toBe(403);
    expect((await admin.post(endpoint('/sync')).send({device_ids:[B],all:true,texts:['词一']})).status).toBe(400);
  });
  it('目标停用规则不能被追加复活，后台词绑定后仍可见',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    await pool.query("INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,'disabled')",[B,'泰鲮']);
    expect((await admin.post(endpoint('/sync')).send({device_ids:[B],all:true})).body).toMatchObject({queued:0,skipped:1});
    await mobile(A,'post','/register').send({restore_enabled:true});
    await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${B}`).send({device_id:A});
    expect((await admin.get(`/api/v1/dashboard/dictionary/entries?user_id=${B}`)).body.entries[0]).toMatchObject({text:'泰鲮',source:'dashboard',status:'disabled'});
  });
  it('普通手机来源不优先，手工确认后重投递但不重复词条',async()=>{
    const admin=await setup(); await upload(A,[word]);
    const payload={device_ids:[B],all:true};
    await admin.post(endpoint('/sync')).send(payload);
    const initial=(await mobile(B,'get','/additions?after=0')).body;
    expect(initial.entries[0].preferred).toBe(false);
    await mobile(B,'post','/additions/ack').send({cursor:initial.cursor});
    await admin.post(endpoint('/words')).send({text:word.text,pinyin:word.pinyin});
    expect((await admin.post(endpoint('/sync')).send(payload)).body.queued).toBe(1);
    const promoted=(await mobile(B,'get',`/additions?after=${initial.cursor}`)).body;
    expect(promoted.cursor).toBeGreaterThan(initial.cursor);
    expect(promoted.entries).toEqual([{cursor:promoted.cursor,text:word.text,pinyin:word.pinyin,preferred:true}]);
    expect((await admin.post(endpoint('/sync')).send(payload)).body.queued).toBe(0);
    expect((await pool.query('SELECT * FROM dictionary_addition')).rows).toHaveLength(1);
  });
  it('每批最多500条，后续页只确认真实下发且不截掉剩余项',async()=>{
    const admin=await setup();
    for(let i=0;i<501;i++) await pool.query('INSERT INTO dictionary_addition(device_id,text,pinyin) VALUES($1,$2,$3)',[B,'词'+String.fromCharCode(0x4e00+i),'ci yi']);
    const first=(await mobile(B,'get','/additions?after=0')).body;
    expect(first.entries).toHaveLength(500); expect(first.has_more).toBe(true);
    const last=(await pool.query('SELECT cursor FROM dictionary_addition WHERE device_id=$1 ORDER BY cursor DESC LIMIT 1',[B])).rows[0];
    expect((await mobile(B,'post','/additions/ack').send({cursor:Number(last.cursor)})).status).toBe(409);
    expect((await mobile(B,'get',`/additions?after=${last.cursor}`)).status).toBe(409);
    await mobile(B,'post','/additions/ack').send({cursor:first.cursor});
    const second=(await mobile(B,'get',`/additions?after=${first.cursor}`)).body;
    expect(second.entries).toHaveLength(1); expect(second.has_more).toBe(false);
    await mobile(B,'post','/additions/ack').send({cursor:second.cursor});
    expect((await admin.get(endpoint('/devices'))).body.devices.find((d:any)=>d.device_id===B).additions_pending).toBe(0);
  });
  it('拒绝超过500条选择、无目标和未注册手机，保留已排队记录',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    for(const body of [{device_ids:[],all:true},{device_ids:[B],texts:Array(501).fill('泰鲮')},{device_ids:[B],all:false},{device_ids:[B],all:true,filter:[]}]) {
      expect((await admin.post(endpoint('/sync')).send(body)).status).toBe(400);
    }
    expect((await admin.post(endpoint('/sync')).send({device_ids:['00000000-0000-4000-8000-00000000000c'],all:true})).status).toBe(404);
    await admin.post(endpoint('/sync')).send({device_ids:[B],all:true});
    await admin.post(endpoint('/sync')).send({device_ids:[B],all:true,filter:{q:'不存在'}});
    expect((await mobile(B,'get','/additions?after=0')).body.entries).toHaveLength(1);
  });
  it.runIf(Boolean(databaseUrl))('真实事务：容量溢出回滚全部目标与序号变更，023重复迁移不丢词',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    await pool.query("INSERT INTO dictionary_addition(device_id,text,pinyin) SELECT $1,'保留'||n,'bao liu' FROM generate_series(1,100000) n",[B]);
    expect((await admin.post(endpoint('/sync')).send({device_ids:[A,B],all:true})).status).toBe(413);
    expect((await pool.query('SELECT COUNT(*) AS n FROM dictionary_addition WHERE device_id=$1',[A])).rows[0].n).toBe('0');
    expect((await pool.query('SELECT COUNT(*) AS n FROM dictionary_addition WHERE device_id=$1',[B])).rows[0].n).toBe('100000');
    await pool.query(readFileSync(new URL('../../migrations/023_dictionary_additions.sql',import.meta.url),'utf8'));
    expect((await admin.get(endpoint('/entries'))).body.entries[0].text).toBe('泰鲮');
  });

  it('较小游标也不能冒充其他设备的已下发确认',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    await admin.post(endpoint('/sync')).send({device_ids:[A,B],all:true});
    const first=(await mobile(A,'get','/additions?after=0')).body;
    const second=(await mobile(B,'get','/additions?after=0')).body;
    await mobile(B,'post','/additions/ack').send({cursor:second.cursor});
    expect((await mobile(B,'post','/additions/ack').send({cursor:first.cursor})).status).toBe(409);
  });

  it('纯手工合并行保留来源且不宣称存在选词次数',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    const merged=(await admin.get(endpoint('/entries')+'&view=merged')).body.entries[0];
    expect(merged).toMatchObject({sources:['dashboard'],has_choices:false,count:0});
  });
  it('全部目标都禁用才算策略跳过，重复已发送不算跳过',async()=>{
    const admin=await setup();
    await admin.post(endpoint('/words')).send({text:'泰鲮',pinyin:'tai ling'});
    await pool.query("INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,'disabled')",[B,'泰鲮']);
    for(const queued of [1,0]) expect((await admin.post(endpoint('/sync')).send({device_ids:[A,B],all:true})).body).toMatchObject({queued,skipped:0});
  });
  it('下载游标不属于当前服务端进度时提供专用复位代码',async()=>{
    await setup();
    const response=await mobile(A,'get','/additions?after=123');
    expect(response.status).toBe(409);
    expect(response.body.code).toBe('dictionary_cursor_reset');
    expect((await mobile(A,'post','/additions/ack').send({cursor:123})).body.code).toBeUndefined();
  });

});

describe('真实习惯增量投递',()=>{
  it('全量同步独立投递真实来源且重复按钮和旧上报不制造次数',async()=>{
    await mobile(A,'post','/register').send({habits_supported:true});
    await mobile(B,'post','/register').send({habits_supported:true});
    await upload(A,[choice(),word],2);
    const admin=await dash();
    const sync=()=>admin.post(`/api/v1/dashboard/dictionary/sync-all?user_id=${A}`).send({device_ids:[B]});
    const first=await sync();expect(first.status).toBe(200);
    expect(first.body).toMatchObject({habits:1,habits_queued:1,words:1});
    expect((await sync()).body.habits_queued).toBe(0);
    expect((await mobile(A,'get','/habits?after=0')).body.entries).toEqual([]);
    const page=(await mobile(B,'get','/habits?after=0')).body;
    expect(page.entries[0]).toMatchObject({...choice(),device_id:A,version:2});
    expect((await mobile(B,'post','/habits/ack').send({cursor:page.cursor+1})).status).toBe(409);
    expect((await mobile(B,'post','/habits/ack').send({cursor:page.cursor})).status).toBe(200);
    await upload(A,[choice('充电宝',1)],1);
    expect((await sync()).body.habits_queued).toBe(0);
    await upload(A,[choice('充电宝',5)],3);
    expect((await sync()).body.habits_queued).toBe(1);
    expect((await mobile(B,'get',`/habits?after=${page.cursor}`)).body.entries[0].count).toBe(5);
  });
  it.runIf(Boolean(databaseUrl))('习惯分页和确认只属于目标设备，迁移重复不清数据',async()=>{
    for(const id of [A,B]) await mobile(id,'post','/register').send({habits_supported:true});
    const values:Array<unknown>=[];
    const tuples=Array.from({length:501},(_,i)=>{
      const text='词'+String.fromCharCode(0x4e00+i),v=choice(text);
      const start=values.length;values.push(B,A,v.code,text,1,JSON.stringify(v));
      return '('+Array.from({length:6},(_,j)=>'$'+(start+j+1)).join(',')+')';
    });
    await pool.query('INSERT INTO dictionary_habit(device_id,source_device_id,code,text,version,payload) VALUES '+tuples.join(','),values);
    await pool.query(readFileSync(new URL('../../migrations/025_dictionary_habits.sql',import.meta.url),'utf8'));
    const first=(await mobile(B,'get','/habits?after=0')).body;
    expect(first.entries).toHaveLength(500);expect(first.has_more).toBe(true);
    expect((await mobile(A,'post','/habits/ack').send({cursor:first.cursor})).status).toBe(409);
    expect((await mobile(B,'get',`/habits?after=${first.cursor+1}`)).body.code).toBe('dictionary_cursor_reset');
    expect((await mobile(B,'post','/habits/ack').send({cursor:first.cursor})).status).toBe(200);
    const second=(await mobile(B,'get',`/habits?after=${first.cursor}`)).body;
    expect(second.entries).toHaveLength(1);expect(second.has_more).toBe(false);
    expect((await mobile(B,'post','/habits/ack').send({cursor:second.cursor})).status).toBe(200);
    expect((await mobile(B,'post','/habits/ack').send({cursor:first.cursor})).status).toBe(200);
    expect(Number((await pool.query('SELECT habits_ack FROM dictionary_device WHERE device_id=$1',[B])).rows[0].habits_ack)).toBe(second.cursor);
  });
  it('旧手机不声明习惯能力不能伪确认且敏感和停用词不得投递',async()=>{
    await mobile(A,'post','/register').send({habits_supported:true});
    await mobile(B,'post','/register').send({});await upload(A);
    const admin=await dash();
    expect((await admin.post(`/api/v1/dashboard/dictionary/sync-all?user_id=${A}`).send({device_ids:[B]})).status).toBe(200);
    expect((await mobile(B,'get','/habits?after=0')).status).toBe(409);
    expect((await mobile(B,'post','/habits/ack').send({cursor:0})).status).toBe(409);
    const devices=(await admin.get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices;
    expect(devices.find((d:any)=>d.device_id===B)).toMatchObject({habits_supported:false,habits_pending:1});
    expect((await upload(A,[choice('验证码')],4)).status).toBe(400);
  });
});

it('一两键选择备份保留编码并重复上报不增次数',async()=>{
 await mobile(A,'post','/register').send({short_codes_supported:true});
 const entries=['3','62'].map(code=>({...choice('的',1),code}));
 expect((await upload(A,entries)).status).toBe(200);
 expect((await upload(A,entries)).status).toBe(200);
 const snapshot=(await mobile(A,'get','')).body;
 expect(snapshot.entries.map((e:any)=>[e.code,e.count]).sort()).toEqual([['3',1],['62',1]]);
});
it('旧手机过滤短码但可正常确认快照和习惯，新版升级后可重放短码',async()=>{
 await mobile(A,'post','/register').send({short_codes_supported:true,habits_supported:true});
 await mobile(B,'post','/register').send({habits_supported:true});
 const short={...choice('的',1),code:'3'};
 await upload(A,[short,choice()]);
 const admin=await dash();
 await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B});
 const legacy=(await mobile(B,'get','')).body;
 expect(legacy.entries.some((e:any)=>e.code==='3')).toBe(false);
 expect((await mobile(B,'post','/ack').send({revision:legacy.revision})).status).toBe(200);
 expect((await admin.get(`/api/v1/dashboard/dictionary/devices?user_id=${A}`)).body.devices.find((d:any)=>d.device_id===B).synced).toBe(true);
 await pool.query('INSERT INTO dictionary_habit(device_id,source_device_id,code,text,version,payload) VALUES($1,$2,$3,$4,$5,$6)',[B,A,'3','的',1,JSON.stringify(short)]);
 await pool.query('INSERT INTO dictionary_habit(device_id,source_device_id,code,text,version,payload) VALUES($1,$2,$3,$4,$5,$6)',[B,A,choice().code,choice().text,1,JSON.stringify(choice())]);
 const oldPage=(await mobile(B,'get','/habits?after=0')).body;
 expect(oldPage.entries.map((e:any)=>e.code)).toEqual([choice().code]);
 expect((await mobile(B,'post','/habits/ack').send({cursor:oldPage.cursor})).status).toBe(200);
 await mobile(B,'post','/register').send({short_codes_supported:true,habits_supported:true});
 expect((await mobile(B,'get','')).body.entries.some((e:any)=>e.code==='3')).toBe(true);
 expect((await mobile(B,'get','/habits?after=0')).body.entries.map((e:any)=>e.code)).toEqual(['3',choice().code]);
});

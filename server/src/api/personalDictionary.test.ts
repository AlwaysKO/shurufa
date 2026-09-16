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
  app = createApp(pool);
});
afterEach(async () => {
  try { if(testSchema) await pool.query(`DROP SCHEMA ${testSchema} CASCADE`); }
  finally { testSchema=undefined; await pool.end(); }
});
describe('个人词库后台绑定与同步', () => {
  it('合并视图按词去重但来源与真实次数可追溯', async () => {
    for(const id of [A,B]) await mobile(id,'post','/register').send({});
    await upload(A,[choice(),word]); await upload(B,[choice('充电宝',2)]);
    const admin=await dash();await admin.post(`/api/v1/dashboard/dictionary/bind?user_id=${A}`).send({device_id:B});
    const result=await admin.get(`/api/v1/dashboard/dictionary/entries?user_id=${A}&view=merged`);
    expect(result.body.total).toBe(1);
    expect(result.body.entries[0]).toMatchObject({text:'充电宝',count:5,pinyin:'chong dian bao',device_ids:[A,B]});
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

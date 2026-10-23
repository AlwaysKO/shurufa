import { readFileSync, realpathSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.CHAT_UNLINKED_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-unlinked.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'chat-unlinked-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
const a = 'aaaaaaaa-0000-4000-8000-000000000001', b = 'bbbbbbbb-0000-4000-8000-000000000002';
let pool: pg.Pool, root: string, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const config = () => ({host: join(cluster!, 'socket'), port:5432, user:process.env.USER || 'ubuntu', database:'chat_unlinked_test'});
const path = `/api/v1/dashboard/chat/unlinked-images?user_id=${a}`;
async function asset(user = a, time = '2026-10-22T00:00:00Z', mime = 'image/png') {
  const hash = randomUUID().replaceAll('-', '').padEnd(64, '0');
  return (await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size,created_at)
    VALUES($1,$2,$3,$4,68,$5) RETURNING *`,[user,hash,mime,`chat/${hash}.png`,time])).rows[0];
}
async function link(assetId: string) {
  const c=(await pool.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence)
    VALUES($1,'wechat','fixture',$2,'direct',1) RETURNING id`,[a,randomUUID()])).rows[0];
  const id=randomUUID();
  await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at)
    VALUES($1,$2,$2,$3,'wechat',$4,$4,'fixture','system','image',NOW())`,[id,a,c.id,randomUUID().replaceAll('-','').padEnd(64,'0')]);
  await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)',[id,assetId]);
}
beforeAll(async()=>{
  if(!cluster)return;
  pool=new pg.Pool(config());
  const identity=(await pool.query("SELECT current_setting('data_directory') dir,current_database() db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));expect(identity.db).toBe('chat_unlinked_test');
  root=mkdtempSync('/tmp/shurufa-unlinked-files-');vi.spyOn(process,'cwd').mockReturnValue(root);
});
beforeEach(async()=>{
  if(!cluster)return;
  await pool.end(); const schema='test_'+randomUUID().replaceAll('-','');
  pool=new pg.Pool({...config(),options:`-c search_path=${schema}`});await pool.query(`CREATE SCHEMA ${schema}`);
  await pool.query(readFileSync(new URL('../../migrations/007_chat_capture.sql',import.meta.url),'utf8'));
  app=createApp(pool);agent=await authenticatedRequest(app,pool);
});
afterAll(async()=>{await pool?.end();vi.restoreAllMocks();if(root)rmSync(root,{recursive:true,force:true});});

test('只列当前手机未关联图片，排序分页稳定，不伪造平台或采集时间',async()=>{
  const old=await asset(a,'2026-10-21T00:00:00Z'),newer=await asset();await asset(b);await asset(a,undefined,'audio/mp3');
  const linked=await asset();await link(linked.id);
  const one=await agent.get(path+'&page_size=1');expect(one.status).toBe(200);expect(one.body.total).toBe(2);
  expect(one.body.images[0]).toMatchObject({id:newer.id,received_at:'2026-10-22T00:00:00.000Z'});
  expect(one.body.images[0]).not.toHaveProperty('captured_at');expect(one.body.images[0]).not.toHaveProperty('platform');
  const two=await agent.get(path+'&page_size=1&page=2');expect(two.body.images[0].id).toBe(old.id);
  expect(one.headers['cache-control']).toBe('no-store');
});
test('北京时间接收日期覆盖零点与最后微秒，排除相邻日期',async()=>{
  await asset(a,'2026-10-21T15:59:59.999Z');const first=await asset(a,'2026-10-21T16:00:00Z');
  const last=await asset(a,'2026-10-22T15:59:59.999999Z');await asset(a,'2026-10-22T16:00:00Z');
  const res=await agent.get(path+'&from=2026-10-22&to=2026-10-22');expect(res.status).toBe(200);
  expect(res.body.images.map((x:any)=>x.id)).toEqual([last.id,first.id]);expect(res.body.total).toBe(2);
});
test('消息关联补齐后自动移出，不复制或更改图片',async()=>{
  const row=await asset();expect((await agent.get(path)).body.total).toBe(1);await link(row.id);
  expect((await agent.get(path)).body).toMatchObject({total:0,images:[]});
  expect((await pool.query('SELECT * FROM media_asset WHERE id=$1',[row.id])).rows[0]).toEqual(row);
});
test('未登录拒绝，手机范围必填，原图仍按所属手机授权',async()=>{
  expect((await request(app).get(path)).status).toBe(401);
  expect((await agent.get('/api/v1/dashboard/chat/unlinked-images')).status).toBe(400);
  const row=await asset();mkdirSync(join(root,'uploads/chat'),{recursive:true});writeFileSync(join(root,'uploads',row.storage_path),'synthetic');
  expect((await agent.get(`/uploads/${row.storage_path}?user_id=${a}`)).status).toBe(200);
  expect((await agent.get(`/uploads/${row.storage_path}?user_id=${b}`)).status).toBe(404);
  expect((await request(app).get(`/uploads/${row.storage_path}?user_id=${a}`)).status).toBe(401);
});
for(const query of ['page=0','page=1.5','page=100001','page_size=61','page_size=0','from=2026-02-30','to=bad','from=2026-10-23&to=2026-10-22']){
  test(`拒绝无效筛选 ${query}`,async()=>{expect((await agent.get(path+'&'+query)).status).toBe(400);});
}

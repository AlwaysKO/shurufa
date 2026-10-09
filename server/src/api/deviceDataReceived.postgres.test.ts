import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { deviceDataReceivedAt } from '../lib/deviceDataReceived.js';

const cluster = process.env.CHAT_RETENTION_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-retention.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'retention-test-only')) throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
const A = randomUUID(), B = randomUUID();
const T1 = '2026-10-01T01:00:00.000Z', T2 = '2026-10-02T02:00:00.000Z';
let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
beforeAll(async () => {
  if (!cluster) return;
  const config = { host: join(cluster, 'socket'), port: 5432, user: 'ko' };
  const bootstrap = new pg.Pool({ ...config, database: 'postgres' });
  try {
    const identity = (await bootstrap.query("SELECT current_setting('data_directory') AS dir")).rows[0];
    expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
    await bootstrap.query('CREATE DATABASE device_data_received_test');
  } finally { await bootstrap.end(); }
  pool = new pg.Pool({ ...config, database: 'device_data_received_test' });
  for (const file of readdirSync(new URL('../../migrations/', import.meta.url)).filter(f => f.endsWith('.sql')).sort()) {
    await pool.query(readFileSync(new URL(`../../migrations/${file}`, import.meta.url), 'utf8'));
  }
});
beforeEach(async () => {
  if (!cluster) return;
  await pool.query('TRUNCATE device,input_event,app_usage_segment,chat_conversation,media_asset,navigation_record,mobile_report_receipt,call_recording,phone_call_log,phone_call_log_sync,location_track,runtime_setting CASCADE');
  await pool.query('INSERT INTO device(id,name,last_seen_at) VALUES($1,\'A\',$3),($2,\'B\',$4)', [A, B, T2, T1]);
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterAll(async () => { await pool?.end(); });
const directory = (query = '') => agent.get(`/api/v1/dashboard/users${query}`);
const device = (id = A) => agent.get(`/api/v1/dashboard/devices?user_id=${id}`);
const sources = ['input_event', 'app_usage_segment', 'chat_message', 'media_asset', 'navigation_record', 'mobile_report_receipt', 'call_recording', 'phone_call_log', 'page_capture', 'video_visit'];
async function seed(source: string, time = T1, user = A) {
  const id = randomUUID();
  switch (source) {
    case 'page_capture': await pool.query("INSERT INTO page_capture(user_id,id,platform,kind,captured_at,width,height,sha256,payload_sha256,mime_type,screenshot,received_at) VALUES($1,$2,'wechat','media_feed','2000-01-01',1,1,$3,$3,'image/png',$4,$5)", [user,id,'a'.repeat(64),Buffer.from('synthetic'),time]); break;
    case 'video_visit': await pool.query("INSERT INTO video_visit(user_id,id,platform,entered_at,ended_at,duration_ms,exit_reason,complete,payload_sha256,received_at) VALUES($1,$2,'wechat',946684800000,946684810000,10000,'exit',true,$3,$4)", [user,id,'a'.repeat(64),time]); break;
    case 'input_event': await pool.query("INSERT INTO input_event(id,user_id,device_id,event_type,occurred_at,created_at) VALUES($1,$2,$2,'commit','2000-01-01',$3)", [id, user, time]); break;
    case 'app_usage_segment': await pool.query("INSERT INTO app_usage_segment(user_id,id,kind,package_name,start_ms,end_ms,end_reason,received_at) VALUES($1,$2,'usage','app.test',946684800000,946684809000,'switch',$3)", [user, id, time]); break;
    case 'chat_message': {
      const conversation = (await pool.query("INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence) VALUES($1,'wechat','test',$2,'direct',1) RETURNING id", [user, id])).rows[0].id;
      await pool.query("INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at,created_at) VALUES($1,$2,$2,$3,'wechat',$4,$4,'sender','incoming','text','2000-01-01',$5)", [id, user, conversation, id.replaceAll('-', '').repeat(2), time]); break;
    }
    case 'media_asset': await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size,created_at) VALUES($1,$2,'image/png','synthetic.png',1,$3)", [user, id.replaceAll('-', '').repeat(2), time]); break;
    case 'navigation_record': await pool.query("INSERT INTO navigation_record(user_id,id,platform,origin,destination,started_at,overview_at,sha256,payload_sha256,mime_type,screenshot,received_at) VALUES($1,$2,'amap','起','终','2000-01-01','2000-01-01','hash','payload','image/png',$3,$4)", [user, id, Buffer.from('synthetic'), time]); break;
    case 'mobile_report_receipt': await pool.query("INSERT INTO mobile_report_receipt(user_id,report_id,payload_hash,received_at) VALUES($1,$2,'hash',$3)", [user, id, time]); break;
    case 'call_recording': await pool.query("INSERT INTO call_recording(device_id,record_id,metadata,metadata_sha256,sha256,byte_size,recorded_at,platform,recording_status,audio_ciphertext,stored_at) VALUES($1,$2,'{}','hash','hash',1,'2000-01-01','phone','ended',$3,$4)", [user, id, Buffer.from('synthetic'), time]); break;
    case 'phone_call_log': await pool.query('INSERT INTO phone_call_log(device_id,source_id,type,date,duration_seconds,stored_at) VALUES($1,$2,1,1000,0,$3)', [user, id, time]); break;
  }
}

test('仅成功联系、空通话同步、旧位置发生时间都不能制造业务入库证据', async () => {
  await pool.query("INSERT INTO phone_call_log_sync(device_id,status,synced_at) VALUES($1,'synced',NOW())", [A]);
  await pool.query('INSERT INTO location_track(user_id,device_id,latitude,longitude,occurred_at,first_seen_at,last_seen_at) VALUES($1,$1,23,113,NOW(),NOW(),NOW())', [A]);
  const result = await directory(); expect(result.status).toBe(200);
  expect(result.body.users.map((row: any) => row.last_data_received_at)).toEqual([null, null]);
  expect((await device()).body.devices[0]).toMatchObject({ id: A, last_seen_at: T2, last_data_received_at: null });
});

test.each(sources)('%s 采用服务器入库时间，设备隔离且旧事件补传也提供真实证据', async source => {
  await seed(source); await seed(source, T2, B);
  expect((await device()).body.devices[0].last_data_received_at).toBe(T1);
  expect((await device(B)).body.devices[0].last_data_received_at).toBe(T2);
  const result = await directory(`?id=${A}`); expect(result.status).toBe(200);
  expect(result.body.users[0].last_data_received_at).toBe(T1);
});

test('只查当前页现存证据，按人为操作排序；清理后可回退，已删录音不计入', async () => {
  await seed('input_event', T1, A); await seed('app_usage_segment', T2, A); await seed('call_recording', '2026-10-03T03:00:00Z', A);
  await pool.query('UPDATE call_recording SET deleted_at=NOW(),audio_ciphertext=NULL WHERE device_id=$1', [A]);
  await pool.query("UPDATE device SET last_interaction_at=$2,last_interaction_source='touch' WHERE id=$1", [A, T2]);
  const first = await directory('?page=1&page_size=1');
  expect(first.body.users).toHaveLength(1); expect(first.body.users[0]).toMatchObject({ id: A, last_data_received_at: T2 });
  expect((await directory('?page=2&page_size=1')).body.users[0]).toMatchObject({ id: B, last_data_received_at: null });
  await pool.query('DELETE FROM app_usage_segment WHERE user_id=$1', [A]);
  expect((await device()).body.devices[0].last_data_received_at).toBe(T1);
  await pool.query('DELETE FROM input_event WHERE user_id=$1', [A]);
  expect((await device()).body.devices[0].last_data_received_at).toBeNull();
  expect((await directory('?q=missing-device')).body.users).toEqual([]);
});

test('注册、失败、关闭保存与精确事件重试不推进业务入库时间', async () => {
  await seed('input_event');
  const stored = (await pool.query('SELECT id,occurred_at FROM input_event WHERE user_id=$1', [A])).rows[0];
  const post = (path: string, body: unknown) => request(app).post(`/api/v1/mobile${path}`).set('X-Device-Id', A).send(body as object);
  expect((await post('/device', { id: A, name: '注册联系' })).status).toBe(200);
  const event = { id: stored.id, device_id: A, event_type: 'commit', occurred_at: stored.occurred_at };
  expect((await post('/events/batch', { events: [event] })).body.inserted).toBe(0);
  expect((await post('/events/batch', { events: [{ ...event, device_id: B }] })).status).toBe(400);
  await pool.query("INSERT INTO runtime_setting(key,value) VALUES($1,'false')", [`device_save_uploads:${A}`]);
  expect((await post('/events/batch', { events: [{ ...event, id: randomUUID() }] })).body.discarded).toBe(true);
  expect((await device()).body.devices[0].last_data_received_at).toBe(T1);
});

test('持久报告去重保留首次接收时间，清掉位置仍有回执证据', async () => {
  const id = randomUUID(), body = { id, kind: 'location', payload: { device_id: A, latitude: 23, longitude: 113, occurred_at: '2000-01-01T00:00:00Z' } };
  const post = () => request(app).post('/api/v1/mobile/reports').set('X-Device-Id', A).send(body);
  expect((await post()).status).toBe(200);
  await pool.query('UPDATE mobile_report_receipt SET received_at=$2 WHERE report_id=$1', [id, T1]);
  expect((await post()).status).toBe(200);
  await pool.query('DELETE FROM location_track WHERE user_id=$1', [A]);
  expect((await device()).body.devices[0].last_data_received_at).toBe(T1);
});

test('当前页查询只有一组设备参数且十个来源都可使用接收时间索引，空页不查询', async () => {
  const spy = vi.spyOn(pool, 'query');
  let sql = '', params: unknown[] = [];
  try {
    expect(await deviceDataReceivedAt(pool, [])).toEqual(new Map());
    expect(spy).not.toHaveBeenCalled();
    await deviceDataReceivedAt(pool, [A]);
    expect(spy).toHaveBeenCalledTimes(1);
    sql = String(spy.mock.calls[0][0]); params = spy.mock.calls[0][1] as unknown[];
    expect(params).toEqual([[A]]);
  } finally { spy.mockRestore(); }
  const db = await pool.connect();
  try {
    await db.query('BEGIN'); await db.query('SET LOCAL enable_seqscan=off');
    const plan = JSON.stringify((await db.query(`EXPLAIN (FORMAT JSON) ${sql}`, params)).rows);
    for (const name of ['idx_input_event_user_created', 'idx_app_usage_user_received', 'idx_chat_message_user_created', 'idx_media_asset_user_created', 'idx_navigation_user_received', 'idx_mobile_receipt_user_received', 'idx_call_recording_device_stored', 'idx_phone_call_device_stored', 'idx_page_capture_user_received', 'idx_video_visit_user_received']) expect(plan).toContain(name);
    await db.query('ROLLBACK');
  } finally { db.release(); }
});

test('业务表缺失必须报错，不能静默伪装成暂无数据', async () => {
  await pool.query('ALTER TABLE media_asset RENAME TO media_asset_temporarily_unavailable');
  try { await expect(deviceDataReceivedAt(pool, [A])).rejects.toMatchObject({ code: '42P01' }); }
  finally { await pool.query('ALTER TABLE media_asset_temporarily_unavailable RENAME TO media_asset'); }
});

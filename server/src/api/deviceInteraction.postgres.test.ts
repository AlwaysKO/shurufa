import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';

const cluster = process.env.CHAT_RETENTION_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-retention.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'retention-test-only')) throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
let pool: pg.Pool;
const A = randomUUID(), B = randomUUID();
const OLD = '2026-10-01T00:00:00.000Z', NEW = '2026-10-02T00:00:00.000Z';
beforeAll(async () => {
  if (!cluster) return;
  const config = { host: join(cluster, 'socket'), port: 5432, user: 'ko' };
  const bootstrap = new pg.Pool({ ...config, database: 'postgres' });
  try {
    expect(realpathSync((await bootstrap.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster, 'data')));
    await bootstrap.query('CREATE DATABASE device_interaction_test');
  } finally { await bootstrap.end(); }
  pool = new pg.Pool({ ...config, database: 'device_interaction_test' });
  for (const file of readdirSync(new URL('../../migrations/', import.meta.url)).filter(f => f.endsWith('.sql')).sort())
    await pool.query(readFileSync(new URL(`../../migrations/${file}`, import.meta.url), 'utf8'));
});
beforeEach(async () => { if (cluster) await pool.query('TRUNCATE device,runtime_setting CASCADE'); });
afterAll(async () => { await pool?.end(); });
const upload = (payload: Record<string, unknown>, header = A) => request(createApp(pool)).post('/api/v1/mobile/device').set('X-Device-Id', header).send({ id: A, ...payload });
const row = async (id = A) => (await pool.query('SELECT last_interaction_at,last_interaction_source FROM device WHERE id=$1', [id])).rows[0];

test('旧版注册/无人后台重试没有人为证据，保持未知', async () => {
  expect((await upload({})).status).toBe(200);
  expect((await upload({})).status).toBe(200);
  expect(await row()).toEqual({ last_interaction_at: null, last_interaction_source: null });
});
test('离线补传保留原操作时间；重复及乱序重试不倒退、不改来源', async () => {
  expect((await upload({ last_interaction_at: NEW, last_interaction_source: 'touch' })).status).toBe(200);
  expect((await upload({ last_interaction_at: OLD, last_interaction_source: 'key' })).status).toBe(200);
  expect((await upload({ last_interaction_at: NEW, last_interaction_source: 'ime_input' })).status).toBe(200);
  await upload({});
  expect((await row()).last_interaction_at.toISOString()).toBe(NEW);
  expect((await row()).last_interaction_source).toBe('touch');
});
test.each(['touch','key','ime_input','usage_interaction'])('明确来源%s可更新', async source => {
  expect((await upload({last_interaction_at: OLD,last_interaction_source: source})).status).toBe(200);
  expect((await row()).last_interaction_source).toBe(source);
});
test.each([
  {last_interaction_at:'garbage',last_interaction_source:'touch'},
  {last_interaction_at:'2026-02-30T00:00:00.000Z',last_interaction_source:'touch'},
  {last_interaction_at:'2026-10-01',last_interaction_source:'touch'},
  {last_interaction_at: NEW,last_interaction_source:'heartbeat'},
  {last_interaction_at: NEW},
  {last_interaction_source:'touch'},
  {last_interaction_at: 1,last_interaction_source:'touch'},
  {last_interaction_at:'2999-01-01T00:00:00.000Z',last_interaction_source:'touch'},
])('拒绝不完整/非法/未来操作证据%j', async payload => {
  expect((await upload(payload)).status).toBe(400);
  expect(await row()).toBeUndefined();
});
test('身份不一致不得写入其他设备', async () => {
  expect((await upload({last_interaction_at: OLD,last_interaction_source:'touch'}, B)).status).toBe(400);
  expect(await row()).toBeUndefined();
});
test('关闭保存时不保存操作时间', async () => {
  await pool.query("INSERT INTO runtime_setting(key,value) VALUES($1,'false')", ['device_save_uploads:'+A]);
  const response = await upload({last_interaction_at: NEW,last_interaction_source:'touch'});
  expect(response.body.discarded).toBe(true);
  expect((await row()).last_interaction_at).toBeNull();
});

test('双设备字段不能绕过归属及保存开关', async () => {
  const response = await upload({id:B,device_id:A,last_interaction_at:NEW,last_interaction_source:'touch'});
  expect(response.status).toBe(400);
  expect(await row(B)).toBeUndefined();
});

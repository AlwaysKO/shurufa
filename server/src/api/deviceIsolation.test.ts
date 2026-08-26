import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';

const DEVICE_A = '00000000-0000-4000-8000-00000000000a';
const DEVICE_B = '00000000-0000-4000-8000-00000000000b';
let pool: pg.Pool;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(new URL('../../migrations/007_chat_capture.sql', import.meta.url), 'utf8'));
  await pool.query(`CREATE TABLE input_session (
    id UUID PRIMARY KEY, device_id UUID NOT NULL, started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ, package_name TEXT, editor_id TEXT, event_count INT NOT NULL DEFAULT 0
  )`);
  for (const [userId, peer] of [[DEVICE_A, '甲'], [DEVICE_B, '乙']]) {
    await pool.query(`INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, display_name,
       conversation_type, identity_confidence)
      VALUES ($1, 'wechat', 'account', $2, $2, 'direct', 1)`, [userId, peer]);
  }
});

describe('设备用户隔离', () => {
  it('移动 API 缺少设备身份时拒绝请求', async () => {
    const response = await request(createApp(pool))
      .post('/api/v1/mobile/chat/assets')
      .send({});
    expect(response.status).toBe(400);
    expect(response.body.error).toBe('device_id required');
  });

  it('请求头与请求体设备不一致时拒绝请求', async () => {
    const response = await request(createApp(pool))
      .post('/api/v1/mobile/chat/messages/batch')
      .set('X-Device-Id', DEVICE_A)
      .send({ device_id: DEVICE_B, messages: [] });
    expect(response.status).toBe(400);
    expect(response.body.error).toBe('device_id mismatch');
  });

  it('后台必须指定用户且两台手机数据互不可见', async () => {
    const missing = await request(createApp(pool)).get('/api/v1/dashboard/chat/conversations');
    expect(missing.status).toBe(400);

    const first = await request(createApp(pool))
      .get(`/api/v1/dashboard/chat/conversations?user_id=${DEVICE_A}`);
    const second = await request(createApp(pool))
      .get(`/api/v1/dashboard/chat/conversations?user_id=${DEVICE_B}`);

    expect(first.status).toBe(200);
    expect(first.body.conversations.map((row: { external_key: string }) => row.external_key)).toEqual(['甲']);
    expect(second.body.conversations.map((row: { external_key: string }) => row.external_key)).toEqual(['乙']);
  });

  it('不同设备不能复用同一会话 ID 覆盖对方记录', async () => {
    const sessionId = crypto.randomUUID();
    const app = createApp(pool);
    const first = await request(app).post('/api/v1/mobile/session').send({
      id: sessionId,
      device_id: DEVICE_A,
      started_at: new Date().toISOString(),
    });
    const conflict = await request(app).post('/api/v1/mobile/session').send({
      id: sessionId,
      device_id: DEVICE_B,
      started_at: new Date().toISOString(),
      event_count: 99,
    });

    expect(first.status).toBe(200);
    expect(conflict.status).toBe(409);
    expect((await pool.query('SELECT device_id, event_count FROM input_session WHERE id = $1', [sessionId])).rows[0])
      .toMatchObject({ device_id: DEVICE_A, event_count: 0 });
  });
});

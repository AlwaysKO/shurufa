import express from 'express';
import request from 'supertest';
import { newDb, DataType } from 'pg-mem';
import { readFileSync } from 'node:fs';
import type pg from 'pg';
import { afterEach, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { loginAddress } from './loginAudit.js';

const pools: pg.Pool[] = [];
afterEach(async () => { vi.unstubAllEnvs(); await Promise.all(pools.splice(0).map(p => p.end())); });
function setup() {
  vi.stubEnv('NODE_ENV', 'development');
  vi.stubEnv('DASHBOARD_USERNAME', 'admin'); vi.stubEnv('DASHBOARD_PASSWORD', 'adminhaha');
  const db = newDb();
  db.public.registerFunction({ name: 'length', args: [DataType.text], returns: DataType.integer, implementation: (s: string) => [...s].length });
  db.public.none(readFileSync(new URL('../../migrations/048_dashboard_login_audit.sql', import.meta.url), 'utf8'));
  const pool = new (db.adapters.createPg().Pool)() as pg.Pool; pools.push(pool);
  return { pool, app: createApp(pool) };
}
const login = (app: express.Express, password = 'adminhaha') => request(app).post('/api/v1/auth/login')
  .set('X-Dashboard-Request', '1').set('User-Agent', 'Mozilla/5.0 TestBrowser')
  .send({ username: 'admin', password });

it('保存成功和失败尝试，不保存密码或会话令牌，重建应用后仍能查询', async () => {
  const { pool, app } = setup();
  expect((await login(app, 'secret-wrong-password')).status).toBe(401);
  expect((await login(app)).status).toBe(200);
  const rows = (await pool.query('SELECT * FROM dashboard_login_audit ORDER BY id')).rows;
  expect(rows.map(r => r.result)).toEqual(['invalid_credentials', 'success']);
  expect(rows[0].authenticated_username).toBeNull();
  expect(rows[1].authenticated_username).toBe('admin');
  expect(rows[1].user_agent).toBe('Mozilla/5.0 TestBrowser');
  expect(rows[1].ip).toBe('127.0.0.1');
  expect(JSON.stringify(rows)).not.toMatch(/adminhaha|secret-wrong-password|dashboard_session/);
  const agent = request.agent(createApp(pool));
  await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  const response = await agent.get('/api/v1/auth/login-records?result=invalid_credentials&username=admin&ip=127.0.0.1&page_size=1');
  expect(response.status).toBe(200); expect(response.body.total).toBe(1);
  expect(response.body.records).toHaveLength(1);
});
it('记录限流和跨站拒绝，记录中的账号只是提交值', async () => {
  const { pool, app } = setup();
  for (let i = 0; i < 5; i++) await login(app, 'wrong');
  expect((await login(app)).status).toBe(429);
  expect((await request(app).post('/api/v1/auth/login').send({ username: 'someone', password: 'hidden' })).status).toBe(403);
  const rows = (await pool.query('SELECT * FROM dashboard_login_audit ORDER BY id')).rows;
  expect(rows[5].result).toBe('rate_limited'); expect(rows[6].result).toBe('rejected');
  expect(rows[6].username).toBe('someone'); expect(rows[6].authenticated_username).toBeNull();
});
it('未登录不可查询，分页和时间参数严格校验', async () => {
  const { app } = setup();
  expect((await request(app).get('/api/v1/auth/login-records')).status).toBe(401);
  const agent = request.agent(app);
  await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  for (const query of ['page=0', 'page=1.5', 'page_size=101', 'result=fake', 'from=bad', 'from=2026-02-30T00:00:00Z', 'from=2026-10-15T00:00:00Z&to=2026-10-14T00:00:00Z']) {
    expect((await agent.get('/api/v1/auth/login-records?' + query)).status).toBe(400);
  }
  const page = await agent.get('/api/v1/auth/login-records?page=2&page_size=1');
  expect(page.status).toBe(200); expect(page.body.records).toHaveLength(0); expect(page.body.total).toBe(1);
});
it('数据库写入失败不会发放新会话', async () => {
  const pool = { query: vi.fn().mockRejectedValue(new Error('unavailable')) } as unknown as pg.Pool;
  const response = await login(createApp(pool));
  expect(response.status).toBe(503); expect(response.headers['set-cookie']).toBeUndefined();
});
it('时间筛选以含时区的绝对时间比较，记录长度受限且过滤SQL注入文本', async () => {
  const { pool, app } = setup();
  await request(app).post('/api/v1/auth/login').set('X-Dashboard-Request', '1')
    .set('User-Agent', 'x'.repeat(1500)).send({ username: 'a'.repeat(250), password: 'wrong' });
  const row = (await pool.query('SELECT * FROM dashboard_login_audit')).rows[0];
  expect(row.username).toHaveLength(200); expect(row.user_agent).toHaveLength(1000);
  const agent = request.agent(app);
  await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  await pool.query("UPDATE dashboard_login_audit SET occurred_at='2026-10-14T00:00:00Z'");
  const filters = new URLSearchParams({ from: '2026-10-14T08:00:00+08:00', to: '2026-10-14T09:00:00+08:00' });
  expect((await agent.get('/api/v1/auth/login-records?' + filters)).body.total).toBe(2);
  filters.set('from', '2026-10-14T08:00:01+08:00');
  expect((await agent.get('/api/v1/auth/login-records?' + filters)).body.total).toBe(0);
  expect((await agent.get('/api/v1/auth/login-records?' + new URLSearchParams({ username: "' OR 1=1 --" }))).body.total).toBe(0);
});
it('只信任本机反代提供的单个合法IP，保留连接地址', () => {
  const req = (remoteAddress: string, forwarded?: string) => ({ socket: { remoteAddress }, get: () => forwarded }) as unknown as express.Request;
  expect(loginAddress(req('203.0.113.9', '8.8.8.8'))).toEqual({ ip: '203.0.113.9', peer_ip: '203.0.113.9', ip_source: 'connection' });
  expect(loginAddress(req('::ffff:127.0.0.1', '2001:db8::1'))).toEqual({ ip: '2001:db8::1', peer_ip: '127.0.0.1', ip_source: 'local_proxy' });
  for (const header of ['8.8.8.8, 1.1.1.1', 'spoofed', '']) expect(loginAddress(req('127.0.0.1', header)).ip).toBe('127.0.0.1');
});

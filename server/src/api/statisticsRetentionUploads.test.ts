import { createHash, randomUUID } from 'node:crypto';
import express from 'express';
import type pg from 'pg';
import sharp from 'sharp';
import request from 'supertest';
import { expect, it, vi } from 'vitest';
import { createMobileNavigationRouter } from './navigationRecords.js';
import { createMobileAppUsageRouter } from './appUsage.js';
import { createMobileCallLogsRouter } from './callLogs.js';
import { lockStatisticsRetention } from '../lib/statisticsRetentionLock.js';

const user = randomUUID();
it('同一UUID大小写共享同一清理上传锁', async () => {
  const query = vi.fn(async () => ({ rows: [] }));
  await lockStatisticsRetention({ query } as unknown as pg.PoolClient, 'call-logs', user.toUpperCase());
  expect(query).toHaveBeenCalledWith("SELECT pg_advisory_xact_lock(hashtextextended('statistics-retention:' || $1 || ':' || $2,0))", ['call-logs', user.toLowerCase()]);
});
const hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
async function navigation() {
  const bytes = await sharp({ create: { width: 4, height: 4, channels: 3, background: '#abc' } }).png().toBuffer();
  const meta = { id: randomUUID(), platform: 'amap', origin: '起点', destination: '终点', started_at: 1780000001000, overview_at: 1780000000000, sha256: hash(bytes), mime_type: 'image/png' };
  return { body: { ...meta, file_base64: bytes.toString('base64') }, version: hash(JSON.stringify(meta)) };
}
function setup(kind: 'navigation' | 'app-usage' | 'call-logs', deleted: string | null = null, stored: string | null = null) {
  const query = vi.fn(async (sql: string) => {
    if (sql.includes('FROM retention_deleted_record')) return { rows: deleted === null ? [] : [{ source_version: deleted }], rowCount: deleted === null ? 0 : 1 };
    if (sql.includes('SELECT payload_sha256')) return { rows: stored === null ? [] : [{ payload_sha256: stored }], rowCount: 1 };
    if (sql.includes('SELECT request_id')) return { rows: [{ request_id: null }], rowCount: 1 };
    return { rows: [], rowCount: 0 };
  });
  const db = { query, release: vi.fn() }, pool = { query, connect: vi.fn(async () => db) };
  const app = express(); app.use(express.json()); app.use((_req, res, next) => { res.locals.userId = user; next(); });
  const router = kind === 'navigation' ? createMobileNavigationRouter : kind === 'app-usage' ? createMobileAppUsageRouter : createMobileCallLogsRouter;
  app.use(router(pool as unknown as pg.Pool));
  app.use((error: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => res.status(500).json({ error: error.message }));
  return { app, pool, db };
}

it('导航已删除的同ID同内容重试返回匹配成功回执，不重新写入图片', async () => {
  const row = await navigation(), { app, db } = setup('navigation', row.version);
  const result = await request(app).post('/').send(row.body);
  expect(result.status).toBe(200);
  expect(result.body).toEqual({ ok: true, id: row.body.id, sha256: row.body.sha256, deleted: true });
  expect(db.query.mock.calls.some(([sql]) => sql.startsWith('INSERT INTO navigation_record'))).toBe(false);
  const lock = db.query.mock.calls.findIndex(([sql]) => sql.includes('pg_advisory_xact_lock'));
  expect(lock).toBeGreaterThan(-1);
  expect(lock).toBeLessThan(db.query.mock.calls.findIndex(([sql]) => sql.includes('FROM retention_deleted_record')));
  expect(db.query.mock.calls.at(-1)).toEqual(['COMMIT']);
  expect(db.release).toHaveBeenCalledTimes(1);
});

it('已删除导航ID的新内容仍返回409，不能以删除记录为由复用ID', async () => {
  const row = await navigation(), { app, db } = setup('navigation', 'different-version');
  expect((await request(app).post('/').send(row.body)).status).toBe(409);
  expect(db.query.mock.calls.some(([sql]) => sql.startsWith('INSERT INTO navigation_record'))).toBe(false);
});

it.each(['app-usage', 'call-logs'] as const)('%s上传在业务写入前获得与清理一致的事务锁，提交后确认', async kind => {
  const { app, db, pool } = setup(kind);
  const body = kind === 'app-usage' ? { records: [{ id: randomUUID(), kind: 'usage', package_name: 'app.test', app_name: null, start_ms: Date.now() - 10000, end_ms: Date.now() - 1000, end_reason: 'switch' }] }
    : { request_id: null, status: 'synced', truncated: false, records: [{ source_id: '123', number: null, name: null, type: 1, date: Date.now() - 10000, duration_seconds: 1 }] };
  const response = await request(app).post(kind === 'app-usage' ? '/app-usage/batch' : '/sync').set('X-Device-Id', user).send(body);
  expect(response.status).toBe(200);
  expect(pool.connect).toHaveBeenCalledTimes(1);
  const calls = db.query.mock.calls;
  const lock = calls.findIndex(([sql]) => sql.includes('pg_advisory_xact_lock'));
  expect(lock).toBeGreaterThan(-1);
  expect(lock).toBeLessThan(calls.findIndex(([sql]) => sql.startsWith(`INSERT INTO ${kind === 'app-usage' ? 'app_usage_segment' : 'phone_call_log('}`)));
  expect(calls.at(-1)).toEqual(['COMMIT']);
  expect(db.release).toHaveBeenCalledTimes(1);
});

it('上传数据库失败回滚，不返回成功回执', async () => {
  const row = await navigation(), { app, db } = setup('navigation');
  db.query.mockImplementation(async (sql: string) => { if (sql.includes('FROM retention_deleted_record')) throw new Error('temporary'); return { rows: [], rowCount: 0 }; });
  expect((await request(app).post('/').send(row.body)).status).toBe(500);
  expect(db.query.mock.calls.at(-1)).toEqual(['ROLLBACK']);
  expect(db.release).toHaveBeenCalledTimes(1);
});

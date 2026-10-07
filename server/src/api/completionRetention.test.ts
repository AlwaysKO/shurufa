import express from 'express';
import request from 'supertest';
import type pg from 'pg';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createCompletionRetentionRouter } from './completionRetention.js';

const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const member = (id: string, kind = 'candidate', key = '["旧短语",null]') =>
  ({ id, kind, key, signature: `signature-${id}`, at: new Date('2020-01-01T00:00:00Z') });
function setup(rows = [member('1'), member('2', 'phrase')]) {
  let current = rows.map(row => ({ ...row }));
  const db = {
    query: vi.fn(async (sql: string, params?: unknown[]) => {
      if (sql.startsWith('WITH members')) return { rows: current, rowCount: current.length };
      if (sql.startsWith('DELETE')) return { rows: [], rowCount: (params?.[1] as string[]).length };
      return { rows: [], rowCount: 0 };
    }),
    release: vi.fn(),
  };
  const pool = { query: vi.fn(async () => ({ rows })), connect: vi.fn(async () => db) };
  const app = express();
  app.use(express.json());
  app.use((req, res, next) => { res.locals.userId = req.query.user_id || A; next(); });
  app.use(createCompletionRetentionRouter(pool as unknown as pg.Pool));
  app.use((error: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => res.status(500).json({ error: error.message }));
  return { app, pool, db, change: (next: typeof rows) => { current = next; } };
}
const preview = (app: ReturnType<typeof express>, days: unknown = 7, filters: unknown = {}) => request(app).post('/preview').send({ days, filters });
const batch = (app: ReturnType<typeof express>, token: string, offset = 0, user = A) => request(app).post('/batch').query({ user_id: user }).send({ confirm: 'DELETE', token, offset });
afterEach(() => vi.restoreAllMocks());

describe('补全候选保留清理协议', () => {
  it('补全清理锁归一化UUID大小写', async () => {
    const { app, db } = setup(); const user = 'ABCDEF12-1234-4123-8123-ABCDEF123456';
    const p = await request(app).post('/preview').query({ user_id: user }).send({ days: 7, filters: {} });
    expect((await batch(app, p.body.token, 0, user)).status).toBe(200);
    expect(db.query.mock.calls.find(([sql]) => sql.includes('pg_advisory_xact_lock'))?.[1]).toEqual([user.toLowerCase()]);
  });
  it('仅接受 1/7/30 天和空筛选，不接受伪造范围', async () => {
    const { app, pool } = setup();
    for (const days of [0, 2, 31, '7', null]) expect((await preview(app, days)).status).toBe(400);
    for (const filters of [null, [], { user_id: B }, { package_name: 'app' }]) expect((await preview(app, 7, filters)).status).toBe(400);
    expect(pool.query).not.toHaveBeenCalled();
    for (const days of [1, 7, 30]) expect((await preview(app, days)).status).toBe(200);
  });

  it('预览同时计候选及学习统计，空结果不生成可执行清理', async () => {
    const { app } = setup();
    const now = Date.now(); vi.spyOn(Date, 'now').mockReturnValue(now);
    const p = await preview(app, 1);
    expect(p.body).toMatchObject({ cutoff: new Date(now - 86400000).toISOString(), total_records: 2, total_files: 0, first_at: '2020-01-01T00:00:00.000Z', last_at: '2020-01-01T00:00:00.000Z' });
    const empty = setup([]); const e = await preview(empty.app);
    expect(e.body).toMatchObject({ total_records: 0, first_at: null, last_at: null });
    expect((await batch(empty.app, e.body.token)).status).toBe(410);
  });

  it('绑定当前手机并校验确认、游标、到期；失效请求不连接数据库', async () => {
    const { app, pool } = setup(); const p = await preview(app);
    expect((await batch(app, p.body.token, 0, B)).status).toBe(410);
    expect((await request(app).post('/batch').send({ token: p.body.token, offset: 0 })).status).toBe(400);
    expect((await batch(app, p.body.token, 1)).status).toBe(409);
    expect((await batch(app, p.body.token, -1)).status).toBe(400);
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 16 * 60000);
    expect((await batch(app, p.body.token)).status).toBe(410);
    expect(pool.connect).not.toHaveBeenCalled();
  });

  it('整组删除两种记录并幂等返回已完成进度', async () => {
    const { app, db } = setup(); const p = await preview(app);
    const r = await batch(app, p.body.token);
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ processed: 2, total: 2, deleted_records: 2, skipped_records: 0, done: true, files_pending: false });
    expect(db.query.mock.calls.filter(([sql]) => sql.startsWith('DELETE')).map(([, params]) => params)).toEqual([[A, ['1']], [A, ['2']]]);
    expect(db.query.mock.calls.some(([sql]) => sql === 'COMMIT')).toBe(true);
    const lock = db.query.mock.calls.findIndex(([sql]) => sql.includes('pg_advisory_xact_lock'));
    expect(lock).toBeGreaterThan(0);
    expect(lock).toBeLessThan(db.query.mock.calls.findIndex(([sql]) => sql.startsWith('LOCK TABLE')));
    const calls = db.query.mock.calls.length;
    expect((await batch(app, p.body.token)).body).toEqual(r.body);
    expect(db.query).toHaveBeenCalledTimes(calls);
  });

  it.each(['changed', 'added', 'removed'])('预览后组成员 %s 时整组跳过', async mode => {
    const { app, change } = setup(); const p = await preview(app);
    const rows = [member('1'), member('2', 'phrase')];
    if (mode === 'changed') rows[0].signature = 'updated';
    if (mode === 'added') rows.push(member('3', 'phrase'));
    if (mode === 'removed') rows.pop();
    change(rows);
    const r = await batch(app, p.body.token);
    expect(r.body).toMatchObject({ processed: 2, deleted_records: 0, skipped_records: 2, done: true });
  });

  it('超过5万条预览被拒绝，避免冻结不完整组', async () => {
    const { app } = setup(Array.from({ length: 50001 }, (_, i) => member(String(i))));
    expect((await preview(app)).status).toBe(400);
  });

  it('事务失败不推进游标并允许同一批重试', async () => {
    const { app, db } = setup(); const p = await preview(app);
    db.query.mockRejectedValueOnce(new Error('temporary'));
    expect((await batch(app, p.body.token)).status).toBe(500);
    expect(db.query.mock.calls.some(([sql]) => sql === 'ROLLBACK')).toBe(true);
    expect((await batch(app, p.body.token)).body).toMatchObject({ processed: 2, deleted_records: 2, done: true });
    expect(db.release).toHaveBeenCalledTimes(2);
  });

  it('跨批次按整组推进，200条边界不拆分同一短语的候选和学习记录', async () => {
    const rows = Array.from({ length: 199 }, (_, i) => member(String(i), 'candidate', `["词${i}",null]`));
    rows.push(member('200', 'candidate', '["整组",null]'), member('201', 'phrase', '["整组",null]'));
    const { app } = setup(rows); const p = await preview(app);
    const first = await batch(app, p.body.token);
    expect(first.body).toMatchObject({ processed: 199, total: 201, deleted_records: 199, done: false });
    expect((await batch(app, p.body.token)).body).toEqual(first.body);
    expect((await batch(app, p.body.token, 199)).body).toMatchObject({ processed: 201, deleted_records: 201, done: true });
  });

  it('同一快照在数据库连接等待期间也不能并发执行', async () => {
    const { app, pool, db } = setup(); const p = await preview(app);
    let release!: () => void;
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    const gate = new Promise<void>(resolve => { release = resolve; });
    pool.connect.mockImplementationOnce(async () => { entered(); await gate; return db; });
    const first = batch(app, p.body.token).then(response => response);
    await started;
    try { expect((await batch(app, p.body.token)).status).toBe(409); }
    finally { release(); }
    expect((await first).body).toMatchObject({ deleted_records: 2, done: true });
    expect(pool.connect).toHaveBeenCalledTimes(1);
  });
});

import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import express from 'express';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, afterAll, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';

// 只接受专用 initdb 实例，绝不读取业务数据库连接配置。
const cluster = process.env.TIMELINE_BEIJING_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-timeline-beijing.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'timeline-beijing-only')) {
  throw Error('独立测试实例验证失败');
}
const test = cluster ? it : it.skip;
let pool: pg.Pool;
let app: express.Express;
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: 'ko', database: 'timeline_beijing_test', max: 1 });
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir, current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('timeline_beijing_test');
  await pool.query(`CREATE TABLE input_event (user_id text, occurred_at timestamptz, event_type text, text text);
    INSERT INTO input_event VALUES
    ('a', '2026-09-13T15:59:59Z', 'commit', '甲乙'),
    ('a', '2026-09-13T16:00:00Z', 'paste', '甲乙丙'),
    ('a', '2026-09-14T00:00:00Z', 'voice', '甲乙'),
    ('a', '2026-09-14T00:01:00Z', 'delete', '不计输入字数'),
    ('b', '2026-09-13T16:00:00Z', 'commit', '其他用户不计入'),
    ('a', '2026-01-01T00:00:00Z', 'commit', '超出范围不计入')`);
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-18T04:00:00Z'));
  app = express();
  app.use((_req, res, next) => { res.locals.userId = 'a'; next(); });
  app.use(createDashboardRouter(pool));
});
afterAll(async () => { vi.restoreAllMocks(); await pool?.end(); });
for (const timezone of ['UTC', 'Asia/Shanghai', 'America/Los_Angeles']) {
  test(`${timezone} 数据库时区下按北京时间自然日分组，返回纯日期而非UTC时间戳`, async () => {
    await pool.query("SELECT set_config('TimeZone', $1, false)", [timezone]);
    const result = await request(app).get('/timeline?days=7');
    expect(result.status).toBe(200);
    expect(result.body.timeline).toEqual([
      { day: '2026-09-13', event_count: '1', input_chars: '2' },
      { day: '2026-09-14', event_count: '3', input_chars: '5' },
    ]);
  });
  test(`${timezone} 数据库时区下小时分布以北京午夜为0时`, async () => {
    await pool.query("SELECT set_config('TimeZone', $1, false)", [timezone]);
    const result = await request(app).get('/hours?days=7');
    expect(result.status).toBe(200);
    expect(result.body.hours).toEqual([
      { hour: 0, event_count: '1', input_chars: '3' },
      { hour: 8, event_count: '2', input_chars: '2' },
      { hour: 23, event_count: '1', input_chars: '2' },
    ]);
  });
  test(`${timezone} 数据库时区下热力图在北京周日午夜切换至周一`, async () => {
    await pool.query("SELECT set_config('TimeZone', $1, false)", [timezone]);
    const result = await request(app).get('/heatmap?days=7');
    expect(result.status).toBe(200);
    expect(result.body.cells).toEqual([
      { dow: 1, hour: 0, chars: '3' },
      { dow: 1, hour: 8, chars: '2' },
      { dow: 7, hour: 23, chars: '2' },
    ]);
  });
}

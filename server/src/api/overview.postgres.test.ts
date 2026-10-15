import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import express from 'express';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, afterAll, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';

const cluster = process.env.TIMELINE_BEIJING_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-timeline-beijing.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'timeline-beijing-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
let pool: pg.Pool;
let app: express.Express;
beforeAll(async () => {
  if (!cluster) return;
  const config = { host: join(cluster, 'socket'), port: 5432, user: 'ko' };
  const bootstrap = new pg.Pool({ ...config, database:'postgres' });
  try {
    expect(realpathSync((await bootstrap.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(join(cluster,'data')));
    await bootstrap.query('CREATE DATABASE overview_beijing_test');
  } finally { await bootstrap.end(); }
  pool = new pg.Pool({ ...config, database:'overview_beijing_test', max:1 });
  await pool.query(`CREATE TABLE input_event (user_id text, occurred_at timestamptz, event_type text, text text);
    INSERT INTO input_event VALUES
    ('a', '2026-10-14T15:59:59.999Z', 'commit', '昨天'),
    ('a', '2026-10-14T16:00:00Z', 'commit', '零点'),
    ('a', '2026-10-15T00:00:00Z', 'paste', '八点'),
    ('a', '2026-10-15T15:59:59.999999Z', 'voice', '当天最后'),
    ('a', '2026-10-15T16:00:00Z', 'commit', '明天不算'),
    ('a', '2026-10-14T16:01:00Z', 'clipboard_change', '复制'),
    ('a', '2026-10-15T01:00:00Z', 'delete', '删除'),
    ('a', '2026-10-08T16:00:00Z', 'commit', '七天起点'),
    ('a', '2026-10-08T15:59:59.999Z', 'commit', '七天之前'),
    ('b', '2026-10-14T16:00:00Z', 'commit', '另一设备')`);
  app = express();
  app.use((_req,res,next) => { res.locals.userId='a'; next(); });
  app.use(createDashboardRouter(pool));
});
afterAll(async () => { vi.restoreAllMocks(); await pool?.end(); });

for (const zone of ['UTC','Asia/Shanghai','America/Los_Angeles']) {
  for (const now of ['2026-10-14T16:30:00Z','2026-10-15T04:00:00Z','2026-10-15T15:59:59Z']) {
    test(`${zone} / ${now}: 北京自然日包含零点及最后微秒，排除前后日与其他设备`, async () => {
      vi.spyOn(Date,'now').mockReturnValue(Date.parse(now));
      await pool.query("SELECT set_config('TimeZone',$1,false)",[zone]);
      const response = await request(app).get('/overview?days=7');
      expect(response.status).toBe(200);
      expect(response.body.today_range).toEqual({date:'2026-10-15',timezone:'Asia/Shanghai',from:'2026-10-14T16:00:00.000Z',to:'2026-10-15T16:00:00.000Z'});
      expect(response.body.today).toEqual({input_events:'3',input_chars:'8',clipboard_count:'1',delete_count:'1'});
      expect(response.body.period).toEqual({event_count:'7',input_chars:'14',active_days:'3'});
      expect(response.body.source_distribution).toEqual({typed:8,pasted:2,external:0,voice:4,total:14});
    });
  }
}
test('跨北京午夜后切换到下一天，不沿用昨日统计', async () => {
  vi.spyOn(Date,'now').mockReturnValue(Date.parse('2026-10-15T16:00:00Z'));
  const response = await request(app).get('/overview');
  expect(response.body.today_range.date).toBe('2026-10-16');
  expect(response.body.today).toEqual({input_events:'1',input_chars:'4',clipboard_count:'0',delete_count:'0'});
});

import { randomUUID } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { defaultDeliveryConfig, readDeliveryState, updateDeliveryState, DeliveryConflict } from '../lib/expressionDelivery.js';

// Opt-in only: never use .env or the application's production pool.
const cluster = process.env.EXPRESSION_DELIVERY_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-delivery.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'expression-delivery-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
let pool: pg.Pool;
let verified = false;
const sql = () => readFileSync(new URL('../../migrations/021_expression_delivery_defaults.sql', import.meta.url), 'utf8');
const connection = () => ({ host: join(cluster!, 'socket'), port: 5432, user: process.env.USER || 'ko', database: 'expression_delivery_test' });
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool(connection());
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir, current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('expression_delivery_test'); verified = true;
});
beforeEach(async () => {
  if (!cluster) return;
  if (!verified) throw Error('禁止操作非隔离实例');
  await pool.end();
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  pool = new pg.Pool({ ...connection(), max: 4, options: `-c search_path=${schema}` });
  await pool.query(`CREATE SCHEMA ${schema}`);
  await pool.query(readFileSync(new URL('../../migrations/020_runtime_settings.sql', import.meta.url), 'utf8'));
});
afterAll(async () => { await pool?.end(); });

test('首次迁移创建与服务端默认完全一致的全局记录', async () => {
  await pool.query(sql());
  expect(await readDeliveryState(pool)).toEqual({ current: defaultDeliveryConfig(), history: [] });
  expect((await pool.query('SELECT key FROM runtime_setting')).rows).toEqual([{ key: 'expression_delivery_v1' }]);
});
test('重复迁移不改变现有记录和更新时间', async () => {
  await pool.query(sql());
  const before = (await pool.query('SELECT * FROM runtime_setting')).rows;
  await pool.query(sql());
  expect((await pool.query('SELECT * FROM runtime_setting')).rows).toEqual(before);
});
test('管理员已修改时迁移不覆盖current或history，也不改其他setting', async () => {
  await pool.query(sql());
  const rules = defaultDeliveryConfig().rules; rules[0].enabled = false;
  const saved = await updateDeliveryState(pool, 0, rules);
  await pool.query("INSERT INTO runtime_setting(key,value) VALUES('collector_base_url','https://example.test')");
  await pool.query(sql());
  expect(await readDeliveryState(pool)).toEqual(saved);
  expect((await pool.query("SELECT value FROM runtime_setting WHERE key='collector_base_url'")).rows[0].value).toBe('https://example.test');
});
test('真实数据库并发CAS仅一个写入成功，另一个明确冲突', async () => {
  await pool.query(sql());
  const outcomes = await Promise.allSettled([updateDeliveryState(pool, 0, []), updateDeliveryState(pool, 0, defaultDeliveryConfig().rules)]);
  expect(outcomes.filter(o => o.status === 'fulfilled')).toHaveLength(1);
  const rejected = outcomes.find(o => o.status === 'rejected') as PromiseRejectedResult;
  expect(rejected.reason).toBeInstanceOf(DeliveryConflict);
  expect((await readDeliveryState(pool)).current.revision).toBe(1);
});

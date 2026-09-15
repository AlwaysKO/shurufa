import { newDb } from 'pg-mem';
import type pg from 'pg';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

let pool: pg.Pool;
let resolveMissingIps: typeof import('./ipgeo.js').resolveMissingIps;
const ip = '116.22.49.196';
const location = '中国 广东 广州市';
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(async () => {
  vi.resetModules();
  ({ resolveMissingIps } = await import('./ipgeo.js'));
  pool = new (newDb().adapters.createPg().Pool)();
  await pool.query('CREATE TABLE input_event (client_ip TEXT, ip_location TEXT)');
  await pool.query('INSERT INTO input_event VALUES ($1, NULL)', [ip]);
  fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'success', country: '中国', regionName: '广东', city: '广州市' }) });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => { vi.unstubAllGlobals(); vi.useRealTimers(); await pool.end(); });

it('重复 IP 只查询一次并返回本次响应所需结果', async () => {
  const result = await resolveMissingIps(pool, [ip, ip]);
  expect(result).toEqual(new Map([[ip, location]]));
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('同 IP 的后续新记录复用缓存并补写地址', async () => {
  await resolveMissingIps(pool, [ip]);
  await pool.query('INSERT INTO input_event VALUES ($1, NULL)', [ip]);
  await resolveMissingIps(pool, [ip]);
  expect((await pool.query('SELECT ip_location FROM input_event')).rows).toEqual([{ ip_location: location }, { ip_location: location }]);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('并发请求合并同 IP 的解析', async () => {
  await Promise.all([resolveMissingIps(pool, [ip]), resolveMissingIps(pool, [ip])]);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('进程无缓存时复用数据库已有地址', async () => {
  await pool.query('INSERT INTO input_event VALUES ($1, $2)', [ip, location]);
  await resolveMissingIps(pool, [ip]);
  expect(fetchMock).not.toHaveBeenCalled();
  expect((await pool.query('SELECT ip_location FROM input_event')).rows.every(r => r.ip_location === location)).toBe(true);
});

it('失败短暂缓存，冷却后可重试而不是永久 null', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  fetchMock.mockRejectedValueOnce(new Error('timeout'));
  expect(await resolveMissingIps(pool, [ip])).toEqual(new Map([[ip, null]]));
  await resolveMissingIps(pool, [ip]);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  vi.setSystemTime(Date.now() + 60_001);
  expect(await resolveMissingIps(pool, [ip])).toEqual(new Map([[ip, location]]));
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

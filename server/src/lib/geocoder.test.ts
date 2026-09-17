import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type pg from 'pg';
import { newDb } from 'pg-mem';

let geocoder: typeof import('./geocoder.js');
let fetchMock: ReturnType<typeof vi.fn>;
const userId = '00000000-0000-4000-8000-000000000001';
const coordinate = (id: number, lat = 1) => ({ id, lat, lng: 2 });
function poolFixture() {
  const query = vi.fn().mockResolvedValue({ rowCount: 1 });
  return { pool: { query } as unknown as pg.Pool, query };
}
const success = () => ({ ok: true, status: 200, json: async () => ({ display_name: '测试地址', address: { country: '测试国家', city: '测试城市' } }) });
beforeEach(async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-17T00:00:00Z'));
  vi.resetModules();
  fetchMock = vi.fn().mockResolvedValue(success());
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  geocoder = await import('./geocoder.js');
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });
async function drain() { await vi.runAllTimersAsync(); }

it('失败有冷却时间，冷却后能重试，不再永久缓存null', async () => {
  const { pool, query } = poolFixture();
  fetchMock.mockResolvedValueOnce({ ok: false, status: 503, headers: new Headers() });
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(query).not.toHaveBeenCalled();
  const state = geocoder.addressResolution(pool, userId, coordinate(1));
  expect(state.address_status).toBe('failed');
  expect(state.address_error).toContain('503');
  expect(Date.parse(state.address_retry_at!)).toBeGreaterThan(Date.now());
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(30_000);
  const retry = geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  await drain(); await retry;
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(query).toHaveBeenCalledTimes(1);
});

it('同坐标的多条记录都回填缓存地址，SQL只补当前用户指定记录的空地址', async () => {
  const { pool, query } = poolFixture();
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  await geocoder.resolveMissingAddresses(pool, [coordinate(2)], userId);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(query).toHaveBeenCalledTimes(2);
  expect(query.mock.calls.map(c => c[1])).toEqual([
    ['测试国家 测试城市', 1, userId], ['测试国家 测试城市', 2, userId],
  ]);
  for (const [sql] of query.mock.calls) {
    expect(sql).toMatch(/WHERE id = \$2 AND user_id = \$3 AND address IS NULL/);
    expect(sql).not.toMatch(/DELETE|TRUNCATE|occurred_at\s*=|latitude\s*=/i);
  }
});

it('并发打开页面合并相同坐标请求和同一记录回写', async () => {
  const { pool, query } = poolFixture();
  let resolve!: (response: any) => void;
  fetchMock.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
  const first = geocoder.resolveMissingAddresses(pool, [coordinate(1), coordinate(2)], userId);
  const second = geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  await vi.advanceTimersByTimeAsync(0);
  expect(geocoder.addressResolution(pool, userId, coordinate(1)).address_status).toBe('resolving');
  expect(fetchMock).toHaveBeenCalledTimes(1);
  resolve(success()); await first; await second;
  expect(query).toHaveBeenCalledTimes(2);
});

it('不同坐标全局串行限速，自动解析最多每15秒一次', async () => {
  const { pool } = poolFixture();
  const times: number[] = [];
  fetchMock.mockImplementation(async () => { times.push(Date.now()); return success(); });
  const first = geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  const second = geocoder.resolveMissingAddresses(pool, [coordinate(2, 3)], userId);
  await drain(); await Promise.all([first, second]);
  expect(times).toHaveLength(2);
  expect(times[1] - times[0]).toBeGreaterThanOrEqual(15_000);
});

it('地址已获取但回写失败时仍可重试，不重新请求地址服务', async () => {
  const { pool, query } = poolFixture();
  query.mockRejectedValueOnce(new Error('模拟数据库暂时不可用'));
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(geocoder.addressResolution(pool, userId, coordinate(1)).address_status).toBe('failed');
  await vi.advanceTimersByTimeAsync(30_000);
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(query).toHaveBeenCalledTimes(2);
});

it('429遵守Retry-After，网络异常不会丢失失败状态', async () => {
  const { pool } = poolFixture();
  fetchMock.mockResolvedValueOnce({ ok: false, status: 429, headers: new Headers({ 'Retry-After': '120' }) });
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(Date.parse(geocoder.addressResolution(pool, userId, coordinate(1)).address_retry_at!) - Date.now()).toBeGreaterThanOrEqual(120_000);
  await vi.advanceTimersByTimeAsync(120_000);
  fetchMock.mockRejectedValueOnce(new Error('连接失败'));
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  expect(geocoder.addressResolution(pool, userId, coordinate(1)).address_error).toContain('请求失败');
});

it('解析地址可通过配置切换服务，不把真实坐标用于测试', async () => {
  vi.stubEnv('GEOCODER_REVERSE_URL', 'https://geocoder.test/reverse');
  const { pool } = poolFixture();
  await geocoder.resolveMissingAddresses(pool, [coordinate(1)], userId);
  const url = new URL(String(fetchMock.mock.calls[0][0]));
  expect(url.origin).toBe('https://geocoder.test');
  expect(url.searchParams.get('lat')).toBe('1');
});

it('真实SQL在纯内存数据库中只补当前用户空地址，保留其他用户及已有地址和时间坐标', async () => {
  // pg-mem 的 Pool 通过 setImmediate 完成查询；本例同坐标只请求一次，无需虚拟限速时钟。
  vi.useRealTimers();
  const adapter = newDb().adapters.createPg();
  const pool = new adapter.Pool() as unknown as pg.Pool;
  try {
    await pool.query(`CREATE TABLE location_track (id INTEGER PRIMARY KEY, user_id TEXT, address TEXT,
      latitude NUMERIC, longitude NUMERIC, occurred_at TEXT)`);
    const otherUser = 'other-user';
    await pool.query(`INSERT INTO location_track VALUES
      (1, $1, NULL, 1, 2, '2026-09-17T01:00:56Z'),
      (2, $2, NULL, 1, 2, '2026-09-17T01:00:56Z'),
      (3, $1, '原有地址', 1, 2, '2026-09-17T01:00:56Z')`, [userId, otherUser]);
    await geocoder.resolveMissingAddresses(pool, [coordinate(1), coordinate(2), coordinate(3)], userId);
    const { rows } = await pool.query('SELECT * FROM location_track ORDER BY id');
    expect(rows.map(row => row.address)).toEqual(['测试国家 测试城市', null, '原有地址']);
    for (const row of rows) expect(row).toMatchObject({ latitude: 1, longitude: 2, occurred_at: '2026-09-17T01:00:56Z' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  } finally { await pool.end(); }
});

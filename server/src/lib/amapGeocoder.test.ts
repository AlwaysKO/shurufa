import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type pg from 'pg';
import { newDb } from 'pg-mem';
import { parseAmapPlace } from './amapGeocoder.js';

const address = () => ({ formatted_address: '测试市测试路12号', addressComponent: { streetNumber: { street: '测试路', number: '12号' }, building: { name: [] } },
  aois: [{ id: 'building-a', name: '测试大厦', type: '120201', distance: '0', area: '1000' }],
  pois: [{ id: 'building-a', name: '测试大厦', address: '测试路12号', type: '商务住宅;楼宇', distance: '30' }] });
it('从所属AOI及同ID对象取楼宇和门牌，不把道路另一边最近门牌当本楼门牌', () => {
  const data = address(); data.addressComponent.streetNumber.number = '99号';
  expect(parseAmapPlace(data)).toMatchObject({ doorplate: '测试路12号', building_name: '测试大厦', shop_name: null, source: 'amap' });
});
it('不把附近首个POI当作店名，也不把不包含坐标的AOI当所属楼宇', () => {
  expect(parseAmapPlace({ ...address(), aois: [{ name: '其他楼', distance: '2' }], pois: [
    { id: 'shop', name: '附近便利店', type: '购物服务;便利店', address: '另一路3号', distance: '1' }] })).toMatchObject({ doorplate: '测试路12号', shop_name: null, building_name: null });
});
it('明确属于商户AOI时返回商户名称，同门牌多个商户不盲选', () => {
  const data = address(); data.aois[0] = { id: 'shop', name: '测试商户', type: '060000', distance: '0', area: '100' };
  data.pois = [{ id: 'shop', name: '测试商户', type: '购物服务;便利店', address: '测试路12号', distance: '20' }];
  expect(parseAmapPlace(data).shop_name).toBe('测试商户');
  const candidates = ['甲店', '乙店'].map((name, i) => ({ id: String(i), name, address: '测试路12号', type: '购物服务;便利店', distance: '10' }));
  expect(parseAmapPlace({ ...address(), aois: [], pois: candidates }).shop_name).toBeNull();
});
it('同门牌唯一近邻仍不能证明所在店，数组空值不能当成名称或0距离', () => {
  expect(parseAmapPlace({ ...address(), aois: [], pois: [{ id: 'shop', name: '测试商户', address: '测试路12号', type: '餐饮服务;餐厅', distance: '10' }] }).shop_name).toBeNull();
  expect(parseAmapPlace({ addressComponent: { streetNumber: { number: [] }, building: { name: [] } }, aois: [{ name: '无效归属', distance: [] }], pois: [] })).toMatchObject({ doorplate: null, shop_name: null, building_name: null });
});

let geocoder: typeof import('./geocoder.js'); let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(async () => { vi.useFakeTimers(); vi.resetModules(); vi.stubEnv('AMAP_WEB_SERVICE_KEY', 'synthetic-test-key');
  fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ status: '1', locations: '2.01,1.01' }) })
    .mockResolvedValue({ ok: true, json: async () => ({ status: '1', regeocode: address() }) });
  vi.stubGlobal('fetch', fetchMock); vi.spyOn(console, 'warn').mockImplementation(() => {}); geocoder = await import('./geocoder.js'); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it('先转换查询坐标再解析，原坐标不改且HTTP请求限速，写入来源', async () => {
  const query = vi.fn().mockResolvedValue({ rowCount: 1 });
  const work = geocoder.resolveMissingAddresses({ query } as unknown as pg.Pool, [{ id: 1, lat: 1, lng: 2 }], 'owner');
  await vi.runAllTimersAsync(); await work;
  const urls = fetchMock.mock.calls.map(c => new URL(String(c[0])));
  expect(urls.map(u => u.pathname)).toEqual(['/v3/assistant/coordinate/convert', '/v3/geocode/regeo']);
  expect(urls[0].searchParams.get('locations')).toBe('2,1'); expect(urls[0].searchParams.get('coordsys')).toBe('gps');
  expect(urls[1].searchParams.get('location')).toBe('2.01,1.01'); expect(urls[1].searchParams.get('extensions')).toBe('all');
  expect(JSON.parse(query.mock.calls[0][1][3])).toMatchObject({ source: 'amap', doorplate: '测试路12号', building_name: '测试大厦' });
  expect(query.mock.calls[0][0]).not.toMatch(/SET\s+latitude|SET\s+longitude|occurred_at\s*=/i);
});
it('旧来源双空结果需要重查，已有有效名称及高德成功空结果不无限重查', () => {
  expect(geocoder.needsPlaceResolution({ source: 'nominatim', doorplate: null, shop_name: null })).toBe(true);
  expect(geocoder.needsPlaceResolution({ source: 'nominatim', doorplate: '已有门牌', shop_name: null })).toBe(false);
  expect(geocoder.needsPlaceResolution({ source: 'amap', doorplate: null, shop_name: null })).toBe(false);
});
it('高德逻辑错误不冒充成功空结果，也不回退公共服务泄漏坐标或密钥', async () => {
  fetchMock.mockReset().mockResolvedValue({ ok: true, json: async () => ({ status: '0', infocode: '10001', info: 'synthetic-test-key' }) });
  const query = vi.fn(); const point = { id: 1, lat: 1, lng: 2 };
  await geocoder.resolveMissingAddresses({ query } as unknown as pg.Pool, [point], 'owner');
  expect(query).not.toHaveBeenCalled(); expect(fetchMock).toHaveBeenCalledTimes(1);
  const result = geocoder.addressResolution({ query } as unknown as pg.Pool, 'owner', point);
  expect(result.address_status).toBe('failed'); expect(result.address_error).not.toContain('synthetic-test-key');
  expect(JSON.stringify(vi.mocked(console.warn).mock.calls)).not.toContain('synthetic-test-key');
});

it('真实SQL只升级当前用户旧双空结果，保留已有信息及高德成功空结果', async () => {
  vi.useRealTimers();
  const adapter = newDb().adapters.createPg(); const pool = new adapter.Pool() as unknown as pg.Pool;
  try {
    await pool.query('CREATE TABLE location_track(id INTEGER PRIMARY KEY,user_id TEXT,address TEXT,place_details JSONB)');
    const empty = { source: 'nominatim', doorplate: null, shop_name: null };
    await pool.query(`INSERT INTO location_track VALUES(1,'owner','旧地址',$1),(2,'other','其他人地址',$1),
      (3,'owner','有效地址',$2),(4,'owner','高德旧地址',$3)`,
      [JSON.stringify(empty), JSON.stringify({ ...empty, doorplate: '原门牌' }), JSON.stringify({ ...empty, source: 'amap' })]);
    await geocoder.resolveMissingAddresses(pool, [1,2,3,4].map(id => ({ id, lat: 1, lng: 2 })), 'owner');
    const rows = (await pool.query('SELECT * FROM location_track ORDER BY id')).rows;
    expect(rows[0]).toMatchObject({ address: '旧地址', place_details: { source: 'amap', doorplate: '测试路12号' } });
    expect(rows[1].place_details).toEqual(empty);
    expect(rows[2].place_details.doorplate).toBe('原门牌');
    expect(rows[3].place_details).toEqual({ ...empty, source: 'amap' });
  } finally { await pool.end(); }
});
it('转换格式不完整时不把缺失纬度当零，也不写入成功空详情', async () => {
  fetchMock.mockReset().mockResolvedValue({ ok: true, json: async () => ({ status: '1', locations: '2.01,' }) });
  const query = vi.fn();
  await geocoder.resolveMissingAddresses({ query } as unknown as pg.Pool, [{ id: 1, lat: 1, lng: 2 }], 'owner');
  expect(fetchMock).toHaveBeenCalledTimes(1); expect(query).not.toHaveBeenCalled();
});
it('转换和逆解析所有HTTP请求至少间隔一秒', async () => {
  const times: number[] = []; fetchMock.mockReset().mockImplementation(async (url: URL) => {
    times.push(Date.now());
    return { ok: true, json: async () => url.pathname.endsWith('/convert') ? { status: '1', locations: '2.01,1.01' } : { status: '1', regeocode: address() } };
  });
  const work = geocoder.resolveMissingAddresses({ query: vi.fn().mockResolvedValue({ rowCount: 1 }) } as unknown as pg.Pool, [{ id: 1, lat: 1, lng: 2 }, { id: 2, lat: 1.00001, lng: 2 }], 'owner');
  await vi.runAllTimersAsync(); await work;
  expect(times).toHaveLength(4); for (let i=1;i<times.length;i++) expect(times[i]! - times[i-1]!).toBeGreaterThanOrEqual(1000);
});

it('缺少街道时不能用12号后缀把附近112号商户识别为所在店', () => {
  expect(parseAmapPlace({ addressComponent: { streetNumber: { number: '12号' } }, pois: [
    { id: 'shop', name: '另一栋商户', address: '测试路112号', type: '购物服务;便利店', distance: '10' }] }).shop_name).toBeNull();
});

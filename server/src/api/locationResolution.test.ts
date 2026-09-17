import express from 'express';
import request from 'supertest';
import type pg from 'pg';
import { afterEach, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';
import { resolveMissingAddresses, addressResolution } from '../lib/geocoder.js';

vi.mock('../lib/geocoder.js', () => ({ resolveMissingAddresses: vi.fn(), addressResolution: vi.fn() }));
afterEach(() => vi.resetAllMocks());
it('位置接口不等待解析完成，返回明确状态并仅调度当前用户空地址记录', async () => {
  const userId = '00000000-0000-4000-8000-000000000001';
  const rows = [
    { id: '1', latitude: '1', longitude: '2', address: null },
    { id: '2', latitude: '3', longitude: '4', address: '已有地址' },
  ];
  const pool = { query: vi.fn().mockResolvedValue({ rows }) } as unknown as pg.Pool;
  vi.mocked(resolveMissingAddresses).mockImplementation(() => new Promise(() => {}));
  vi.mocked(addressResolution).mockReturnValue({ address_status: 'failed', address_error: '地址服务请求失败或超时', address_retry_at: '2026-09-17T01:01:00.000Z' });
  const app = express();
  app.use((_req, res, next) => { res.locals.userId = userId; next(); });
  app.use(createDashboardRouter(pool));
  const response = await request(app).get('/locations');
  expect(response.status).toBe(200);
  expect(resolveMissingAddresses).toHaveBeenCalledWith(pool, [{ id: '1', lat: 1, lng: 2 }], userId);
  expect(response.body.locations[0]).toMatchObject({ address: null, address_status: 'failed', address_error: '地址服务请求失败或超时', address_retry_at: '2026-09-17T01:01:00.000Z' });
  expect(response.body.locations[1]).toMatchObject({ address: '已有地址', address_status: 'resolved', address_error: null, address_retry_at: null });
  expect(addressResolution).toHaveBeenCalledTimes(1);
});

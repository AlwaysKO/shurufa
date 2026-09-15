import express from 'express';
import type pg from 'pg';
import request from 'supertest';
import { afterEach, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';

afterEach(() => vi.unstubAllGlobals());

it('events 首次响应直接包含解析完成的地址，无需再次刷新', async () => {
  const ip = '116.22.49.197';
  const query = vi.fn(async (sql: string) => {
    if (sql.includes('COUNT(*)')) return { rows: [{ total: 2 }] };
    if (sql.includes('SELECT id,')) return { rows: [
      { id: '1', client_ip: ip, ip_location: null },
      { id: '2', client_ip: ip, ip_location: null },
    ] };
    return { rows: [] };
  });
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ status: 'success', city: '广州市' }) }));
  vi.stubGlobal('fetch', fetchMock);
  const app = express();
  app.use(createDashboardRouter({ query } as unknown as pg.Pool));
  const response = await request(app).get('/events?user_id=test');
  expect(response.status).toBe(200);
  expect(response.body.items.map((r: { ip_location: string }) => r.ip_location)).toEqual(['广州市', '广州市']);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it } from 'vitest';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { createApp } from '../app.js';

let pool: pg.Pool;
const userId = '00000000-0000-4000-8000-000000000001';

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(new URL('../../migrations/020_runtime_settings.sql', import.meta.url), 'utf8'));
});

describe('collector runtime setting', () => {
  it('后台保存HTTPS origin后移动端读取同一个规范地址', async () => {
    const dashboard = await authenticatedRequest(createApp(pool));
    const saved = await dashboard.put(`/api/v1/dashboard/settings/collector?user_id=${userId}`)
      .send({ collector_base_url: ' https://new.example.com/ ' });
    expect(saved.status).toBe(200);
    expect(saved.body).toEqual({ ok: true, collector_base_url: 'https://new.example.com' });

    const mobile = await request(createApp(pool)).get('/api/v1/mobile/config').set('X-Device-Id', userId);
    expect(mobile.status).toBe(200);
    expect(mobile.body).toEqual({ collector_base_url: 'https://new.example.com' });
  });

  it('拒绝HTTP和带业务路径的地址且不覆盖原设置', async () => {
    const dashboard = await authenticatedRequest(createApp(pool));
    await dashboard.put(`/api/v1/dashboard/settings/collector?user_id=${userId}`)
      .send({ collector_base_url: 'https://good.example' });
    for (const value of ['http://bad.example', 'https://bad.example/api']) {
      const response = await dashboard.put(`/api/v1/dashboard/settings/collector?user_id=${userId}`)
        .send({ collector_base_url: value });
      expect(response.status).toBe(400);
    }
    const current = await dashboard.get(`/api/v1/dashboard/settings/collector?user_id=${userId}`);
    expect(current.body.collector_base_url).toBe('https://good.example');
  });
});


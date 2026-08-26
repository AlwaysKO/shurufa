import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { isLoopbackAddress } from '../lib/requestIdentity.js';

let pool: pg.Pool;
const ids = Array.from({ length: 25 }, (_, index) =>
  `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
);

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(`CREATE TABLE device (
    id UUID PRIMARY KEY, name TEXT, platform TEXT, model TEXT, os_version TEXT,
    app_version TEXT, brand TEXT, sdk_int INT, screen_resolution TEXT, locale TEXT,
    region TEXT, hardware TEXT, rom_version TEXT, ram_mb INT,
    dashboard_name TEXT, tags TEXT,
    first_seen_at TIMESTAMPTZ DEFAULT NOW(), last_seen_at TIMESTAMPTZ DEFAULT NOW()
  )`);
  for (const [index, id] of ids.entries()) {
    await pool.query(`INSERT INTO device(id,name,platform,model,brand,last_seen_at)
      VALUES($1,$2,'android',$3,$4,$5)`, [
      id, `用户手机 ${String(index + 1).padStart(2, '0')}`, `Model-${index + 1}`,
      index === 24 ? 'HONOR' : 'Test', new Date(Date.UTC(2026, 7, index + 1)),
    ]);
  }
});

describe('dashboard user directory', () => {
  it('用户目录只允许后台本机访问', () => {
    expect(isLoopbackAddress('127.0.0.1')).toBe(true);
    expect(isLoopbackAddress('::1')).toBe(true);
    expect(isLoopbackAddress('::ffff:127.0.0.1')).toBe(true);
    expect(isLoopbackAddress('192.168.1.20')).toBe(false);
  });

  it('无需当前用户即可分页列出用户', async () => {
    const response = await request(createApp(pool))
      .get('/api/v1/dashboard/users?page=2&page_size=10');
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ total: 25, page: 2, page_size: 10 });
    expect(response.body.users).toHaveLength(10);
  });

  it('支持名称、品牌、型号、设备 ID 搜索和精确 ID 查询', async () => {
    const byBrand = await request(createApp(pool)).get('/api/v1/dashboard/users?q=HONOR');
    const byModel = await request(createApp(pool)).get('/api/v1/dashboard/users?q=Model-25');
    const exact = await request(createApp(pool)).get(`/api/v1/dashboard/users?id=${ids[24]}`);
    expect(byBrand.body.users.map((row: { id: string }) => row.id)).toEqual([ids[24]]);
    expect(byModel.body.users.map((row: { id: string }) => row.id)).toEqual([ids[24]]);
    expect(exact.body.users.map((row: { id: string }) => row.id)).toEqual([ids[24]]);
  });

  it('支持设置易识别的用户名称和标签并参与搜索', async () => {
    const app = createApp(pool);
    const updated = await request(app)
      .patch(`/api/v1/dashboard/users/${ids[24]}?user_id=${ids[0]}`)
      .send({ dashboard_name: '上海客服 025', tags: '上海,客服,VIP' });
    const searched = await request(app).get('/api/v1/dashboard/users?q=VIP');
    expect(updated.status).toBe(200);
    expect(updated.body.user).toMatchObject({ id: ids[24], dashboard_name: '上海客服 025', tags: '上海,客服,VIP' });
    expect(searched.body.users.map((row: { id: string }) => row.id)).toEqual([ids[24]]);
  });

  it('设备接口只返回当前用户自己的设备', async () => {
    const response = await request(createApp(pool))
      .get(`/api/v1/dashboard/devices?user_id=${ids[24]}`);
    expect(response.status).toBe(200);
    expect(response.body.devices.map((row: { id: string }) => row.id)).toEqual([ids[24]]);
  });
});

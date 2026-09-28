import express from 'express';
import request from 'supertest';
import type pg from 'pg';
import { expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';

it('来源选项读取当前用户全部历史非空包名并补全名称，不受分页和日期限制', async () => {
  const query = vi.fn()
    .mockResolvedValueOnce({ rows: [{ package_name: 'com.example.chat' }, { package_name: 'com.example.older' }] })
    .mockResolvedValueOnce({ rows: [{ package_name: 'com.example.chat', app_name: '聊天' }] });
  const app = express();
  app.use((_req, res, next) => { res.locals.userId = 'owner'; next(); });
  app.use(createDashboardRouter({ query } as unknown as pg.Pool));
  const response = await request(app).get('/event-apps?user_id=someone-else');
  expect(response.status).toBe(200);
  expect(response.body.apps).toEqual([
    { package_name: 'com.example.chat', app_name: '聊天' },
    { package_name: 'com.example.older', app_name: null },
  ]);
  expect(query.mock.calls[0][0]).toMatch(/DISTINCT package_name/);
  expect(query.mock.calls[0][0]).toContain("btrim(package_name) <> ''");
  expect(query.mock.calls[0][0]).not.toMatch(/occurred_at|LIMIT/);
  expect(query.mock.calls[0][1]).toEqual(['owner']);
  expect(query.mock.calls[1][1]).toEqual(['owner', ['com.example.chat', 'com.example.older']]);
});

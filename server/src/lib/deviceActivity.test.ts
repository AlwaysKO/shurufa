import express from 'express';
import request from 'supertest';
import type pg from 'pg';
import { describe, expect, it, vi } from 'vitest';
import { recordDeviceActivity } from './deviceActivity.js';
import { requireMobileIdentity } from './requestIdentity.js';

const id = '00000000-0000-4000-8000-000000000001';
function setup(fail = false) {
  const query = fail ? vi.fn().mockRejectedValue(new Error('offline')) : vi.fn().mockResolvedValue({ rowCount: 1 });
  const app = express();
  app.use(express.json());
  app.use('/api/v1/mobile', requireMobileIdentity, recordDeviceActivity({ query } as unknown as pg.Pool));
  app.all('/api/v1/mobile/*', (req, res) => res.status(Number(req.query.status ?? 200)).json({ ok: true, received: 1 }));
  app.get('/api/v1/dashboard/users', (_req, res) => res.json({ users: [] }));
  return { app, query };
}

describe('device last activity', () => {
  it.each(['/app-usage/batch', '/chat/assets', '/chat/messages/batch', '/navigation-records',
    '/reports', '/events/batch', '/call-recordings/call-log/batch'])(
    '成功上报%s更新身份对应设备，业务回执不变', async path => {
      const { app, query } = setup();
      const result = await request(app).post('/api/v1/mobile' + path).set('X-Device-Id', id).send({});
      expect(result.body).toEqual({ ok: true, received: 1 });
      await expect.poll(() => query.mock.calls.length).toBe(1);
      expect(query.mock.calls[0][1]).toEqual([id]);
    });
  it('录音PUT同样更新；服务器错误、未知接口、身份错误和GET不更新', async () => {
    const { app, query } = setup();
    await request(app).put('/api/v1/mobile/call-recordings/example').set('X-Device-Id', id);
    await expect.poll(() => query.mock.calls.length).toBe(1);
    query.mockClear();
    for (const status of [400, 401, 404, 409, 500]) {
      await request(app).post(`/api/v1/mobile/reports?status=${status}`).set('X-Device-Id', id);
    }
    await request(app).post('/api/v1/mobile/reports').set('X-Device-Id', 'invalid');
    await request(app).get('/api/v1/mobile/config').set('X-Device-Id', id);
    await request(app).get('/api/v1/dashboard/users');
    expect(query).not.toHaveBeenCalled();
  });
  it('目录辅助状态失败不改变已成功业务回执，错误可诊断', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const { app, query } = setup(true);
      const result = await request(app).post('/api/v1/mobile/reports').set('X-Device-Id', id);
      expect(result.status).toBe(200);
      await expect.poll(() => query.mock.calls.length).toBe(1);
      expect(error).toHaveBeenCalled();
    } finally { error.mockRestore(); }
  });
});

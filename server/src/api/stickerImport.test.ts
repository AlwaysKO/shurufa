import request from 'supertest';
import express from 'express';
import type pg from 'pg';
import { expect, it, vi, afterEach } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { createStickerImportAgentRouter, createImportGate } from './stickerImport.js';
import { ImportJobs } from '../stickers/importJobs.js';
afterEach(() => vi.restoreAllMocks());
it('后台沿用登录与CSRF，助手令牌不授权关键词且不用选择手机', async () => {
  const pool = { query: vi.fn(), connect: vi.fn() } as unknown as pg.Pool;
  vi.spyOn(ImportJobs.prototype, 'createPairing').mockResolvedValue({
    code: 'abc',
    expiresAt: new Date(),
  });
  const app = createApp(pool);
  expect((await request(app).post('/api/v1/dashboard/sticker-import/pairings')).status).toBe(401);
  const agent = await authenticatedRequest(app);
  expect((await agent.post('/api/v1/dashboard/sticker-import/pairings')).status).toBe(201);
  expect(
    (await agent.post('/api/v1/dashboard/sticker-import/pairings').set('X-Dashboard-Request', ''))
      .status,
  ).toBe(403);
  expect(
    (
      await request(app)
        .patch('/api/v1/dashboard/sticker-materials/' + 'a'.repeat(64) + '/keywords')
        .set('Authorization', 'Bearer sfi_' + 'a'.repeat(64))
        .send({ add: ['你好'] })
    ).status,
  ).toBe(401);
});
it('助手错误配对全局限速不能通过伪造XFF绕过，错误不回显SQL/令牌', async () => {
  vi.spyOn(ImportJobs.prototype, 'pair').mockRejectedValue(
    new Error('postgres token SECRET https://cdn.private/path'),
  );
  const app = express();
  app.set('trust proxy', true);
  app.use(createStickerImportAgentRouter({} as pg.Pool));
  for (let i = 0; i < 10; i++) {
    const r = await request(app)
      .post('/pair')
      .set('X-Forwarded-For', `192.0.2.${i}`)
      .send({ code: 'a'.repeat(16), name: '电脑' });
    expect(r.status).toBe(500);
    expect(r.text).not.toContain('SECRET');
  }
  expect(
    (
      await request(app)
        .post('/pair')
        .set('X-Forwarded-For', '1.1.1.1')
        .send({ code: 'a'.repeat(16), name: '电脑' })
    ).status,
  ).toBe(429);
});
it('请求门限有界并拒绝远程路径命令字段', async () => {
  const app = express();
  app.use(createImportGate({ requests: 2, pairings: 2, concurrent: 1 }));
  app.get('/ok', (_q, r) => r.send('ok'));
  expect((await request(app).get('/ok')).status).toBe(200);
  expect((await request(app).get('/ok')).status).toBe(200);
  expect((await request(app).get('/ok')).status).toBe(429);
  const pair = vi.spyOn(ImportJobs.prototype, 'pair');
  const other = express();
  other.use(createStickerImportAgentRouter({} as pg.Pool, createImportGate()));
  expect(
    (
      await request(other)
        .post('/pair')
        .send({ code: 'a'.repeat(16), name: '电脑', path: 'C:/private' })
    ).status,
  ).toBe(400);
  expect(pair).not.toHaveBeenCalled();
});
it('上传使用独立Bearer与租约，提交后归档失败仍返回成功且不暴露原始错误', async () => {
  const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
  const upload = vi.spyOn(ImportJobs.prototype, 'upload').mockResolvedValue({
    status: 'imported',
    material: {
      sha256: 'a'.repeat(64),
      ids: [1],
      keywords: [],
      url: '/uploads/stickers/a.gif',
      format: 'gif',
      width: 1,
      height: 1,
      assigned: false,
    },
  });
  const bundle = await import('../stickers/bundle.js');
  vi.spyOn(bundle, 'publishStickerBundle').mockRejectedValue(new Error('private path SECRET'));
  const app = express();
  app.use(createStickerImportAgentRouter({} as pg.Pool, createImportGate()));
  const url = '/jobs/00000000-0000-4000-8000-000000000001/materials';
  expect(
    (await request(app).post(url).set('Content-Type', 'application/octet-stream').send(gif)).status,
  ).toBe(401);
  expect(upload).not.toHaveBeenCalled();
  const response = await request(app)
    .post(url)
    .query({ filename: 'a.gif', sha256: 'a'.repeat(64) })
    .set('Authorization', 'Bearer sfi_' + 'b'.repeat(64))
    .set('X-Import-Lease', 'c'.repeat(64))
    .set('Content-Type', 'application/octet-stream')
    .send(gif);
  expect(response.status).toBe(201);
  expect(response.body.archiveWarning).toBe(true);
  expect(response.text).not.toContain('SECRET');
  expect(response.headers['cache-control']).toBe('no-store');
  expect(upload).toHaveBeenCalledWith(
    'sfi_' + 'b'.repeat(64),
    '00000000-0000-4000-8000-000000000001',
    'c'.repeat(64),
    { buffer: gif, filename: 'a.gif', sha256: 'a'.repeat(64) },
  );
});
it('客户端断开连接不会释放仍在执行的后台任务并发名额', async () => {
  let started!: () => void, finish!: () => void;
  const entered = new Promise<void>((resolve) => {
    started = resolve;
  });
  const pending = new Promise<void>((resolve) => {
    finish = resolve;
  });
  vi.spyOn(ImportJobs.prototype, 'heartbeat')
    .mockImplementationOnce(async () => {
      started();
      await pending;
      return { ok: true };
    })
    .mockResolvedValue({ ok: true });
  const app = express();
  app.use(
    createStickerImportAgentRouter(
      {} as pg.Pool,
      createImportGate({ requests: 20, pairings: 10, concurrent: 1 }),
    ),
  );
  const first = request(app)
    .post('/heartbeat')
    .set('Authorization', 'Bearer sfi_' + 'a'.repeat(64));
  const running = first.then(
    () => undefined,
    () => undefined,
  );
  await entered;
  first.abort();
  await new Promise((resolve) => setTimeout(resolve, 20));
  try {
    expect(
      (
        await request(app)
          .post('/heartbeat')
          .set('Authorization', 'Bearer sfi_' + 'a'.repeat(64))
      ).status,
    ).toBe(429);
  } finally {
    finish();
    await running;
  }
});
it('配对大小写和尾斜杠别名共用同一低额度桶', async () => {
  vi.spyOn(ImportJobs.prototype, 'pair').mockResolvedValue({
    token: 'token',
    agent: { id: '00000000-0000-4000-8000-000000000001', name: '电脑' },
  });
  const app = express();
  app.use(
    createStickerImportAgentRouter(
      {} as pg.Pool,
      createImportGate({ requests: 20, pairings: 2, concurrent: 8 }),
    ),
  );
  for (const path of ['/PAIR', '/pair/'])
    expect(
      (
        await request(app)
          .post(path)
          .send({ code: 'a'.repeat(16), name: '电脑' })
      ).status,
    ).toBe(201);
  expect(
    (
      await request(app)
        .post('/pair')
        .send({ code: 'a'.repeat(16), name: '电脑' })
    ).status,
  ).toBe(429);
});

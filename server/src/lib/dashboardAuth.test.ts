import request from 'supertest';
import { readFileSync } from 'node:fs';
import type pg from 'pg';
import { afterEach, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
const pool = { query: vi.fn(async () => ({ rows: [], rowCount: 0 })) } as unknown as pg.Pool;
afterEach(() => vi.unstubAllEnvs());
it('匿名后台和网页私有资源拒绝，手机健康接口不受影响', async () => {
  const app = createApp(pool);
  expect((await request(app).get('/api/v1/dashboard/users')).status).toBe(401);
  expect((await request(app).get('/uploads/stickers/test.png?user_id=00000000-0000-4000-8000-000000000001')).status).toBe(401);
  expect((await request(app).get('/health')).status).toBe(200);
});
it('登录、会话、退出和伪造 cookie', async () => {
  const app = createApp(pool); const agent = request.agent(app);
  expect((await agent.get('/api/v1/auth/session')).status).toBe(401);
  const login = await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  expect(login.status).toBe(200);
  expect(login.headers['set-cookie'][0]).toContain('HttpOnly');
  expect(login.headers['set-cookie'][0]).toContain('SameSite=Strict');
  expect((await agent.get('/api/v1/auth/session')).body.username).toBe('admin');
  expect((await agent.get('/api/v1/dashboard/chat/conversations')).status).toBe(400);
  expect((await agent.post('/api/v1/auth/logout').set('X-Dashboard-Request', '1')).status).toBe(204);
  expect((await agent.get('/api/v1/auth/session')).status).toBe(401);
  expect((await request(app).get('/api/v1/auth/session').set('Cookie', 'dashboard_session=fake')).status).toBe(401);
});
it('跨站登录和无自定义头写请求拒绝', async () => {
  const app = createApp(pool);
  expect((await request(app).post('/api/v1/auth/login').send({ username: 'admin', password: 'adminhaha' })).status).toBe(403);
  expect((await request(app).post('/api/v1/auth/login').set('X-Dashboard-Request', '1').set('Origin', 'https://evil.test').send({ username: 'admin', password: 'adminhaha' })).status).toBe(403);
});
it('错误密码统一响应并限制连续尝试', async () => {
  const app = createApp(pool);
  for (let i = 0; i < 5; i++) expect((await request(app).post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'wrong' })).status).toBe(401);
  expect((await request(app).post('/api/v1/auth/login').set('X-Dashboard-Request', '1').set('X-Forwarded-For', '8.8.8.8').send({ username: 'admin', password: 'adminhaha' })).status).toBe(429);
});
it('生产不使用默认密码且 cookie Secure', async () => {
  vi.stubEnv('NODE_ENV', 'production'); vi.stubEnv('DASHBOARD_USERNAME', ''); vi.stubEnv('DASHBOARD_PASSWORD', '');
  expect(() => createApp(pool)).toThrow(/DASHBOARD/);
  vi.stubEnv('DASHBOARD_USERNAME', 'owner'); vi.stubEnv('DASHBOARD_PASSWORD', 'a-unique-long-password-123');
  const response = await request(createApp(pool)).post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'owner', password: 'a-unique-long-password-123' });
  expect(response.status).toBe(200); expect(response.headers['set-cookie'][0]).toContain('Secure');
});
it('会话到期后拒绝访问', async () => {
  const app = createApp(pool); const agent = request.agent(app);
  await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  const spy = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 9 * 60 * 60 * 1000);
  try { expect((await agent.get('/api/v1/auth/session')).status).toBe(401); } finally { spy.mockRestore(); }
});

it('部署模板保留管理页面 Host，使同源 HTTPS 登录经过反代仍可成功', async () => {
  vi.stubEnv('DASHBOARD_ORIGIN', '');
  const template = readFileSync(new URL('../../../deploy/nginx/shurufa.conf', import.meta.url), 'utf8');
  const dashboard = template.split('# 数据管理后台')[1]!;
  const configuredHost = dashboard.match(/proxy_set_header Host ([^;]+);/)![1];
  const browserHost = 'mymanage.dog8ball.com';
  const response = await request(createApp(pool)).post('/api/v1/auth/login')
    .set('Host', configuredHost === '$host' ? browserHost : configuredHost)
    .set('X-Forwarded-Proto', 'https').set('Origin', `https://${browserHost}`)
    .set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  expect(response.status).toBe(200);
});
it('显式 Origin 使用网页域名，允许 API 上游 Host 与网页域名不同', async () => {
  vi.stubEnv('DASHBOARD_ORIGIN', 'https://mymanage.dog8ball.com');
  const response = await request(createApp(pool)).post('/api/v1/auth/login')
    .set('Host', 'myapi.dog8ball.com').set('X-Forwarded-Proto', 'https')
    .set('Origin', 'https://mymanage.dog8ball.com').set('X-Dashboard-Request', '1')
    .send({ username: 'admin', password: 'adminhaha' });
  expect(response.status).toBe(200);
});

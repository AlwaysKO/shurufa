import request from 'supertest';
import type { Express } from 'express';

/** 集成测试通过真实登录取得 cookie，不绕过生产中间件。 */
export async function authenticatedRequest(app: Express) {
  const agent = request.agent(app);
  const response = await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1')
    .send({ username: 'admin', password: 'adminhaha' });
  if (response.status !== 200) throw new Error(`Test login failed: ${response.status}`);
  agent.set('X-Dashboard-Request', '1');
  return agent;
}

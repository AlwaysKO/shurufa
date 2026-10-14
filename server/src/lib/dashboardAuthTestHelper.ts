import request from 'supertest';
import type { Express } from 'express';
import type pg from 'pg';
const preparedPools = new WeakSet<pg.Pool>();

/** 集成测试通过真实登录取得 cookie，不绕过生产中间件。 */
export async function authenticatedRequest(app: Express, pool: pg.Pool) {
  // 业务集成测试只加载各自迁移；在夹具中补认证基础表，仍通过真实INSERT和HTTP登录。
  // 完整迁移约束由 loginAudit.test.ts 覆盖。
  if (!preparedPools.has(pool)) {
    await pool.query(`CREATE TABLE IF NOT EXISTS dashboard_login_audit (
      id BIGSERIAL PRIMARY KEY, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      username TEXT NOT NULL, authenticated_username TEXT, result TEXT NOT NULL,
      ip TEXT NOT NULL, peer_ip TEXT NOT NULL, ip_source TEXT NOT NULL, user_agent TEXT NOT NULL
    )`);
    preparedPools.add(pool);
  }
  const agent = request.agent(app);
  const response = await agent.post('/api/v1/auth/login').set('X-Dashboard-Request', '1')
    .send({ username: 'admin', password: 'adminhaha' });
  if (response.status !== 200) throw new Error(`Test login failed: ${response.status}`);
  agent.set('X-Dashboard-Request', '1');
  return agent;
}

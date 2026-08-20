import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import type pg from 'pg';
import { createMobileRouter } from './api/mobile.js';
import { createDashboardRouter } from './api/dashboard.js';

export function createApp(pool: pg.Pool): express.Express {
  const app = express();
  app.set('trust proxy', true); // 反代后 X-Forwarded-For 生效（事件记录客户端 IP）
  app.use(cors());
  app.use(express.json({ limit: '10mb' }));

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  // 输入法端 API
  app.use('/api/v1/mobile', createMobileRouter(pool));

  // Dashboard API
  app.use('/api/v1/dashboard', createDashboardRouter(pool));

  // 统一错误处理
  app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    console.error('[error]', err);
    res.status(500).json({ error: 'internal_error', message: err.message });
  });

  return app;
}

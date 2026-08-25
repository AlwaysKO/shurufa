import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import type pg from 'pg';
import { createMobileRouter } from './api/mobile.js';
import { createDashboardRouter } from './api/dashboard.js';
import { createMobileStickerRouter, createDashboardStickerRouter } from './api/stickers.js';
import { createMobilePhraseRouter, createDashboardPhraseRouter } from './api/userPhrases.js';
import { createMobileChatCaptureRouter } from './api/chatCapture.js';
import { createChatDashboardRouter } from './api/chatDashboard.js';
import { createMobileRelationshipsRouter } from './api/mobileRelationships.js';
import { createRelationshipDashboardRouter } from './api/relationshipDashboard.js';
import {
  createMobileExpressionRouter,
  expressionAssetRoot,
  requireExpressionAssetIdentity,
} from './api/expressions.js';

export function createApp(pool: pg.Pool): express.Express {
  const app = express();
  app.set('trust proxy', true); // 反代后 X-Forwarded-For 生效（事件记录客户端 IP）
  app.use(cors());
  app.use(express.json({ limit: '10mb' }));

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  // 表情包图片静态目录（server/uploads/stickers）
  const stickerDir = join(process.cwd(), 'uploads', 'stickers');
  mkdirSync(stickerDir, { recursive: true });
  app.use(
    '/uploads/expression',
    requireExpressionAssetIdentity,
    express.static(expressionAssetRoot()),
  );
  app.use('/uploads', express.static(join(process.cwd(), 'uploads')));

  // 输入法端 API
  app.use('/api/v1/mobile', createMobileRouter(pool));
  app.use('/api/v1/mobile', createMobileStickerRouter(pool));
  app.use('/api/v1/mobile', createMobilePhraseRouter(pool));
  app.use('/api/v1/mobile/chat', createMobileChatCaptureRouter(pool));
  app.use('/api/v1/mobile/relationships', createMobileRelationshipsRouter(pool));
  app.use('/api/v1/mobile/expressions', createMobileExpressionRouter(pool));

  // Dashboard API
  app.use('/api/v1/dashboard', createDashboardRouter(pool));
  app.use('/api/v1/dashboard', createDashboardStickerRouter(pool));
  app.use('/api/v1/dashboard', createDashboardPhraseRouter(pool));
  app.use('/api/v1/dashboard/chat', createChatDashboardRouter(pool));
  app.use('/api/v1/dashboard/relationships', createRelationshipDashboardRouter(pool));

  // 统一错误处理
  app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    console.error('[error]', err);
    res.status(500).json({ error: 'internal_error', message: err.message });
  });

  return app;
}

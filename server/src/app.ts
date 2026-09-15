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
import type { RemoteExpressionSearch } from './expression/remoteSearch.js';
import { requireDashboardIdentity, requireMobileIdentity } from './lib/requestIdentity.js';
import { createDashboardAuth } from './lib/dashboardAuth.js';
import { authorizeUpload } from './lib/uploadAuthorization.js';
import { DeepSeekProvider, type RelationshipAiProvider } from './ai/deepSeekProvider.js';
import {
  createMobileRelationshipAiRouter,
  createRelationshipAiDashboardRouter,
} from './api/relationshipAi.js';

export interface CreateAppOptions {
  remoteExpressionSearch?: RemoteExpressionSearch | null;
  relationshipAiProvider?: RelationshipAiProvider;
  relationshipAiMinMessages?: number;
}

export function createApp(pool: pg.Pool, options: CreateAppOptions = {}): express.Express {
  const app = express();
  app.set('trust proxy', true); // 反代后 X-Forwarded-For 生效（事件记录客户端 IP）
  const auth = createDashboardAuth();
  const relationshipAiProvider = options.relationshipAiProvider ?? new DeepSeekProvider();
  app.use('/api/v1/mobile', cors());
  app.use(express.json({ limit: '10mb' }));

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  app.use('/api/v1/auth', auth.router);

  // 网页私有资源必须登录；移动客户端保留设备头协议。
  app.use('/uploads', (req, res, next) => {
    if (req.get('X-Device-Id')) { next(); return; }
    auth.requireSession(req, res, next);
  });

  // 表情包图片静态目录（server/uploads/stickers）
  const stickerDir = join(process.cwd(), 'uploads', 'stickers');
  mkdirSync(stickerDir, { recursive: true });
  app.use(
    '/uploads/expression',
    (req, res, next) => req.get('X-Device-Id')
      ? requireExpressionAssetIdentity(req, res, next)
      : requireDashboardIdentity(req, res, next),
    express.static(expressionAssetRoot()),
  );
  app.use('/uploads', authorizeUpload(pool), express.static(join(process.cwd(), 'uploads')));

  // 输入法端 API
  app.use('/api/v1/mobile', requireMobileIdentity);
  app.use('/api/v1/mobile', createMobileRouter(pool));
  app.use('/api/v1/mobile', createMobileStickerRouter(pool));
  app.use('/api/v1/mobile', createMobilePhraseRouter(pool));
  app.use('/api/v1/mobile/chat', createMobileChatCaptureRouter(pool));
  app.use('/api/v1/mobile/relationships', createMobileRelationshipsRouter(pool));
  app.use('/api/v1/mobile/relationships', createMobileRelationshipAiRouter(pool, relationshipAiProvider));
  app.use(
    '/api/v1/mobile/expressions',
    options.remoteExpressionSearch === undefined
      ? createMobileExpressionRouter(pool)
      : createMobileExpressionRouter(pool, options.remoteExpressionSearch),
  );

  // Dashboard API
  app.use('/api/v1/dashboard', auth.requireSession, auth.protectWrite, requireDashboardIdentity);
  app.use('/api/v1/dashboard', createDashboardRouter(pool));
  app.use('/api/v1/dashboard', createDashboardStickerRouter(pool));
  app.use('/api/v1/dashboard', createDashboardPhraseRouter(pool));
  app.use('/api/v1/dashboard/chat', createChatDashboardRouter(pool));
  app.use('/api/v1/dashboard/relationships', createRelationshipDashboardRouter(pool));
  app.use('/api/v1/dashboard/relationships', createRelationshipAiDashboardRouter(
    pool,
    relationshipAiProvider,
    options.relationshipAiMinMessages,
  ));

  // 统一错误处理
  app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    console.error('[error]', err);
    res.status(500).json({ error: 'internal_error', message: err.message });
  });

  return app;
}

import { Router } from 'express';
import type pg from 'pg';
import { CaptureConflict, CaptureValidation, readCaptureState, updateCaptureState, validCaptureRules } from '../lib/chatCaptureConfig.js';
export function createMobileCaptureRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/chat-capture-config', async (_req,res,next) => { try { res.set('Cache-Control','no-store').json((await readCaptureState(pool)).current); } catch(error) { next(error); } });
  return router;
}
export function createDashboardCaptureRouter(pool: pg.Pool): Router {
  const router = Router();
  const path = '/settings/chat-capture';
  router.get(path, async (_req,res,next) => { try { res.set('Cache-Control','no-store').json(await readCaptureState(pool)); } catch(error) { next(error); } });
  for (const rollback of [false,true]) {
    router[rollback ? 'post' : 'put'](path + (rollback ? '/rollback' : ''), async (req,res,next) => {
      const b = req.body;
      const keys = rollback ? ['expectedRevision','revision'] : ['expectedRevision','rules'];
      if (!b || typeof b !== 'object' || Object.keys(b).sort().join(',') !== keys.sort().join(',') || Buffer.byteLength(JSON.stringify(b)) > 32768 || !Number.isSafeInteger(b.expectedRevision) || b.expectedRevision < 0 || (rollback ? !Number.isSafeInteger(b.revision) || b.revision < 0 : !validCaptureRules(b.rules))) {
        res.status(400).json({error:'invalid_capture_config',message:'采集配置字段、大小或规则不合法'}); return;
      }
      try { res.json(await updateCaptureState(pool,b.expectedRevision,rollback ? undefined : b.rules,rollback ? b.revision : undefined)); }
      catch(error) {
        if (error instanceof CaptureConflict) res.status(409).json({error:'revision_conflict',message:'配置已被其他管理员更新，请重新加载后修改'});
        else if(error instanceof CaptureValidation) res.status(400).json({error:'invalid_capture_config',message:'规则非法或回滚版本已不在历史中'});
        else next(error);
      }
    });
  }
  return router;
}

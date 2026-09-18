import { Router } from 'express';
import type pg from 'pg';
import { DeliveryConflict, DeliveryValidation, readDeliveryState, updateDeliveryState, validDeliveryRules } from '../lib/expressionDelivery.js';
export function createMobileDeliveryRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/expression-delivery', async (_req,res,next) => { try { res.set('Cache-Control','no-store').json((await readDeliveryState(pool)).current); } catch(error) { next(error); } });
  return router;
}
export function createDashboardDeliveryRouter(pool: pg.Pool): Router {
  const router = Router();
  const path = '/settings/expression-delivery';
  router.get(path, async (_req,res,next) => { try { res.set('Cache-Control','no-store').json(await readDeliveryState(pool)); } catch(error) { next(error); } });
  for (const rollback of [false,true]) {
    router[rollback ? 'post' : 'put'](path + (rollback ? '/rollback' : ''), async (req,res,next) => {
      const b = req.body;
      const keys = rollback ? ['expectedRevision','revision'] : ['expectedRevision','rules'];
      if (!b || typeof b !== 'object' || Object.keys(b).sort().join(',') !== keys.sort().join(',') || Buffer.byteLength(JSON.stringify(b)) > 65536 || !Number.isSafeInteger(b.expectedRevision) || b.expectedRevision < 0 || (rollback ? !Number.isSafeInteger(b.revision) || b.revision < 0 : !validDeliveryRules(b.rules))) {
        res.status(400).json({error:'invalid_delivery_config',message:'发送配置字段、大小或规则不合法'}); return;
      }
      try { res.json(await updateDeliveryState(pool,b.expectedRevision,rollback ? undefined : b.rules,rollback ? b.revision : undefined)); }
      catch(error) {
        if (error instanceof DeliveryConflict) res.status(409).json({error:'revision_conflict',message:'配置已被其他管理员更新，请重新加载后修改'});
        else if(error instanceof DeliveryValidation) res.status(400).json({error:'invalid_delivery_config',message:'规则非法或回滚版本已不在历史中'});
        else next(error);
      }
    });
  }
  return router;
}

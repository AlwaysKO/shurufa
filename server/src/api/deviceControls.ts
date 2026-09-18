import { Router } from 'express';
import type pg from 'pg';
import { deviceSavingKey } from '../lib/deviceSaving.js';
import { deleteDeviceData, DeviceDeletionError } from '../lib/deleteDeviceData.js';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
export function createDeviceControlsRouter(pool: pg.Pool) {
  const router = Router();
  router.post('/users/:id/saving', async (req, res, next) => {
    if (!UUID.test(req.params.id) || typeof req.body?.save_uploads !== 'boolean') { res.status(400).json({ error: '设备ID或保存开关无效' }); return; }
    const id = req.params.id.toLowerCase();
    try {
      const result = await pool.query(`INSERT INTO runtime_setting(key,value,updated_at)
        SELECT $2,$3,NOW() FROM device WHERE id=$1
        ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=NOW() RETURNING key`, [id, deviceSavingKey(id), String(req.body.save_uploads)]);
      if (!result.rowCount) { res.status(404).json({ error: '手机不存在，请刷新列表' }); return; }
      res.json({ id, save_uploads: req.body.save_uploads });
    } catch (error) { next(error); }
  });
  router.post('/users/:id/delete', async (req, res, next) => {
    if (!UUID.test(req.params.id) || req.body?.confirm !== 'DELETE') { res.status(400).json({ error: '必须明确确认要删除的手机' }); return; }
    try { res.json(await deleteDeviceData(pool, req.params.id.toLowerCase())); }
    catch (error) {
      if (error instanceof DeviceDeletionError) res.status(error.status).json({ error: error.message });
      else next(error);
    }
  });
  return router;
}

import type { RequestHandler } from 'express';
import type pg from 'pg';

/** 最近活跃是设备成功上报的服务器时间，不代表手机正在打字或所有队列已传完。 */
export function recordDeviceActivity(pool: pg.Pool): RequestHandler {
  return (req, res, next) => {
    // 包含关闭保存时的注册回执；查询/轮询与后台浏览不算新上报。
    if (!['POST', 'PUT', 'PATCH'].includes(req.method)) {
      next(); return;
    }
    const userId = res.locals.userId;
    res.once('finish', () => {
      if (res.statusCode < 200 || res.statusCode >= 300) return;
      // 不阻塞业务回执，也不复活被删除的设备；并发旧事务不能使时间倒退。
      void pool.query('UPDATE device SET last_seen_at = GREATEST(last_seen_at, NOW()) WHERE id = $1', [userId])
        .catch(error => console.error('[device-activity] update failed', error));
    });
    next();
  };
}

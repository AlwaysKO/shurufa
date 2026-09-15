-- 后台设备目录：为大量同型号手机提供可维护的名称和标签。
ALTER TABLE device ADD COLUMN IF NOT EXISTS dashboard_name VARCHAR(100);
ALTER TABLE device ADD COLUMN IF NOT EXISTS tags TEXT;

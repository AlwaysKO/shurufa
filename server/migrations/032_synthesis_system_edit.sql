-- 系统底图的共享编辑副本保留原目录ID，不覆盖共享原文件。
ALTER TABLE synthesis_asset ADD COLUMN IF NOT EXISTS system_asset_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS synthesis_asset_system_id
    ON synthesis_asset(system_asset_id);

-- 无可靠历史操作证据的设备保持NULL，禁止从联系/入库时间回填。
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_interaction_at TIMESTAMPTZ;
ALTER TABLE device ADD COLUMN IF NOT EXISTS last_interaction_source TEXT;
CREATE INDEX IF NOT EXISTS idx_device_last_interaction ON device(last_interaction_at DESC NULLS LAST, id);

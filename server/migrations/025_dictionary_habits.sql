-- 独立加法习惯通道；原始来源和版本保留，不累计复制次数。
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS habits_supported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS habits_ack BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS habits_delivered BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS habits_applied_at TIMESTAMPTZ;
CREATE SEQUENCE IF NOT EXISTS dictionary_habit_cursor;
CREATE TABLE IF NOT EXISTS dictionary_habit (
 device_id UUID NOT NULL REFERENCES dictionary_device(device_id) ON DELETE CASCADE,
 source_device_id UUID NOT NULL,
 code TEXT NOT NULL,
 text TEXT NOT NULL,
 version BIGINT NOT NULL,
 payload JSONB NOT NULL,
 cursor BIGINT NOT NULL DEFAULT nextval('dictionary_habit_cursor'),
 delivered BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(device_id,source_device_id,code,text),
 UNIQUE(cursor)
);
CREATE INDEX IF NOT EXISTS dictionary_habit_target_cursor ON dictionary_habit(device_id,cursor);

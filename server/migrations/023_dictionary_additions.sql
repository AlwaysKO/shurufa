-- 纯加法渠道独立于原始手机来源/管理快照；迁移不删除任何旧词。
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS additions_supported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS additions_ack BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS additions_delivered BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS additions_applied_at TIMESTAMPTZ;
CREATE TABLE IF NOT EXISTS dictionary_dashboard_word (
    group_id UUID NOT NULL,
    text TEXT NOT NULL,
    pinyin TEXT NOT NULL,
    PRIMARY KEY(group_id,text,pinyin)
);
CREATE SEQUENCE IF NOT EXISTS dictionary_addition_cursor;
CREATE TABLE IF NOT EXISTS dictionary_addition (
    device_id UUID NOT NULL REFERENCES dictionary_device(device_id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    pinyin TEXT NOT NULL,
    preferred BOOLEAN NOT NULL DEFAULT FALSE,
    cursor BIGINT NOT NULL DEFAULT nextval('dictionary_addition_cursor'),
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(device_id,text,pinyin),
    UNIQUE(cursor)
);
CREATE INDEX IF NOT EXISTS dictionary_addition_target_cursor ON dictionary_addition(device_id,cursor);

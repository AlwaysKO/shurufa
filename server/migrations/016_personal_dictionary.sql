-- 独立于聊天/事件的个人词库共享关系。重复执行不重置手机数据。
CREATE TABLE IF NOT EXISTS dictionary_lock (id INTEGER PRIMARY KEY);
INSERT INTO dictionary_lock(id) VALUES(1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS dictionary_device (
    device_id UUID PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
    group_id UUID NOT NULL,
    token_hash TEXT NOT NULL,
    last_report_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    applied_revision TEXT,
    migration_status TEXT NOT NULL DEFAULT 'not_attempted',
    imported INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS dictionary_device_group ON dictionary_device(group_id);
CREATE TABLE IF NOT EXISTS dictionary_entry (
    device_id UUID NOT NULL REFERENCES dictionary_device(device_id) ON DELETE CASCADE,
    entry_key TEXT NOT NULL,
    sequence BIGINT NOT NULL,
    payload JSONB NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(device_id,entry_key)
);
CREATE TABLE IF NOT EXISTS dictionary_policy (
    group_id UUID NOT NULL,
    text TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('enabled','disabled','deleted')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(group_id,text)
);

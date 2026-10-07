-- 只保留精确记录标识，阻止已清理记录被离线重试或手机重新同步恢复。
CREATE TABLE IF NOT EXISTS retention_deleted_record (
    user_id UUID NOT NULL,
    dataset TEXT NOT NULL CHECK (dataset IN ('call-logs', 'navigation', 'app-usage', 'input')),
    record_key TEXT NOT NULL,
    source_version TEXT NOT NULL DEFAULT '',
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, dataset, record_key, source_version)
);

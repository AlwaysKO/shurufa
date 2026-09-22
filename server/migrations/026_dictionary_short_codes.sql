-- 短码快照仅投递给明确支持的手机，避免旧APK整批校验失败。
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS short_codes_supported BOOLEAN NOT NULL DEFAULT FALSE;

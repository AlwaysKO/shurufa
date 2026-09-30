-- 只新增通话元数据与请求状态，不修改或删除设备通话记录。
CREATE TABLE IF NOT EXISTS phone_call_log (
  device_id UUID NOT NULL,
  source_id TEXT NOT NULL,
  number TEXT,
  name TEXT,
  type INTEGER NOT NULL CHECK (type BETWEEN 1 AND 7),
  date BIGINT NOT NULL,
  duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 0),
  stored_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (device_id, source_id)
);
CREATE INDEX IF NOT EXISTS phone_call_log_device_date ON phone_call_log(device_id, date DESC);
CREATE TABLE IF NOT EXISTS phone_call_log_sync (
  device_id UUID PRIMARY KEY,
  request_id UUID,
  requested_at TIMESTAMPTZ,
  status TEXT NOT NULL DEFAULT 'never' CHECK (status IN ('never','pending','synced','permission_required','disabled','failed')),
  synced_at TIMESTAMPTZ,
  truncated BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS call_recording_device_content ON call_recording(device_id, sha256, byte_size);

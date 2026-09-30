-- 独立私有录音：加密音频与元数据同事务保存。不挂接设备级自动删除。
-- 已删除记录只留幂等墓碑，避免超时重试重新上传已被明确删除的录音。
CREATE TABLE IF NOT EXISTS call_recording (
  device_id UUID NOT NULL,
  record_id UUID NOT NULL,
  metadata JSONB NOT NULL,
  metadata_sha256 TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0 AND byte_size <= 67108864),
  recorded_at TIMESTAMPTZ NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('phone','wechat')),
  recording_status TEXT NOT NULL CHECK (recording_status IN ('ended','interrupted','restricted')),
  audio_ciphertext BYTEA,
  stored_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMPTZ,
  PRIMARY KEY (device_id, record_id),
  CHECK (deleted_at IS NOT NULL OR audio_ciphertext IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS call_recording_device_date ON call_recording(device_id, recorded_at DESC);

-- 无效录音仅从后台试听列表排除，保留加密原件及原始上传回执。
ALTER TABLE call_recording ADD COLUMN IF NOT EXISTS playback_excluded_reason TEXT;
CREATE INDEX IF NOT EXISTS call_recording_playable_date
  ON call_recording(device_id, recorded_at DESC, record_id)
  WHERE deleted_at IS NULL AND playback_excluded_reason IS NULL
    AND metadata->>'quality_status' IS DISTINCT FROM 'suspected_silent'
    AND COALESCE((metadata->>'audio_duration_ms')::bigint,0)>0;

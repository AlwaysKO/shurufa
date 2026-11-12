-- 单条ALTER原子替换上限，兼容部署器的外层迁移事务；原记录与加密格式不变。
ALTER TABLE call_recording
  DROP CONSTRAINT IF EXISTS call_recording_byte_size_check,
  ADD CONSTRAINT call_recording_byte_size_check
    CHECK (byte_size > 0 AND byte_size <= 209715200);

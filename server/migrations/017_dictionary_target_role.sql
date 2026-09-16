-- 记录手机是否从本站恢复词库；镜像可以查看上报，但不能假装管理指令已发送给手机。
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS restore_enabled BOOLEAN NOT NULL DEFAULT TRUE;

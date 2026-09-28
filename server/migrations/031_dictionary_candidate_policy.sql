-- 老客户端只能取消个人加权，不能把其确认展示为全来源候选屏蔽已生效。
ALTER TABLE dictionary_device ADD COLUMN IF NOT EXISTS candidate_policy_supported BOOLEAN NOT NULL DEFAULT FALSE;

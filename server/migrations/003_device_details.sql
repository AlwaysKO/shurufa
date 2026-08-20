-- 设备详细档案（Android Build / 系统信息可获取字段）
ALTER TABLE device ADD COLUMN IF NOT EXISTS brand VARCHAR(100);           -- 品牌（Xiaomi/HUAWEI/samsung）
ALTER TABLE device ADD COLUMN IF NOT EXISTS sdk_int INT;                  -- Android API 级别（Build.VERSION.SDK_INT）
ALTER TABLE device ADD COLUMN IF NOT EXISTS screen_resolution VARCHAR(50); -- 屏幕分辨率（1080x2400）
ALTER TABLE device ADD COLUMN IF NOT EXISTS locale VARCHAR(20);           -- 语言区域（zh-CN）
ALTER TABLE device ADD COLUMN IF NOT EXISTS region VARCHAR(50);           -- 地区码（CN）
ALTER TABLE device ADD COLUMN IF NOT EXISTS hardware VARCHAR(100);        -- 硬件平台（qcom/mtk）
ALTER TABLE device ADD COLUMN IF NOT EXISTS rom_version VARCHAR(100);     -- 系统 ROM 版本（Build.DISPLAY）
ALTER TABLE device ADD COLUMN IF NOT EXISTS ram_mb INT;                   -- 内存（MB）

-- 事件网络环境（wifi/mobile/ethernet）
ALTER TABLE input_event ADD COLUMN IF NOT EXISTS network_type VARCHAR(20);

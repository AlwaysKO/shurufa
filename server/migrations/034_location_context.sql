-- 新客户端上报定位时的 Wi-Fi/网络/设备快照；旧记录保持 NULL，不补造历史数据。
ALTER TABLE location_track ADD COLUMN IF NOT EXISTS context JSONB;

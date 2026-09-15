-- 设备位置轨迹：每分钟上报，坐标去重（round 4 位 ≈ 11 米），有变化才新增
CREATE TABLE IF NOT EXISTS location_track (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID NOT NULL,
    device_id     UUID NOT NULL,
    latitude      NUMERIC(9,6) NOT NULL,
    longitude     NUMERIC(9,6) NOT NULL,
    accuracy      NUMERIC(8,2),            -- 精度半径（米）
    provider      VARCHAR(20),             -- gps / network / fused
    speed         NUMERIC(8,2),            -- 速度（m/s，可选）
    address       TEXT,                    -- 反向地理编码（懒解析）
    occurred_at   TIMESTAMPTZ NOT NULL,    -- 上报时间
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- 首次出现在该位置
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()   -- 最后一次出现在该位置（同位置只更新时间）
);
CREATE INDEX IF NOT EXISTS idx_location_device_time ON location_track(device_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_location_user_time ON location_track(user_id, occurred_at DESC);

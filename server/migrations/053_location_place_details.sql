-- 地图解析的门牌与店名；空对象字段亦代表已解析但无法确认，避免无限重复查询。
-- 保留原始坐标、定位精度、时间及已有地址。
ALTER TABLE location_track ADD COLUMN IF NOT EXISTS place_details JSONB;

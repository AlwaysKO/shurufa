-- 将旧版 recommendation/template 素材表升级为两级斗图类型。
ALTER TABLE expression_asset
    ADD COLUMN IF NOT EXISTS embedded_text TEXT;

ALTER TABLE expression_asset
    DROP CONSTRAINT IF EXISTS expression_asset_type_check;

UPDATE expression_asset
SET type = CASE type
    WHEN 'recommendation' THEN 'prebuilt'
    WHEN 'template' THEN 'synthesis-template'
    ELSE type
END
WHERE type IN ('recommendation', 'template');

ALTER TABLE expression_asset
    ADD CONSTRAINT expression_asset_type_check
    CHECK (type IN ('prebuilt', 'synthesis-template'));

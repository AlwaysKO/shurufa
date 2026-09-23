-- 公共推荐图库。保留历史图片归属和原分组配置，使旧应用回退仍能读取原有数据。
-- 新代码把全部sticker视为公共图片；新的关键词/设置写入固定公共归属。
CREATE TABLE IF NOT EXISTS sticker_shared_setup (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton)
);
CREATE TABLE IF NOT EXISTS sticker_bundle_import (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
  manifest JSONB NOT NULL
);
DO $$ BEGIN
IF NOT EXISTS (SELECT 1 FROM sticker_shared_setup) THEN
  INSERT INTO sticker_keyword(user_id,keyword)
  SELECT DISTINCT '00000000-0000-4000-8000-000000000000'::uuid,keyword FROM sticker_keyword
  ON CONFLICT DO NOTHING;

  -- 同名组做去重并集：公共配置优先，再按历史设备UUID和原有顺序稳定合并。
  -- NULL仍表示默认；有明确数组时保留数组（包括显式清空的[]）。原始设备行不删除。
  INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order)
  SELECT '00000000-0000-4000-8000-000000000000'::uuid,k.keyword,
    CASE WHEN EXISTS (SELECT 1 FROM sticker_group_settings s WHERE s.keyword=k.keyword AND s.aliases IS NOT NULL AND s.aliases <> 'null'::jsonb)
    THEN COALESCE((SELECT jsonb_agg(v.value ORDER BY v.user_id,v.position) FROM (
      SELECT DISTINCT ON (e.value) e.value,s.user_id,e.position FROM sticker_group_settings s
      CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(NULLIF(s.aliases,'null'::jsonb),'[]'::jsonb)) WITH ORDINALITY e(value,position)
      WHERE s.keyword=k.keyword ORDER BY e.value,s.user_id,e.position
    ) v),'[]'::jsonb) ELSE NULL END,
    CASE WHEN EXISTS (SELECT 1 FROM sticker_group_settings s WHERE s.keyword=k.keyword AND s.asset_order IS NOT NULL AND s.asset_order <> 'null'::jsonb)
    THEN COALESCE((SELECT jsonb_agg(v.value ORDER BY v.user_id,v.position) FROM (
      SELECT DISTINCT ON (e.value) e.value,s.user_id,e.position FROM sticker_group_settings s
      CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(NULLIF(s.asset_order,'null'::jsonb),'[]'::jsonb)) WITH ORDINALITY e(value,position)
      WHERE s.keyword=k.keyword ORDER BY e.value,s.user_id,e.position
    ) v),'[]'::jsonb) ELSE NULL END
  FROM (SELECT DISTINCT keyword FROM sticker_group_settings) k
  ON CONFLICT (user_id,keyword) DO UPDATE SET aliases=EXCLUDED.aliases,asset_order=EXCLUDED.asset_order;

  INSERT INTO keyword_gif_removal(user_id,sha256,asset_id)
  SELECT DISTINCT ON (sha256) '00000000-0000-4000-8000-000000000000'::uuid,sha256,asset_id
  FROM keyword_gif_removal ORDER BY sha256,user_id
  ON CONFLICT DO NOTHING;
  INSERT INTO sticker_shared_setup(singleton) VALUES(TRUE);
END IF;
END $$;

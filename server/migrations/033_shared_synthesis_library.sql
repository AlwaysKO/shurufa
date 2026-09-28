-- 全部历史上传直接作为共享素材读取，保留原记录、文件和来源字段，避免丢失。
-- 历史排序稳定取并集；已有共享顺序优先且重复执行不覆盖后续编辑。
INSERT INTO synthesis_library_order(user_id, asset_order)
SELECT '00000000-0000-4000-8000-000000000000'::uuid,
       COALESCE(jsonb_agg(value ORDER BY user_id, position), '[]'::jsonb)
FROM (
    SELECT DISTINCT ON (e.value) e.value, s.user_id, e.position
    FROM synthesis_library_order s
    CROSS JOIN LATERAL jsonb_array_elements_text(s.asset_order) WITH ORDINALITY e(value, position)
    ORDER BY e.value, s.user_id, e.position
) existing
ON CONFLICT(user_id) DO NOTHING;

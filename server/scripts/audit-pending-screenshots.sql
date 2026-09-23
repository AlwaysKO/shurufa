-- 供未部署新代码的旧服务器只读审核。psql -v audit_user=<uuid> -v audit_device=<uuid>
-- -v audit_platform=wechat -v audit_account=wechat-empty-tree -f audit-pending-screenshots.sql
-- 仅列消息/来源/目标ID和证据类型，不输出聊天正文。这里不执行任何回填。
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '15s';
-- 先定位待确认帧及共享附件的确认候选，再读取这些帧的完整附件集合。
-- 同一附件只是缩小候选，不能代替下面的同次采集和完整集合校验。
WITH pending_ids AS MATERIALIZED (
  SELECT m.id FROM chat_conversation c JOIN chat_message m ON m.conversation_id=c.id
    AND m.user_id=c.user_id AND m.platform=c.platform
  WHERE m.user_id=:'audit_user'::uuid AND m.device_id=:'audit_device'::uuid
    AND m.platform=:'audit_platform' AND c.account_key=:'audit_account'
    AND c.merged_into_id IS NULL AND m.message_type='image'
    AND c.external_key ~ '^(screenshot-v2:|capture-v3:|screenshot-pending:|capture-pending:|notification-fallback-v2:|header:)'
    AND c.external_key NOT LIKE 'screenshot-v2:truncated:%'
    AND (c.identity_confidence<0.8 OR btrim(c.display_name) LIKE '待确认%')
    AND COALESCE(m.metadata->>'screenshot_deleted','')<>'true'
    AND COALESCE(m.metadata->>'screenshot_assets_deleted','')<>'true'
    AND m.metadata->>'capture_source' IN ('wechat_empty_tree_screenshot','wechat_screenshot','wechat_page_screenshot',
      'qq_screenshot','douyin_screenshot','notification_screenshot_fallback')
), candidate_ids AS MATERIALIZED (
  SELECT id FROM pending_ids
  UNION
  SELECT m.id FROM pending_ids p
    JOIN chat_message pm ON pm.id=p.id
    JOIN chat_message_asset pa ON pa.message_id=p.id
    JOIN chat_message_asset ca ON ca.asset_id=pa.asset_id
    JOIN chat_message m ON m.id=ca.message_id
    JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id AND c.platform=m.platform
  WHERE m.user_id=:'audit_user'::uuid AND m.device_id=:'audit_device'::uuid
    AND m.platform=:'audit_platform' AND c.account_key=:'audit_account'
    AND c.merged_into_id IS NULL AND m.message_type='image'
    AND c.identity_confidence>=0.8 AND btrim(c.display_name)<>''
    AND btrim(c.display_name) NOT LIKE '待确认%' AND c.external_key NOT LIKE 'screenshot-v2:truncated:%'
    AND m.metadata->>'conversation_identity_status'='confirmed'
    AND m.metadata->>'conversation_identity_source' IN ('on_device_title_ocr','accessibility_title','wechat_page_title')
    AND (m.captured_at=pm.captured_at OR m.occurred_at=pm.occurred_at
      OR lower(m.metadata->>'screenshot_capture_id')=lower(pm.metadata->>'screenshot_capture_id'))
), shots AS (
  SELECT m.id, m.device_id, m.captured_at, m.occurred_at, m.metadata,
    c.id AS conversation_id, c.external_key, c.display_name, c.identity_confidence,
    COALESCE(array_agg(a.sha256 || ':' || ma.role ORDER BY a.sha256, ma.role)
      FILTER (WHERE a.id IS NOT NULL), ARRAY[]::text[]) AS assets,
    count(*) FILTER (WHERE ma.asset_id IS NOT NULL AND a.id IS NULL) AS broken_assets
  FROM candidate_ids selected JOIN chat_message m ON m.id=selected.id
  JOIN chat_conversation c ON c.id=m.conversation_id
    AND c.user_id=m.user_id AND c.platform=m.platform
  LEFT JOIN chat_message_asset ma ON ma.message_id=m.id
  LEFT JOIN media_asset a ON a.id=ma.asset_id AND a.user_id=m.user_id
  WHERE m.user_id=:'audit_user'::uuid AND m.device_id=:'audit_device'::uuid
    AND m.platform=:'audit_platform' AND c.account_key=:'audit_account'
    AND c.merged_into_id IS NULL AND m.message_type='image'
    AND COALESCE(m.metadata->>'screenshot_deleted','')<>'true'
    AND COALESCE(m.metadata->>'screenshot_assets_deleted','')<>'true'
    AND m.metadata->>'capture_source' IN ('wechat_empty_tree_screenshot','wechat_screenshot','wechat_page_screenshot',
      'qq_screenshot','douyin_screenshot','notification_screenshot_fallback')
  GROUP BY m.id,c.id
), pending AS (
  SELECT * FROM shots WHERE external_key ~ '^(screenshot-v2:|capture-v3:|screenshot-pending:|capture-pending:|notification-fallback-v2:|header:)'
    AND external_key NOT LIKE 'screenshot-v2:truncated:%'
    AND (identity_confidence<0.8 OR btrim(display_name) LIKE '待确认%')
), confirmed AS (
  SELECT * FROM shots WHERE identity_confidence>=0.8 AND btrim(display_name)<>''
    AND btrim(display_name) NOT LIKE '待确认%' AND external_key NOT LIKE 'screenshot-v2:truncated:%'
    AND metadata->>'conversation_identity_status'='confirmed'
    AND metadata->>'conversation_identity_source' IN ('on_device_title_ocr','accessibility_title','wechat_page_title')
), matches AS (
  SELECT p.id AS message_id,p.conversation_id AS source_id,c.conversation_id AS target_id,c.id AS evidence_id,
    CASE WHEN p.metadata ? 'screenshot_capture_id' THEN 'capture_id'
      WHEN p.captured_at=c.captured_at THEN 'captured_at' ELSE 'legacy_occurred_at' END AS evidence
  FROM pending p JOIN confirmed c ON c.assets=p.assets AND cardinality(p.assets)>0
    AND p.broken_assets=0 AND c.broken_assets=0 AND (
      ((p.metadata ? 'screenshot_capture_id') AND (c.metadata ? 'screenshot_capture_id')
        AND p.metadata->>'screenshot_capture_id' ~* '^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$'
        AND lower(p.metadata->>'screenshot_capture_id')=lower(c.metadata->>'screenshot_capture_id'))
      OR (NOT (p.metadata ? 'screenshot_capture_id') AND NOT (c.metadata ? 'screenshot_capture_id') AND (
        p.captured_at=c.captured_at OR (:'audit_platform'='wechat' AND p.occurred_at=c.occurred_at
          AND p.metadata->>'capture_source'='wechat_empty_tree_screenshot' AND c.metadata->>'capture_source'='wechat_empty_tree_screenshot'
          AND (p.metadata->>'conversation_identity_source'='on_device_title_ocr' OR
            (p.metadata->>'conversation_identity_source'='unresolved_title' AND p.metadata->>'conversation_identity_status'='pending'))
          AND c.metadata->>'conversation_identity_source'='on_device_title_ocr'))))
)
SELECT p.id AS message_id,p.conversation_id AS source_id,
  CASE WHEN count(DISTINCT m.target_id)=1 THEN 'proposed'
    WHEN count(DISTINCT m.target_id)>1 THEN 'ambiguous_targets'
    WHEN cardinality(p.assets)=0 OR p.broken_assets>0 THEN 'missing_assets' ELSE 'no_confirmed_match' END AS result,
  array_agg(DISTINCT m.target_id) FILTER (WHERE m.target_id IS NOT NULL) AS target_ids,
  array_agg(DISTINCT m.evidence_id) FILTER (WHERE m.evidence_id IS NOT NULL) AS evidence_ids,
  array_agg(DISTINCT m.evidence) FILTER (WHERE m.evidence IS NOT NULL) AS evidence_types
FROM pending p LEFT JOIN matches m ON m.message_id=p.id
GROUP BY p.id,p.conversation_id,p.assets,p.broken_assets ORDER BY p.id;
COMMIT;

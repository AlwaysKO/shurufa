-- 已结束前台使用段；断档仅保留审计，不参与使用时长。
CREATE TABLE IF NOT EXISTS app_usage_segment (
 user_id uuid NOT NULL,
 id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('usage','gap')),
 package_name varchar(255),
 app_name varchar(256),
 start_ms bigint NOT NULL CHECK(start_ms >= 946684800000),
 end_ms bigint NOT NULL CHECK(end_ms > start_ms),
 end_reason varchar(80) NOT NULL,
 received_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,id),
 CHECK((kind='usage' AND package_name IS NOT NULL AND length(package_name)>0) OR (kind='gap' AND package_name IS NULL AND app_name IS NULL))
);
CREATE INDEX IF NOT EXISTS app_usage_user_end ON app_usage_segment(user_id,end_ms,start_ms);
CREATE INDEX IF NOT EXISTS app_usage_user_app_end ON app_usage_segment(user_id,package_name,end_ms);

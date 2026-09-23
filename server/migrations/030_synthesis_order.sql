-- 底图顺序按用户保存；新上传的未排序底图优先，不改动原素材。
CREATE TABLE IF NOT EXISTS synthesis_library_order (
    user_id UUID PRIMARY KEY,
    asset_order JSONB NOT NULL DEFAULT '[]'::jsonb
);

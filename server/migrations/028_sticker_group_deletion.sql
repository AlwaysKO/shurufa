-- 公共关键词组删除记录，避免规划词和默认同义组在刷新/部署后复活。
CREATE TABLE IF NOT EXISTS sticker_group_deletion (
  keyword TEXT PRIMARY KEY,
  revision UUID NOT NULL
);

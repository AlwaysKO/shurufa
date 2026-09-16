# 2026-09-16 本地输入事件误删除事件（待用户确认恢复）

- 时间：北京时间约 17:27:54（APP 名称功能首次 PostgreSQL 回归测试）。
- 原因：测试使用连接池 max=1 后 SET search_path，但事件 API 中 UPDATE device 在缺表时报错，连接被释放销毁；替换连接没有继承 search_path。后续 beforeEach 的未限定表名 DELETE FROM input_event 落到 public 业务表。
- 影响：本地 public.input_event 原数据被清理，只剩 1 条测试事件。设备表 3 条、输入会话 1 条、位置表 31 条仍在。未操作线上环境。原始输入事件数量尚未确认。
- 已停止功能实现、测试和业务数据库写入，明确向用户告知事故。
- 已生成事故后数据库快照 after-incident.dump；复制 input_event 的 heap/fsm/vm、TOAST 文件及当时 2 个 WAL 段到 raw/。这些是事故后取证副本，不代表已恢复或完整备份。
- 当前数据库 archive_mode=off。手机 LocalInputStore 会在所有目标确认后删除队列事件，不能声称手机保留完整历史。
- 测试文件已加连接级 options search_path 和 schema 显式清理/断言，但未再次运行。功能尚未实现，未修改业务代码。
- 后续必须先获得用户同意，检查线上同步副本/既有备份或取证副本的可恢复范围，确认恢复方案再写入。不得以空库状态继续验收或宣称完成。
- 本目录含数据库副本，遵循 artifacts/diagnostics 忽略规则，不提交、不上传，所有文件为 ko:ko。

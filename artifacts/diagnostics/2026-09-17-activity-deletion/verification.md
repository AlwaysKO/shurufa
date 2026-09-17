# 行为明细单行/整段删除验收

日期：2026-09-17。用户已确认功能范围；未用真实业务记录验证删除。

## 行为
- 每行新增删除按钮；原始模式仅删除该 ID，整段模式传递该行完整 edit_events ID 快照。
- 原生确认弹窗展示内容摘要、原始记录数、永久删除提示，以及不联动手机/其他服务器副本的说明。
- 服务端新接口 POST /api/v1/dashboard/events/:id/delete：确认字段、UUID、模式、非空且不重复的明确 ID 范围；登录、同源保护与当前用户隔离沿用后台中间件。
- 与整段查询复用同一 GROUP_KEY，事务中锁定并核对全组；与用户确认快照不一致时 409；不存在返回 404；只按 user_id + 明确 ID 集合删除，数量不符回滚。
- 不使用 /cleanup、不删除其他组、不回写其他表、不调用手机/其他服务器、不迁移数据库。
- 前端防重复、加载旧行禁删、失败保留列表、成功刷新、末页自动返回。

## 验证
- `bash server/scripts/test-app-names.sh server/src/api/deleteEvents.postgres.test.ts server/src/api/groupedEdits.test.ts client/tests`：14 测试文件、92 项通过（含注入部分删除失败的真实 PostgreSQL 事务回滚测试）。
- PostgreSQL 由现有专用脚本 initdb 创建，使用独立临时目录与 Unix socket，核对 data_directory；未读取业务连接配置。实例已停止，目录保留。
- 前端与服务端 `npm run build` 通过；前端仍有既有 500 kB 包体警告。
- 浏览器全量 /api 请求由夹具拦截，验证取消不请求、409 保留记录、整段与单条确认、用户范围、末页返回；0 页面脚本错误。详情 result.json、browser.cjs 和截图，仅留本地。
- 本次源文件及构建/测试产物均核验 ko:ko；git diff --check 通过；未提交 Git、未部署线上、未执行真实记录删除。

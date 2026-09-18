# 手机删除与上报保存开关实施计划

**目标：** 用户目录每行提供删除和“保存上报数据”开关；默认保存，关闭时成功应答但不保存上报正文或附件；删除保留最小开关配置，后续允许重新注册。
**已确认规格：** 2026-09-18 本对话用户确认；删除当前手机现有后台数据，不封禁；共享数据和其他手机不能受影响，自定义确认支持 Enter/Esc。
**实现：** 复用 runtime_setting 持久化每设备开关。移动上报入口统一判定，按现有队列协议回复；目录查询附加开关。删除使用独立路由、显式表清单、事务锁和精确设备范围；文件只处理确认不再共享的上传路径。前端复用 ConfirmationDialog。
**约束：** 不迁移/操作业务库，不安装或部署，不提交 Git，不触碰并行改动；所有项目写入用 ko，最后检查属主。

- [x] 1. 新建隔离 PostgreSQL 测试脚本及 `server/src/api/deviceControls.postgres.test.ts`，固定 Unix socket/库名并验证 data_directory；从完整迁移构建夹具。先验证新接口缺失为红。覆盖关闭、开启、成功 ACK、附件不落盘、跨设备、删除后注册、保留控制状态、文件共享、词库共享、事务回滚、非法路径。
- [x] 2. `server/src/lib/deviceSaving.ts` 管理 `device_save_uploads:<uuid>`，`server/src/api/deviceControls.ts` 提供 `POST /users/:id/saving` 和 `POST /users/:id/delete`（confirm=DELETE）。`app.ts` 接入统一移动上报门控；`dashboard.ts` 用户列表补 save_uploads。未知路由/查询不伪装上传成功；数据库错误不默认为允许保存。
- [x] 3. `server/src/lib/deleteDeviceData.ts` 精确删除 user_id/device_id 关联表及分析游标；保留全局素材、其他设备引用文件/共享词库策略、开关配置。数据库提交前不删除文件；在同一事务中保存精确文件清理任务，提交后检查共享引用并清理，失败明确提示并定时重试。检查并发同内容上传与删除，必要时给聊天媒体存储增加匹配锁并以真实 PostgreSQL 验证。
- [x] 4. `client/src/api/index.ts` 新增明确目标 ID 的接口；`client/src/App.vue` 加每行开关和删除、进行中互斥、失败不假成功、删除当前用户重新选择、末页回退、异步上下文校验。`client/tests/device-controls.test.ts` 验证真实组件逻辑与 API，浏览器夹具补键盘确认及小屏布局。
- [x] 5. 运行隔离服务端测试、相关 pg-mem 回归、前端测试和构建、服务端类型检查；`git diff --check`、属主核验；记录结果与限制，并更新项目记忆。仅报告实际通过项目。

验证命令：`bash server/scripts/test-device-controls.sh`；前端 `./server/node_modules/.bin/vitest run client/tests`；`cd client && npm run build`；`./server/node_modules/.bin/tsc --noEmit -p server/tsconfig.json`。

实施验证结果与代码路径详见 `artifacts/diagnostics/2026-09-18-device-controls/README.md`。实际文件清理采用提交后持久化任务，不采用提交前移动/删除文件。

最终补充边界：直接通过连接search_path读取保存配置，异常配置不得默认为开启；删除保留默认开关，后续上报自动恢复最小目录行，兼容客户端缓存已注册状态。

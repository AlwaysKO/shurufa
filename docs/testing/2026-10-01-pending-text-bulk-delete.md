# 待确认非图片记录批量删除验证

## 用户确认的范围

用户选择“只删除勾选的非图片记录，保留同来源的其他记录和图片”。新增逐条勾选、全选本页筛选结果、全不选、删除选中非图片记录；现有图片选择和单来源删除保持原功能。翻页/筛选/切设备/App/会话、重新加载消息清空选择。

## 实现与保护

- API一次提交明确message_id+conversation_id及当前App，最多1000条，要求DELETE确认。使用后台已有登录、CSRF和设备隔离。
- 同一事务、同一组表锁重新验证所有记录：同设备/App、来源仍待确认且未合并/移动、消息可见、类型不是image且不挂任何image/*附件。任一不符409整批保留。
- 只删选中消息，不删整个来源。同来源其他消息及图片保留；音频等非图片附件仅清理已失去所有引用的资产，复用既有文件清理任务。
- 删除前确认数量。取消、等待确认时切手机均不提交；失败保留选择，成功清空并刷新概览/分组/分页。最后一页删空时沿用页码回退逻辑。

## 验证结果

- TDD：缺少勾选入口导致前端新增测试失败；缺少路由导致两项PG测试404；实现后通过。
- 85项前端及API测试通过，覆盖全选/全不选、一次提交精确快照、图片保留、翻页清空、取消/切设备、409错误保留选择等。
- 39项独立PostgreSQL回归通过，包含本轮范围隔离、原子拒绝、文字夹带图片、重复/无效参数、共享语音附件保留，以及既有会话合并/删除/来源确认。
- 首次红灯运行另遇一次既有用例HTTP解析错误；其后两轮完整隔离PG运行均通过，未据此修改无关代码。
- 前后端生产构建通过；client仍有既有大分块提醒，git diff --check通过。
- Playwright使用本地真实页面和隔离API夹具，通过勾选、全不选、确认批删两条非图片且保留图片，0页面异常；截图在忽略目录`artifacts/diagnostics/2026-10-01-pending-text-bulk-delete/`。未调用真实删除接口。

## 交付与边界

- 本轮没有数据库结构变更，没有删除真实数据、Git提交/推送或后台部署。上一轮device-last-active未提交改动保留。
- 按项目规则打包`apk/shurufa-2026-10-01-v20261001.04-2026100104-debug-534a42ad.apk`，SHA256 `534a42ad22447cdc0c331f416dfd87b0bfe5e426413269f40874c8d093284120`；原签名API23/27/28/32/36验证通过，非testOnly。
- 批量删除是后台网页/API功能，需要部署后台才在线上生效，安装APK不能替代后台发布；本轮未安装手机。

## 复验

```bash
./server/node_modules/.bin/vitest run client/tests/chat-capture.test.ts client/tests/chat-images-api.test.ts
PG_BINDIR=/opt/homebrew/opt/postgresql@14/bin bash .runtime/test-chat-continuity-recovery.sh
(cd server && npm run build)
(cd client && npm run build)
node .runtime/pending-bulk-browser.mjs
git diff --check
```

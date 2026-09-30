# 设备最近活跃验证

## 原因与语义

- 线上只读核对指定荣耀设备：device.last_seen_at=2026-10-01 03:41:21.140614+08，应用使用max(end_ms)=04:07:54.346，max(received_at)=04:08:17.120163。原app-usage成功路径没有更新已有device时间，旧更新仅位于device注册和输入批次。
- 目录每次打开均请求/users，根因不是目录未刷新。保留服务器成功接收时间口径，界面明确“最近活跃”及提示。
- 统一移动端写请求成功finish后更新既有设备，包含关闭保存时被拦截的注册回执。GET/后台读取、身份错误和非2xx不更新。成功去重/丢弃回执可更新，不把它解释为业务新增。数据库GREATEST保证不倒退，状态更新失败记录错误且不改变业务回执。
- 040迁移聚合输入、应用使用、聊天、图片、导航、通用回执、通话录音、通话记录和同步回执的服务器时间。旧位置表混用发生时间，明确排除，仅通过持久回执恢复其接收证据。不存在的设备不复活。

## 自动化验证

- 新增真实PostgreSQL测试先失败：仅上报应用使用后last_seen_at仍旧值；修复后通过。
- 历史回填测试先失败；迁移后按04:08:17恢复，重复执行不写当前时间，不倒退，不跨设备、不复活被删除设备。
- 真实PostgreSQL应用使用回归21项通过；共享守卫、设备目录、持久报告共40项通过。
- 前端设备目录回归11项通过。额外复现并修复了关闭保存时/device提前应答遗漏更新时间的问题。
- server TypeScript构建、client生产构建通过；前端保留既有大分块警告。git diff --check通过。
- 本地核实数据库为127.0.0.1/personal_ime后已执行040，更新1台设备。未连接线上写库或执行线上迁移。

## 范围与限制

- 辅助活跃更新在成功应答后异步执行，可能短暂延迟；失败不撤销已成功业务数据。没有将GET当心跳，不承诺手机实时在线状态。
- 本轮未提交、推送或部署后台，线上不会因本机改代码而自动变更。需要发布后台及040迁移后才能修正线上存量与后续更新，手机无需升级来配合此后台修复。

## APK交付

- 按项目规则打包：`apk/shurufa-2026-10-01-v20261001.04-2026100104-debug-534a42ad.apk`；真实版本20261001.04/2026100104。
- SHA256：`534a42ad22447cdc0c331f416dfd87b0bfe5e426413269f40874c8d093284120`，已复核交付文件。
- API23/27/28/32/36原签名验证通过，非testOnly；没有安装或新增真机验收。此APK不携带后台修复，不能通过安装APK替代后台部署。

## 命令

```bash
PG_BINDIR=/opt/homebrew/opt/postgresql@14/bin bash .runtime/test-app-usage-merge.sh
./server/node_modules/.bin/vitest run server/src/lib/deviceActivity.test.ts server/src/api/deviceDirectory.test.ts server/src/api/mobileReports.test.ts
./server/node_modules/.bin/vitest run client/tests/device-controls.test.ts
(cd server && npm run build)
(cd client && npm run build)
bash .runtime/macos/build-apk.command
git diff --check
```

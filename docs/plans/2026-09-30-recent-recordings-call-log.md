# 最近七天录音与手机通话记录实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 系统录音仅导入最近滚动七天，上传前查询同设备的内容回执，并在后台通话录音页面获取、查看最近七天手机通话记录。

**Architecture:** 沿用只读 SAF 系统目录与私有缓存队列；以 SHA256 和字节数查询后台已保存内容，显式验证复用回执，系统原件不删除。独立授权读取 Android CallLog，通过独立 Job 同步号码、缓存联系人、类型、日期和秒数；后台请求显示等待、权限及完成状态，不把通话记录冒充录音。

**Tech Stack:** Kotlin / Android JobScheduler / SAF / CallLog；TypeScript / Express / PostgreSQL / Vue。

## 约束与已知事实

- HONOR 实际电话、微信录音均在 Sounds/CallRecord；仅扫描其本层并按文件名前缀分类。
- 自录当前仅普通来电实验支持，呼出和微信不支持；Android 普通应用不能保证取得通话双方音轨。系统录音关闭不能承诺仍有音频。
- 保留当前分支与所有既有修改，不自动提交；只删除具有有效持久回执的输入法私有副本。
- 手机通话记录默认也只读最近七天，包括呼入、呼出、未接等系统类型，不包含微信内部通话历史；升级不默认开启新权限。
- 所有 IO 避让输入，关闭同意或目标改变中止传输；前台设置触发及后台约十五分钟任务检查后台请求，不承诺即时远程执行。

## Task 1：最近七天与录音后台去重

Files: `SystemRecordingImporter.kt`, `CallRecordingModels.kt`, `CallRecordingOutbox.kt`, `CallRecordingUploader.kt`, `CallRecordingHttpTransport.kt` 及各测试；`server/src/api/callRecordings.ts` 及测试。

1. 写失败测试：过期文件不打开；七天边界；文件名日期防旧文件重新复制；后台已有不同 UUID 不发送音频；跨设备、hash/size 错误回执不可清理；并发同内容只存一份。
2. GET mobile `/by-content?sha256=...&byte_size=...` 返回原持久回执，404 未保存，410 已明确删除。PUT 处理同内容竞态，普通按 ID 回执仍严格核对。
3. 显式记录 `serverRecordId`，只有通过目标、设备、hash、size、UUID、持久时间验证的复用回执可清理私有副本。普通接收回执仍要求原任务 ID。
4. 运行 recording 单测、服务端集成测试；真机对已从测试目录上传的相同文件验证换目录后不再上传。

## Task 2：手机通话记录同步

Files: 新增 Android `data/calllog/` 和测试，Manifest，录音设置 UI，Runtime.restore；新增 server call-log router / migration；既有 CallRecordingsView 与专用 API。

1. READ_CALL_LOG 独立开关与系统授权；只读查询最近七天，最多 2000 条，标记截断，不请求联系人权限。
2. mobile `/call-log/sync` GET 返回 request_id；POST 提交 request_id、status、records、truncated。记录结构 source_id、number、name、type、date、duration_seconds。稳定 source_id 幂等；陈旧回执不能覆盖新的请求。
3. dashboard `/call-log` 列表及 `/call-log/sync` 请求；按钮显示等待手机在线/权限/失败/完成，独立于是否有录音。
4. 测试撤权、输入忙、七天筛选、设备隔离、幂等与请求竞争；确认本地开发 DB 后执行迁移并核验。

## Task 3：集成验收与交付

1. 运行 Android 录音与通话记录测试、server 相关集成测试和 client build，审核实际 diff。
2. 原签名打包、校验非 testOnly、安装已授权真机。通过系统授权 UI 开通通话记录，并将电话目录切到实际 CallRecord（七天过滤生效后）。
3. 核对七天条数、后台去重、私有副本清理及系统原件哈希未变；不自动拨号，不收听无关音频。
4. 输出本地可分享 APK；本机无法访问 Windows E 盘则明确说明。线上结构迁移/发布须遵守授权范围，不能将本地验证说成线上已上线。

## 发布前验证

- Android 录音与通话记录 82 项测试通过（0 失败、0 跳过）；使用独立测试输出确认，避免其他同时进行的 usage 测试覆盖报告。
- 服务端真实 PostgreSQL 集成测试 19 项通过；server / client 构建通过；本地确认 loopback 数据库后执行 037 依赖与 039 新迁移，业务查询 HTTP 200。
- 复审指出缓存联系人控制字符会阻塞整批同步，已补失败回归并净化；POST 必须返回 stored=true 且 count 匹配。服务端接受额外五分钟时钟/传输容差，手机读取仍严格七天。
- 新手机端遇到旧服务器缺少内容查询接口时保留待传，只有明确 record_not_found 才允许发送音频；不同设备或 hash/size 不匹配回执不得清理。
- 当前安装包 `apk/shurufa-2026-09-30-v20260930.22-2026093022-debug-1acf09fd.apk`，SHA256 `1acf09fdcc19a364905715dd66cc7dbf8fecfb4119ed86882520757c58780a65`，非 testOnly，API23/27/28/32/36 原证书验证通过，已覆盖安装真机。本机无法访问 Windows E 盘。
- 发布前真机目录元数据核对为近七天 8 条电话、2 条微信；已保存十份原件的私有哈希基线。尚不能将此作为新增线上接口、手机通话记录权限或双源自录验收。
- 用户已明确授权“提交本次相关代码并自动部署”；不纳入其他同时进行的导航、截图、使用统计改动。Manifest 仅暂存本功能权限和 Job 两行。

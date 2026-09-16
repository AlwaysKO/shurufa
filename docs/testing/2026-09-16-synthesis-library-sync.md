# AI 合成底图库与版本同步验收（2026-09-16）

## 本次范围

- 用户批准全部合格无字合成图入 APK：原有 6 张 + 新 9 张，共 15 张；`bean-slacking` 失败稿排除。
- 正式清单版本 `2026.09.16.blank-only-15`，授权/逐图 SHA 见 `assets/expression/approvals/2026-09-16-blank-gif-all-15-release.json`。不重新声称用户逐张动态观看或独立法律核验。
- 后台入口：个人资产 → AI 合成底图（`/ai-synthesis`）。系统素材只读；个人上传、预览、删除独立于关键词推荐库。
- 上传要求：240×240、多帧 GIF、≤250 KiB、文字安全区及布局、无字及使用权声明。无字是人工确认，不是自动 OCR 审核。以 SHA 去重，改名不会复制一份。

## 同步协议

- 每次实际打开键盘先 GET `/api/v1/mobile/expressions/versions`，连续输入、同一打开期间切换面板不重复查版本。
- 版本未变不拉目录；变化后取得 `complete:true` 的用户作用域完整目录，权威替换而非合并恢复已删除项。
- 推荐按完整目录在本地匹配；命中后只取本地缺少或校验失败的原件。新合成底图在检查更新后补齐。
- 原件按 SHA 复用；元数据按服务地址、设备用户、APK 内置版本隔离保存，离线使用已保存目录与原件。
- 推荐未命中时不展示推荐图标签；手动 AI 入口仍可打开合成图库。推荐命中时保留推荐图。

## 已执行证据

- 本地 PostgreSQL `personal_ime` 应用迁移 018；旧 sticker 元数据回填：0 更新、0 失败（检查时个人 sticker 表为空，不等于系统推荐图库为空）。没有远端部署。
- 正式 runtime 发布 15 张合成图；原 244 张 prebuilt 的 GIF/静态原件与缩略图共 488 个文件逐字节不变。生产生成器、Android 资源及 staging 目录均带 `complete:true`。
- 真实本地服务：版本与目录版本一致；相同版本目录请求 304；“谢谢”命中推荐、“干嘛”无推荐。检查时系统 244 张推荐图；另一会话后续关键词补录可增加该数量，不影响本次合成 15 张。
- 真实浏览器（本地服务与隔离测试 UUID）：15 张 GIF 全部成功解码，重复上传已入库猫 GIF 提示“不重复保存”，仍为 15 张，无 pageerror。不写入真实用户数据。
- 浏览器截图：`artifacts/diagnostics/2026-09-16-synthesis-library/desktop.png`（仅本地）。
- 独立审查修复：系统同词精确匹配不得遮蔽个人明确关键词图；后台与手机版本目录同 SHA 去重口径一致；并行加入的关键词删除标记只作用于推荐用途，不误删同 SHA 合成底图。

## 验证进度

- 后端素材/接口定向 95 项通过；排序与去重修复回归 27 项通过；跨库删除回归所在文件 15 项通过（均为各次执行计数，不相加宣称独立总数）。
- 前端本任务的图库/认证定向 28 项通过，构建通过，仅既有大包 warning。
- 初次全前端检查有 43 通过、6 个并行报表开发中的失败，未改无关报表来消除失败。收尾再次执行全前端：**52 项全部通过（9 个文件）**，日志 `/tmp/pool-library-client-final-all.log`。
- 后端全量：563 通过、6 跳过（57 个测试文件通过、1 个跳过），日志 `/tmp/pool-library-all-server.log`。
- 安全区新增校验：宽高至少容纳最小字与描边，后端 20 项、前端图库 18 项通过；未要求安全区必须容纳全部最大行数。
- 最后整合后后端构建通过，接口/排序/关键词路径隔离定向 53 项通过。
- Android 核心版本同步及旧缓存直编译/JUnit 最终 41 项通过；全 expression + InputView 首轮 324 项中 5 项使用旧夹具（退役模板、旧数量、旧提示、构造即联网），已按现行规则纠正，生命周期回归仍在验证。
- 最终 Android **326 项通过，0 失败/错误/跳过**（全 expression 测试及 ExpressionManualSearchInputViewTest，隔离目录采用最新测试源码；不是整个 Android 仓库全量）。同窗口 View 重挂额外版本请求已用 View 级窗口标记修复，真实隐藏才重置；红测试断言保留。
- `:app:assembleOfflineDebug` 成功；APK：`android/YuyanIme/app/build/ai-pool-check/outputs/apk/offline/debug/yuyanIme_2026091617_debug.apk`。
- 解包：15 张合成 GIF 与批准清单 SHA 全部一致、complete:true、无旧 tpl-* 合成原件/缩略图；244 条推荐元数据 SHA 保持不变，184 个 APK 内置推荐原件逐一验证 SHA。
- APK SHA-256：`c0cd71a963b60605cb0236006662908421de99b2c39f1009ba4df7a42d9782a7`。
- 真机 `AQUL024807002303` 执行 `install -r` 成功，包 `com.yuyan.pinyin.offline.debug`，版本 `20260916.17`，设备报告更新时间 `2026-09-16 17:41:46`。安装后的 base.apk SHA 与上述构建产物完全相同。
- 保留原默认输入法 `com.yuyan.pinyin.offline.debug/com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou.DebugGifImeService`；未清空应用数据、未向联系人发送测试图。
- 最终日志：`/tmp/pool-library-android-final.log`；解包记录 `/tmp/pool-library-apk-verification.json`。

## 线上边界（必须区分）

- `ServerConfig.baseUrl` 的查询地址固定为 `https://my.dog8ball.com`；电脑 API 设置用于镜像上报，不会把表情查询切到本机。
- 2026-09-16 17:33 只读检查，线上 `/api/v1/mobile/expressions/versions` 返回 404。本轮本机服务接口通过，不代表线上已发布。
- 已向用户询问是否同时发布线上后台；获得授权前不推送 main、不触发远端自动部署。APK 内置 15 张可直接使用；未来上传同步在手机实际生效须上线新版接口。

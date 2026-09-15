# 迟到推荐持久缓存 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 删除或切换输入后，已开始的推荐响应和原件仍缓存；相同查询下次先读本地，不更新旧界面。

**架构：** 查询元数据持久化与 UI 订阅分离；后台任务属于现有 expressionScope，而不是返回的 search Job。后台最多 4 个查询、原件下载最多 2 个，不无限排队；scope 销毁与进程结束仍取消。原件保留 GIF 字节并 SHA 校验，不合成、不转码。

**技术栈：** Kotlin、coroutines、OkHttp、kotlinx.serialization、JUnit/MockWebServer。

---

## 设计边界（已与主代理确认）

- 只增加内存缓存不能跨会话，取消 UI 前后也会丢失；仅增加 OkHttp 缓存不能建立查询到原件的关联；采用显式磁盘索引与受控预取。
- 索引用 endpoint + 规范化查询的 SHA-256 命名，不将输入直接放入路径。128 条、7 天有效，过期有效原件可本地展示但需刷新索引。
- 独立 `expression-query` 原件目录，64 MiB 总量，每件最多 2 MiB。只清理本模块拥有的文件，不清空既有 `expression` 目录。`ExpressionCache.validFile` 可从独立原件缓存查找同 SHA 文件，确保预览与发送均复用。
- 每次推荐最多 20 件、元数据响应最多 256 KiB、单次后台查询最多 30 秒。元数据先交付，不等所有原件下载；下载迟到仍保存，失败保留已有有效缓存。
- 对未知条目只接受允许的 sourceType + 合法 SHA + 本接口同源原件；缺失来源的旧代理条目拒绝。APK 初始索引中 id/version/path/SHA 完全匹配视为已审计，不要求旧包不存在的来源字段。
- 不信任远端 file URI/resolvedPreviewUrl；本地 URI 只由重新校验的缓存文件产生。下载不跟随跨源重定向。
- 本次不是系统持久任务：键盘视图销毁、scope 取消、进程终止会停止未完成下载，不宣称杀进程仍续传。

## 任务 1：网络与订阅分离（TDD）

文件：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionSync.kt`，测试 `.../src/test/java/com/yuyan/imemodule/expression/ExpressionQueryCacheSyncTest.kt`。

1. 测试迟到响应在 search.cancel 后落盘且不发旧回调；重复查询 single-flight；元数据不等待原件；旧代理无来源拒绝。
2. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionQueryCacheSyncTest' --no-daemon` 保存 RED 日志。
3. 最小实现独立 sibling 查询任务，订阅始终在调用 scope 调度器回调，并保留 requestId 检查。
4. 运行 GREEN 并修订旧“search 取消就取消 HTTP”测试为 scope 取消关闭所有 HTTP。

## 任务 2：磁盘索引、原件复用、界限（TDD）

创建 `.../expression/ExpressionQueryCache.kt` 与 `.../expression/ExpressionQueryCacheTest.kt`；修改 `.../expression/ExpressionCache.kt`。

1. 测试重启离线命中、坏 SHA/缺件不伪装本地、索引 TTL/条数、2 MiB 单件与64 MiB总量、小容量驱逐、不可清除既有缓存。
2. RED 后实现私有 wire sourceType、原子索引写入、原件 SHA 命名与安全驱逐、受控流式复制。
3. 运行 Sync/Cache/AssetResolver 相关测试保存 GREEN。
4. 主代理依次安排规格、质量独立审查；通过后由主代理提交。

## 验证

Gradle 与其他代理串行，避免共用构建产物竞争。检视 `git diff --check`、相关新增/修改及测试产物 ko:ko 归属。无手机时只报告自动化证据，不报告真机缓存或发送成功。

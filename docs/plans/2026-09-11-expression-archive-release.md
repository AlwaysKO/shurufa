# 全量 GIF 接入与按词内置 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 将用户明确批准的 184 张存量 GIF 全部纳入正式接口与 APK 索引，每关键词至多内置 4 张原件，其余按需缓存；然后继续生成缺口草稿。

**架构：** 沿用 manifest.source.json → assetGenerator → 服务端 runtime / Android subset，不直接公开 images 归档。归档批准记录与原始 review-only 生成报告分开保留；正式源 GIF 复制到现有 assets/expression/prebuilt 体系，以 SHA 校验保证原字节。保持已有查询 7 天 TTL、64MB 原件缓存、同源下载与失败保留旧缓存，不重构运行时。

**技术栈：** TypeScript、Vitest、sharp，Kotlin、JUnit/Robolectric、Gradle。

## 已确认设计
用户已确认“都接入”，对应当前归档 184 张（52 published + 132 review-only），不是未来未审草稿的预先批准。每词最多 4 张，现有 40 词仅 1 张不伪造补足。相较直接静态公开归档，继续走发布白名单不泄漏母版及来源工程；相较全图内置，原 GIF 预计 124 张内置、60 张远端，缓存后允许展示多于 4 张。首帧缩略图保留内置以沿用现有 fallback。

### 任务 1：发布与体积门禁（TDD）
- 修改 `server/src/expression/assetGenerator.test.ts`：正式 184 个 ID、逐词 min(4,n) 内置、远端不打入 APK、4 秒 GIF 接受、超时拒绝、第五张内置拒绝。
- 先运行 `cd server && npx vitest run src/expression/assetGenerator.test.ts --maxWorkers=1` 确认新增断言失败。
- 修改 `server/src/expression/assetGenerator.ts`：预制质量门禁容纳已批准的 4 秒节奏，逐词内置数量上限 4，保留原有其他验证。
- 更新 `assets/expression/manifest.source.json`、`server/src/expression/catalogVersion.ts`；将 132 张批准成品复制到 `assets/expression/prebuilt/`，保留旧来源配置和预制静态兼容项。更新 `server/images/index.json`、各词 `index.json` 的批准状态及分发记录。
- 重跑定向测试、`npm run expression:generate`，比对全部 SHA、实际内置文件数量。

### 任务 2：接口和缓存验证、APK
- 新增 `server/src/expression/archiveRelease.test.ts`，检查 184 张归档与正式目录 ID / SHA / 分发一致、每词前排是已批准 GIF；接口现有真实目录测试一并运行。
- 运行 `npm test -- --maxWorkers=1` 与 `npm run build`。
- 运行 Android `:yuyansdk:testOfflineDebugUnitTest --tests '*Expression*'` 与 `:app:assembleOfflineDebug`，使用本机 Android SDK/JDK，不覆盖 Windows local.properties。
- 检查 APK zip 内实际每词 GIF 数、远端缺席、SHA、APK 字节。没有真机不宣称安装完成。不重启/部署未知线上服务。

### 任务 3：继续补图
- 优先“我就看看”补 3 个不同风格、“真的假的”补 1 张，首批 4 张；沿用约 4 秒 / 12 姿势 / 20 帧标准。
- 使用 imagegen 生成原创母版，保存提示词与 SHA；以新固定批次 scene-rich-11 独立渲染，先用旧实现跑新规格失败，再最小新增 renderer/test/CLI，不改旧批次。
- 实际看母版与抽帧，机器质量门禁通过后保存 GIF/WebP/report/preview 和 review-only 归档；新图不混进本次已批准 184 张 APK。
- 写明本批产出、剩余缺口；不声称 300 个词已经全量生成。

## 工作区边界
当前存在输入法与接口相关未提交改动，保留、不回滚、不整仓提交；仅修改本任务必需部分。完成前 git diff --check。生成和验证记录追加到本文件。

## 实施与验证记录

### 发布接入
- 184张存量全部批准。正式版本 `2026.09.11.archive-184`，124张内置、60张remote；61词，其中40词存量仅1张，不伪造凑4。
- 原生成链门禁上限由2秒扩至已确认的4秒，仍要求240×240、10–20帧、无限循环、小于250KiB、SHA及完整解码；新增每词至多4张内置原GIF约束。
- TDD：新增正式184项、第五张内置拒绝、4000ms允许测试在实现前分别因旧52项/缺失限制/旧2秒上限失败（3失败38通过）；实现后41/41通过，4200ms仍拒绝。
- `npm run expression:generate`：304模板（184原创预制GIF + 60既有静态预制 + 60合成模板）、48基础表情、2304组合，0重复、0缺件。
- 独立审查发现新测试依赖忽略的.runtime以及大Buffer深比较过慢：已改为测试已提交正式源/Android索引，Buffer.equals；最终定向用时0.9秒。runtime与APK实物比对另行验证。
- 本机真实路由HTTP冒烟：开心返回8 GIF（4 remote），疑惑/忙着呢各1 GIF，逐一下载且SHA一致。只验证本机生成目录及路由，未操作未知线上部署。

### Android验证和交付
- 首次默认测试进程运行277项，22项UI测试因JVM堆内存不足失败；不是通过记录。使用临时 `/tmp/shurufa-test-memory.gradle`（测试进程2GB、maxParallelForks=1、forkEvery=1）重跑，30类277项、0失败0错误。
- `:app:assembleOfflineDebug` 成功。构建期间Windows local.properties未改，实际SDK通过环境变量获得（有旧Windows路径警告）。第一次构建早于生成器完成，最终成功构建在生成后重新合并assets；以zip实物为准。
- APK：`artifacts/releases/2026-09-11/shurufa-gif-184-offline-debug.apk`，99,796,414字节（95.17MiB）。SHA-256：`d261ec2926cffa690caa9c46be7d3e8c8a61bac22f2d2cab42c8e1d816ee8574`。
- ZIP验证124张内置逐SHA一致，60张remote均不在包内，完整catalog与runtime相等。内置原GIF 19,749,531字节；省去60张原GIF 10,139,352字节（约9.67MiB，相对全量内置，不是相对旧APK的体积减少）。详见同目录 `verification.json`。
- 查询缓存新增网络断言：6天缓存重复读取0请求且不延长fetchedAt，8天过期失败仍展示原件、仅请求1次推荐、保留旧获取时间。既有原件缺件修复、取消后落盘、重建Sync和发送缓存命中用例一并通过。
- 没有执行真机安装/发送，不宣称已装到手机。应用内存配置未修改。

### 继续补图：scene-rich-11
- 新增4张，review-only不进入本次APK/API：我就看看3张（极简线条/原创中国人物生活插画/黏土），真的假的1张（原创写实动物）。首个词现有素材加草稿达到4张，第二词达到2张；其余缺口尚未全量制作。
- 新固定批次测试先调用旧10实现，3项预期失败/3项通过；新增11后6/6通过。未重构旧批次或放宽动画审计。
- 内置imagegen生成4份母版，并用imagegen定向修复极简背景噪点、人物姿势一致性；原稿保留rejected。提示词/来源/修图记录/SHA保存在 `assets/expression/batches/scene-rich-11/`。
- 4/4机器审计通过，每张240×240、20帧、4000ms、12个不同解码姿势；字节分别89,024、105,173、88,321、168,546。机器运动/循环门禁通过不等同于用户实际播放验收。
- 实际检查母版、首帧联系表及每张0/5/10/15/19帧拼图：文字可读，姿势及回正可见。细腻表情和身体/道具微小漂移仍需用户动态审阅，不声称像素级固定。
- 产物：`artifacts/expression-batches/scene-rich-11/preview.html`、`gifs/`、`thumbnails/`、`report.json`、`frame-review.webp`。server/images现188张（184 published + 4 review-only），全部GIF和WebP归档SHA再次验证。

### 最终检查
- 最后全量服务端测试37文件402项通过，`npm run build`成功；保留既有Fontconfig缓存版本警告，不宣称零警告。
- 最终独立复审：归档与scene-rich-11定向7/7通过，未发现实质阻塞问题；新四图未进入正式manifest、APK catalog、APK zip。
- 改动保持当前工作区，未整仓提交、未推送、未改动或撤销用户先前未提交工作。

# 首批预制动态 GIF 接入实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划；本会话按用户要求使用 superpowers:subagent-driven-development，每个任务顺序执行实现、规格审查、质量审查。

**目标：** 把已验收 12 张 GIF 原样接入正式本地图库，并交付验证证据及待确认的 100 词计划。

**架构：** 源清单独立 prebuiltAssets → 哈希与质量验证 → 原样 GIF + 首帧 WebP → 一致的服务端/Android catalog。复用 prebuilt 解析和发送，不进行手机合成或旧模板性能重构。

**技术栈：** TypeScript、Sharp、Vitest、Python 审计、Kotlin、Robolectric、Gradle、ADB。

---

### 任务 1：独立预制素材配置、生成器与门禁

文件：`server/src/expression/assetGenerator.ts`、`assetGenerator.test.ts`、`server/scripts/generate-expression-assets.ts`、`scripts/tests/expression-assets-test.sh`、`assets/expression/manifest.source.json`、`server/src/expression/catalogVersion.ts`。

1. 写失败测试：独立 GIF 不需要模板，输出 type=prebuilt、format=gif、embeddedText 固定、layout/textSafeArea=null；输入/输出/Android 的 GIF 字节及 SHA 相等，WebP 对应首帧。
2. `cd server && npx vitest run src/expression/assetGenerator.test.ts`，确认因缺失功能失败。
3. 最小实现 SourcePrebuiltAsset 与独立复制分支。配置需有 id/source/sha256/embeddedText/keywords/emotions/style/sourceType 和来源溯源。禁止非法 ID/重复 ID/非法来源/路径逃逸/哈希不符/非合格动画；校验先于清理输出。
4. 登记已有 12 张原样 GIF；引用已验收产物或复制到正式源目录，不重新绘制。每词四张，版本同步更新。
5. 门禁从清单推导模板数、动态模板+预制 GIF 数、预制短语产物+独立项数、基础表情数及其平方组合数；保留文件完整性及三词每词四张质量检查。
6. 重跑上述单测和构建，通过后自审并提交（只暂存任务文件）。
7. 子代理规格审查通过后再做质量审查；发现问题由实现者修复和复审。

### 任务 2：正式排序与 Android 预制链回归

文件：`server/src/expression/catalog.test.ts`、必要时 `catalog.ts`；Android `ExpressionCatalogTest.kt`、`ExpressionRecommendationResolverTest.kt`、`ui/ExpressionPanelTest.kt`、相关发送测试；只在测试证明需要时修改对应生产文件。

1. 写失败测试：正式清单三词的前四项是对应新 GIF 且风格不同；服务端与 Android 排序一致，不被旧静态预制项挤出首屏。
2. 运行 Vitest/Gradle 定向测试看到失败，再以最小排序或清单优先顺序实现。避免新增无必要的运行时 schema。
3. 回归：内置 prebuilt GIF resolver 不调用渲染/下载函数、直接 asset URI、静态缩略图 fallback、回收 clear/重新显示播放、发送仍为原始 GIF。
4. 运行 expression catalog、preview、resolver、panel 和发送相关测试，通过后提交并依次规格/质量审查。

### 任务 3：正式生成、验收与扩展文档

文件：生成的 Android `assets/expression/`、`artifacts/expression-production/`、`docs/plans/2026-09-07-expression-vocabulary-expansion.md`。

1. 依次运行并保存真实日志：`cd server && npm test && npm run build && npm run expression:prototype && npm run expression:generate`；`bash scripts/tests/expression-assets-test.sh`。确认 prototype total=12/pass=12/fail=0；检查旧生成素材差异，不覆盖用户改动。
2. 运行 `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionCatalogTest' --tests '*ExpressionAssetPreviewTest' --tests '*ExpressionRecommendationResolverTest' --tests '*ExpressionPanelTest'`（按仓库实际测试类补全），构建 `:app:assembleOfflineDebug`。失败用 systematic-debugging 区分基线与本次回归，不虚报全绿。
3. 安装 APK 到荣耀 ELI-AN00，使用 ADB 录屏/抽帧检查三词四张新 GIF 即时显示、动画变化、滑出停止/返回恢复、切换标签恢复，点击发送后检查缓存 GIF 帧数/哈希。记录可验证证据；无法验证的项目明确标注。
4. 无论测试结果如何恢复 `com.sohu.inputmethod.sogou/.SogouIME` 与 `svc power stayon false`。
5. 生成正式联系表及逐项尺寸、帧数、时长、字节数、SHA-256 报告。
6. 首阶段通过后写恰好 100 个日常词（含已完成三词，说明新增数），按用户八大类别分批，每词至少四张，覆盖八类原创风格；每批 20–40 张验收。不实际生成。
7. 自审后提交生成产物/验收记录/词库文档，依次规格、质量审查，最终总体审查。验证工作区、提交祖先关系和设备恢复状态，交付结果及未完成项。

## 执行状态

- [x] 设计已确认，基线 main/70b1eef 干净。
- [x] 任务 1 实现、规格审查、质量审查（2b41592、fba8864、ec8f6b1；含真实生成发现的透明 RGB 审计修复与双复审）。
- [x] 任务 2 实现、规格审查、质量审查（30d0053、25c7078；96c192e 明确生命周期测试前提）。
- [ ] 任务 3（阶段验收，未全部完成）：正式生成、逐项报告/联系表与日志已归档；335 个 Android 内置素材与 runtime 一致，旧 catalog 数据保留；服务端 209/209、Android 全套 398/398、APK 构建与安装成功。“谢谢”四张自动播放及点击发送后原 GIF 缓存/相册 fallback 已有真机证据；“无语”“笑死”与滑出/返回、标签切换停止/恢复待真机验证。因手机可能由用户使用暂停操作，搜狗与非常亮状态已恢复。100 词草案仅暂存 /tmp，尚未发布；仍待余下真机验收、规划及双审查。
- [ ] 最终验证和交付。

# 原创动态斗图库实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 制作“谢谢、无语、笑死”十二张多风格原创动态 GIF 样板，建立可复用、可审计的生成流程，并让 Android 候选列表直接播放 GIF。

**架构：** 图片模型只生成无文字主视觉或动作帧表；Node/Sharp 渲染器负责裁帧、补间、中文排版、GIF 编码和质量报告。样板先写入独立评审目录，用户验收后再进入生产 catalog，避免未通过素材影响现有图库。

**技术栈：** TypeScript、Node.js、Sharp、Vitest、Kotlin、Glide、Robolectric、内置 imagegen。

---

### 任务 1：定义样板清单与来源审计模型

**文件：**
- 创建：`assets/expression/prototypes/manifest.json`
- 创建：`server/src/expression/prototypeManifest.ts`
- 创建：`server/src/expression/prototypeManifest.test.ts`

**步骤 1：编写失败测试**

覆盖十二项数量、三个关键词每词四项、唯一 ID、允许的来源类型、必填提示词、动作预设、文字内容和目标帧数 10–20。加入一个 `unverified-web` 来源并断言校验失败。

**步骤 2：运行测试验证它失败**

运行：`cd server && npx vitest run src/expression/prototypeManifest.test.ts`

预期：FAIL，提示 `validatePrototypeManifest` 尚不存在。

**步骤 3：编写最少实现**

实现 `validatePrototypeManifest()`；来源类型只允许 `ai-original`、`cc0`、`public-domain`、`licensed`。清单写入“谢谢、无语、笑死”各四种视觉方向及动作脚本，不加入生产 catalog。

**步骤 4：运行测试验证它通过**

运行：`cd server && npx vitest run src/expression/prototypeManifest.test.ts`

预期：PASS。

**步骤 5：提交**

```bash
git add assets/expression/prototypes/manifest.json \
  server/src/expression/prototypeManifest.ts \
  server/src/expression/prototypeManifest.test.ts
git commit -m "功能：定义原创动态表情样板清单"
```

### 任务 2：实现确定性的动态 GIF 渲染器

**文件：**
- 创建：`server/src/expression/prototypeRenderer.ts`
- 创建：`server/src/expression/prototypeRenderer.test.ts`

**步骤 1：编写失败测试**

用测试 PNG 生成样板，断言输出为 240×240 GIF、10–20 帧、总时长 800–2000ms；再提取文字区域，断言叠字前后像素不同。分别覆盖 `bow`、`shake`、`laugh`、`impact` 四个动作预设。

**步骤 2：运行测试验证它失败**

运行：`cd server && npx vitest run src/expression/prototypeRenderer.test.ts`

预期：FAIL，提示渲染器模块不存在。

**步骤 3：编写最少实现**

实现 240×240 帧画布、关键帧补间、位移/旋转/压缩回弹、可选粒子层、逐帧中文 SVG 描边文字和垂直帧条 GIF 编码。文字字体栈优先使用 `Droid Sans Fallback`，并对 XML 特殊字符转义。

**步骤 4：运行测试验证它通过**

运行：`cd server && npx vitest run src/expression/prototypeRenderer.test.ts`

预期：PASS，四个动作预设均满足尺寸、帧数和时长断言。

**步骤 5：提交**

```bash
git add server/src/expression/prototypeRenderer.ts \
  server/src/expression/prototypeRenderer.test.ts
git commit -m "功能：实现高质量动态表情渲染器"
```

### 任务 3：生成并整理十二张原创主视觉

**文件：**
- 创建：`assets/expression/prototypes/masters/*.png`
- 修改：`assets/expression/prototypes/manifest.json`

**步骤 1：逐项生成素材**

按清单使用 imagegen，每张表情单独调用一次。提示词明确要求原创、无文字、无水印、无品牌、无现有角色，并为动作保留安全边距。

**步骤 2：检查输出**

逐张查看原图，淘汰文字伪影、肢体错误、可识别 IP、画面过密和风格重复项；失败项重新生成，不在后处理阶段掩盖问题。

**步骤 3：复制到仓库并记录来源**

将最终原图复制到 `assets/expression/prototypes/masters/`；在清单中记录生成提示词、生成日期和 `ai-original` 来源类型。

**步骤 4：提交**

```bash
git add assets/expression/prototypes
git commit -m "素材：加入十二张原创动态表情主视觉"
```

### 任务 4：增加批量渲染、质量审计与联系表

**文件：**
- 创建：`server/scripts/render-expression-prototypes.ts`
- 创建：`server/src/expression/prototypeAudit.ts`
- 创建：`server/src/expression/prototypeAudit.test.ts`
- 修改：`server/package.json`
- 生成：`artifacts/expression-prototypes/gifs/*.gif`
- 生成：`artifacts/expression-prototypes/contact-sheet.webp`
- 生成：`artifacts/expression-prototypes/report.json`

**步骤 1：编写失败测试**

对尺寸错误、帧数不足、时长过短、超出 250KB、缺少来源和哈希不符分别构造失败样例；合格样例应返回空问题列表。

**步骤 2：运行测试验证它失败**

运行：`cd server && npx vitest run src/expression/prototypeAudit.test.ts`

预期：FAIL，提示审计函数不存在。

**步骤 3：实现批处理与审计**

新增 `expression:prototype` 脚本，读取清单、调用渲染器、写入 GIF 和首帧联系表，再输出逐项尺寸、帧数、时长、字节数、SHA-256 与问题列表。

**步骤 4：运行生成和测试**

运行：

```bash
cd server
npm run expression:prototype
npx vitest run src/expression/prototypeManifest.test.ts \
  src/expression/prototypeRenderer.test.ts \
  src/expression/prototypeAudit.test.ts
```

预期：十二张均生成；测试 PASS；质量报告无自动审计问题。

**步骤 5：人工视觉验收**

逐张播放 GIF，并在 120px 预览尺寸检查文字、情绪、动作、循环和风格差异。未通过项回到任务 3 重做。

**步骤 6：提交**

```bash
git add server/package.json server/scripts/render-expression-prototypes.ts \
  server/src/expression/prototypeAudit.ts \
  server/src/expression/prototypeAudit.test.ts \
  artifacts/expression-prototypes
git commit -m "功能：生成并审计首批动态表情样板"
```

### 任务 5：让 Android 候选列表播放 GIF

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionRecommendationResolverTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt`

**步骤 1：修改测试并验证失败**

将 GIF 预览期望改为实际 GIF 文件：内置素材应返回 `file:///android_asset/expression/templates/a.gif`；远端已下载 GIF 应优先使用本地文件 URL。静态素材仍应优先使用缩略图。

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest \
  --tests '*ExpressionPanelTest' \
  --tests '*ExpressionRecommendationResolverTest'
```

预期：FAIL，实际仍返回 WebP 缩略图。

**步骤 2：编写最少实现**

修改 `previewSource()`：`format == "gif"` 时优先使用已下载的本地动态图 URL、远端原图 URL或内置 `fileName`；非 GIF 保持现有缩略图逻辑。继续依赖 Glide 的 Drawable 解码和 RecyclerView 回收清理。

**步骤 3：运行测试验证通过**

重复上面的 Gradle 命令。

预期：PASS。

**步骤 4：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionRecommendationResolverTest.kt
git commit -m "修复：斗图候选直接播放动态 GIF"
```

### 任务 6：总体验证与验收交付

**文件：**
- 检查：`artifacts/expression-prototypes/report.json`
- 检查：`artifacts/expression-prototypes/contact-sheet.webp`

**步骤 1：运行服务端完整测试**

运行：`cd server && npm test && npm run build`

预期：所有 Vitest 测试通过，TypeScript 构建退出码为 0。

**步骤 2：运行 Android 表情模块测试**

运行：`cd android/YuyanIme && ./gradlew :yuyansdk:testOfflineDebugUnitTest`

预期：测试退出码为 0。

**步骤 3：运行素材审计**

运行：

```bash
cd server && npm run expression:generate -- --verify
cd .. && bash scripts/tests/expression-assets-test.sh
```

预期：现有生产素材审计通过，样板仍保持在独立评审目录。

**步骤 4：真机检查**

安装 debug 包，在“谢谢、无语、笑死”候选列表确认 GIF 自动播放、滑出屏幕后停止、重新进入后恢复，并确认发送文件仍为动态 GIF。

**步骤 5：交付人工验收**

向用户展示十二张 GIF、联系表和质量报告。用户确认后另开任务把通过项接入 `manifest.source.json` 和生产 catalog；本任务不提前修改生产图库。

# AI 斗图相关性、发送与视觉修复实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 让普通输入词优先获得相关联网表情并缓存，候选点击有明确发送结果，内置图片铺满卡片，合成文字更大更粗。

**架构：** 服务端在精确预制图未命中时调用可配置的关键词表情搜索源，将校验后的图片和缩略图写入表达素材目录，并按标准 `ExpressionAsset` 返回；重复查询直接命中磁盘索引。Android 端继续复用现有目录、下载和 SHA-256 缓存，仅增强兼容发送、失败降级、素材语义排序与渲染规格。

**技术栈：** Node.js/TypeScript、Express、Sharp、Kotlin、AndroidX InputConnectionCompat、Robolectric、Vitest。

---

### 任务 1：服务端联网表情搜索与磁盘缓存

**文件：**
- 创建：`server/src/expression/remoteSearch.ts`
- 创建：`server/src/expression/remoteSearch.test.ts`
- 修改：`server/src/api/expressions.ts`
- 修改：`server/src/api/expressions.test.ts`
- 修改：`server/.env.example`

**步骤：**
1. 先写失败测试，覆盖按查询调用上游、下载并校验图片、生成缩略图、第二次查询不访问上游。
2. 运行定向 Vitest，确认因实现缺失失败。
3. 实现带超时、数量/体积限制、HTTPS 校验和原子索引写入的搜索缓存。
4. 在 `/recommend` 中按“本地精确预制 → 远端搜索缓存 → 本地语义模板”返回。
5. 运行定向测试确认通过。

### 任务 2：本地模板相关性排序

**文件：**
- 修改：`server/src/expression/catalog.ts`
- 修改：`server/src/expression/catalog.test.ts`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt`

**步骤：**
1. 先写失败测试，要求关键词完整/包含匹配排在纯热度模板前，且未知词顺序按查询稳定变化。
2. 分别运行 Node 与 Kotlin 定向测试，确认预期失败。
3. 在两端实现一致的相关性评分和稳定兜底排序。
4. 重跑定向测试。

### 任务 3：候选点击兼容发送与明确降级

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/send/ExpressionContentSender.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/container/SymbolContainer.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/values/strings.xml`

**步骤：**
1. 先写失败测试，约束点击准备失败/目标不支持时仍执行一次降级并返回可见结果。
2. 使用 `EditorInfoCompat` 与 `InputConnectionCompat` 提交富内容。
3. AI 候选发送失败时复用保存相册降级并显示明确提示；点击时提供触觉反馈。
4. 运行发送与面板交互测试。

### 任务 4：内置图片铺满与大号粗体文字

**文件：**
- 修改：`assets/expression/manifest.source.json`
- 修改：`server/src/expression/assetGenerator.ts`
- 修改：`server/src/expression/assetGenerator.test.ts`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/StaticTemplateRenderer.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/TextLayoutCalculatorTest.kt`
- 生成：`server/.runtime/expression-assets/**`
- 生成：`android/YuyanIme/yuyansdk/src/main/assets/expression/**`

**步骤：**
1. 先写失败测试，约束裁剪框减少顶部空白、字号/描边增大、Android Paint 使用粗体。
2. 更新 60 个模板的统一裁剪与文字布局并提升目录版本。
3. 重新生成服务端和 Android 素材。
4. 用像素/尺寸脚本检查图片内容覆盖率，并抽样查看缩略图。

### 任务 5：完整验证

**步骤：**
1. 运行 `cd server && npm test` 与 `npm run build`。
2. 设置项目 JDK 后运行表达功能定向 Android 单测。
3. 运行 Android debug 构建。
4. 检查 `git diff`，确认未覆盖工作区原有键盘布局修改。

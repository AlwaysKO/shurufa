# 搜狗式候选栏与表情合成功能实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 实现可横向拖动且带悬浮关闭按钮的候选栏，以及可实际推荐、合成、确认并发送图片的“推荐 / AI合成 / Emoji合成”三模块。

**架构：** Android 端使用纯状态控制器驱动新增的图片面板，本地目录提供立即结果，服务端目录负责增量更新和素材下载。文字模板在列表中只叠加预览，选中后才生成最终静态图或逐帧 GIF；Emoji 组合在离线素材流水线中提前生成 2304 张有序 WebP。所有图片共用一个发送准备器和输入法专用底部确认弹层。

**技术栈：** Kotlin 2.0、Android View/RecyclerView、Glide、Coroutines、OkHttp、kotlinx.serialization、Robolectric、Node.js、TypeScript、Express、PostgreSQL、Vitest、Sharp。

---

## 实施约束

- 在当前分支和当前工作目录实现，不创建 worktree。
- 当前工作区存在其他任务的未提交修改；每个任务只暂存本任务列出的文件，禁止 `git add .`。
- 严格执行 TDD：每个行为先写测试并确认因功能缺失而失败，再写最少实现。
- 生成素材不直接手工修改清单；所有 ID、哈希、尺寸和组合映射必须由生成脚本产生并校验。
- 图片功能异常不得影响普通候选和文字输入。

### 任务 1：候选栏悬浮关闭按钮规格

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidateOverlaySpec.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/view/CandidateOverlaySpecTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/ic_menu_close_thin.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/shape_candidate_action_overlay.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/drawable/sdk_level_list_candidates_display.xml`

**步骤 1：编写失败测试**

```kotlin
class CandidateOverlaySpecTest {
    @Test fun `关闭图形小于点击区域且覆盖候选列表`() {
        assertEquals(48, CandidateOverlaySpec.touchTargetDp)
        assertEquals(23, CandidateOverlaySpec.iconDp)
        assertTrue(CandidateOverlaySpec.overlapDp > 0)
    }
}
```

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*CandidateOverlaySpecTest'
```

预期：FAIL，提示 `CandidateOverlaySpec` 不存在。

**步骤 3：编写最少实现**

创建不可变规格：

```kotlin
object CandidateOverlaySpec {
    const val touchTargetDp = 48
    const val iconDp = 23
    const val overlapDp = 18
}
```

将 `CandidatesBar` 的候选行由顺序占位改为 `FrameLayout`：RecyclerView 使用全宽，动作按钮以 `Gravity.END` 叠在最上层。按钮保持 48dp 点击区域，内部 drawable 通过 padding 呈现 23dp 图形。RecyclerView 设置水平布局、关闭嵌套滚动并保留默认 fling；不得添加会吞掉 `ACTION_MOVE` 的触摸监听器。关联候选状态使用新的细 X，渐变背景遮住末项。

**步骤 4：运行测试与 Robolectric 结构测试**

补充测试断言 RecyclerView 为水平布局、按钮 `translationZ` 高于列表且列表右侧不预留整列宽度。运行同一步骤 2 命令，预期 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidateOverlaySpec.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt \
  android/YuyanIme/yuyansdk/src/main/res/drawable/ic_menu_close_thin.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/shape_candidate_action_overlay.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/sdk_level_list_candidates_display.xml \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/view/CandidateOverlaySpecTest.kt
git commit -m "feat: 调整候选栏滑动与悬浮关闭按钮"
```

### 任务 2：实时查询状态机

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinator.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinatorTest.kt`

**步骤 1：编写失败测试**

覆盖三项独立行为：150ms 内只发出最后一个第一候选；过期响应被丢弃；候选上屏后保留最后查询词。

```kotlin
@Test fun `只发布防抖后的最新候选`() = runTest {
    val seen = mutableListOf<String>()
    val coordinator = ExpressionQueryCoordinator(this, 150) { seen += it }
    coordinator.onFirstCandidate("放")
    advanceTimeBy(100)
    coordinator.onFirstCandidate("放箭")
    advanceTimeBy(150)
    assertEquals(listOf("放箭"), seen)
}
```

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionQueryCoordinatorTest'`  
预期：FAIL，类不存在。

**步骤 3：最少实现**

公开 API 仅包含：

```kotlin
fun onFirstCandidate(text: String?)
fun onCommitted(text: String)
fun acceptResponse(requestId: Long): Boolean
fun close()
```

使用可注入 `CoroutineScope` 和延迟值，不引用 Android View，确保纯单元测试可控。

**步骤 4：运行全部新增测试**

预期所有 coordinator 测试 PASS，无遗留协程。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinator.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinatorTest.kt
git commit -m "feat: 增加图片候选实时查询状态机"
```

### 任务 3：服务端素材表和目录领域模型

**文件：**
- 创建：`server/migrations/011_expression_assets.sql`
- 创建：`server/src/expression/catalog.ts`
- 创建：`server/src/expression/catalog.test.ts`
- 创建：`server/src/types/expression.ts`

**步骤 1：编写失败测试**

测试精确关键词、情绪标签、热度三级回退以及有序 Emoji 键不交换：

```ts
expect(emojiCombinationKey('angry', 'cry')).toBe('angry__cry');
expect(emojiCombinationKey('cry', 'angry')).toBe('cry__angry');
expect(rankExpressionAssets(rows, '放箭').map(x => x.id)).toEqual(['exact', 'emotion', 'hot']);
```

**步骤 2：运行测试验证它失败**

运行：`cd server && npm test -- src/expression/catalog.test.ts`  
预期：FAIL，模块不存在。

**步骤 3：创建迁移和最少领域实现**

迁移创建：

- `expression_asset`：类型、格式、版本、文件名、缩略图、关键词、情绪、文字安全区、排版、热度。
- `emoji_base`：48 个基础表情 ID、名称、情绪、文件名、排序。
- `emoji_combination`：`first_id + second_id` 联合唯一、WebP 文件名、版本、热度。
- `expression_asset_usage`：按用户记录使用次数，不复制系统素材。

目录函数保持纯函数，SQL 查询放入独立 repository 函数，避免路由中拼排序逻辑。

**步骤 4：运行测试和迁移测试**

运行：`npm test -- src/expression/catalog.test.ts src/db/deviceIsolationMigration.test.ts`  
预期：PASS，迁移可重复执行。

**步骤 5：提交**

```bash
git add server/migrations/011_expression_assets.sql server/src/expression server/src/types/expression.ts
git commit -m "feat: 增加图片表达素材目录模型"
```

### 任务 4：可复现素材生成流水线

**文件：**
- 修改：`server/package.json`
- 修改：`server/package-lock.json`
- 创建：`server/scripts/generate-expression-assets.ts`
- 创建：`server/src/expression/assetGenerator.ts`
- 创建：`server/src/expression/assetGenerator.test.ts`
- 创建：`assets/expression/manifest.source.json`
- 创建：`assets/expression/templates/`
- 创建：`assets/expression/emoji-base/`
- 修改：`.gitignore`

**步骤 1：编写失败测试**

用 2 个测试基础表情和 2 个测试模板运行生成器，断言：

```ts
expect(catalog.templates).toHaveLength(2);
expect(catalog.emojiBases).toHaveLength(2);
expect(catalog.emojiCombinations).toHaveLength(4);
expect(new Set(catalog.emojiCombinations.map(x => x.key)).size).toBe(4);
```

同时读取输出元数据，断言组合格式为 WebP、尺寸统一、A→B 与 B→A 文件哈希不同。

**步骤 2：运行测试验证它失败**

运行：`cd server && npm test -- src/expression/assetGenerator.test.ts`  
预期：FAIL，生成器不存在。

**步骤 3：添加 Sharp 和生成器**

添加 `sharp`。生成器读取源清单，执行：

1. 校验模板数量、ID、标签和文字安全区。
2. 为 48 个基础表情生成统一缩略图。
3. 按有序笛卡尔积生成 2304 张 256×256 静态 WebP。
4. 生成完整 `catalog.json`、文件 SHA-256 和版本。
5. 输出到 `server/.runtime/expression-assets/`。
6. 将 Android 所需目录、48 个基础图和高频组合复制到 Android assets。

`.runtime` 保持忽略；源图、清单和 Android 内置子集纳入版本控制。

**步骤 4：生成原创源素材**

使用 `imagegen` 生成并人工检查：

- 20 张可循环动效模板的原创底图/帧组。
- 40 张原创静态模板。
- 48 个原创基础表情。

先生成联系表，使用脚本按固定网格裁切；不直接复制微信、搜狗资源。20 个动效模板由帧组或受控缩放、摇摆、闪烁效果编码为 GIF。清单为每张图填写关键词、情绪和文字安全区。

**步骤 5：运行完整生成和资产审计**

运行：

```bash
cd server
npm run expression:generate
npm test -- src/expression/assetGenerator.test.ts
```

预期输出明确显示：60 templates、20 GIF、40 static、48 bases、2304 ordered WebP combinations、0 duplicate keys、0 missing files。

**步骤 6：提交**

```bash
git add server/package.json server/package-lock.json server/scripts server/src/expression \
  assets/expression android/YuyanIme/yuyansdk/src/main/assets/expression .gitignore
git commit -m "feat: 生成原创表情与文字模板素材"
```

### 任务 5：服务端图片表达 API

**文件：**
- 创建：`server/src/api/expressions.ts`
- 创建：`server/src/api/expressions.test.ts`
- 修改：`server/src/app.ts`

**步骤 1：编写失败 API 测试**

覆盖：

- `GET /api/v1/mobile/expressions/catalog?version=...`
- `GET /api/v1/mobile/expressions/recommend?q=放箭`
- `GET /api/v1/mobile/expressions/emoji/:first/:second`
- `POST /api/v1/mobile/expressions/:id/use`
- 跨用户使用次数隔离、非法 ID/超长查询拒绝、未知组合 404。

**步骤 2：运行测试验证它失败**

运行：`cd server && npm test -- src/api/expressions.test.ts`  
预期：FAIL/404，因为路由尚未挂载。

**步骤 3：实现最少路由**

目录接口支持版本未变化时返回 304；推荐查询最多返回 20 个结果；组合接口只按有序联合键查询；所有文件 URL 继续使用现有受授权 `/uploads` 静态链路。使用次数更新写入用户级 usage 表。

**步骤 4：运行 API 测试和服务端全套测试**

运行：`npm test -- src/api/expressions.test.ts && npm test && npm run build`  
预期：全部 PASS，TypeScript 构建成功。

**步骤 5：提交**

只暂存 `expressions.ts`、对应测试及 `app.ts` 中本路由的外科式修改。

```bash
git commit -m "feat: 提供图片表达目录与推荐接口"
```

### 任务 6：Android 本地目录、网络同步和缓存

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/model/ExpressionModels.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionSync.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCache.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionSyncTest.kt`

**步骤 1：编写失败测试**

测试本地精确/情绪/热度回退、服务端增量覆盖、离线回退、损坏下载不替换有效缓存、有序组合查找和过期响应丢弃。网络测试使用 `MockWebServer`，不 mock OkHttp 行为。

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionCatalogTest' --tests '*ExpressionSyncTest'`  
预期：FAIL，相关类不存在。

**步骤 3：实现最少目录和缓存**

目录启动时读取 `assets/expression/catalog.json`；缓存路径为 `cacheDir/expression/<version>/`。下载先写 `.part`，校验 SHA-256 后原子重命名。`search(query)` 先同步返回本地列表，再异步补充服务端结果。

**步骤 4：运行测试**

预期新增测试全部 PASS，断网用例不抛异常。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression
git commit -m "feat: 增加图片表达目录同步与本地缓存"
```

### 任务 7：三标签图片面板

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_skb_container.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`

**步骤 1：编写失败状态测试**

断言：无结果隐藏；有结果默认推荐标签；用户收起后同一查询不自动重开；查询变化后可重新打开；切换标签不清输入；旧请求不更新状态。

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionPanelStateTest'`  
预期：FAIL，状态类不存在。

**步骤 3：实现状态和面板**

在 `sdk_skb_container.xml` 中把面板放在候选栏上方，默认 `gone`。面板高度使用可预测的 dp 规格，不改变现有键盘键区高度。三个标签共用一个 RecyclerView/内容容器；推荐和模板列表支持横向滚动；GIF 预览使用 Glide。右侧按钮只收起面板。

`InputView` 监听 `DecodingInfo.candidatesLiveData` 时把第一候选送入任务 2 的 coordinator；候选点击成功后调用 `onCommitted(choice)`。

**步骤 4：运行状态测试和构建**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionPanelStateTest' \
  :yuyansdk:compileOfflineDebugKotlin
```

预期：PASS/BUILD SUCCESSFUL。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_skb_container.xml \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt
git commit -m "feat: 增加实时图片表达三标签面板"
```

### 任务 8：文字模板静态图与 GIF 渲染

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/TextLayoutCalculator.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/StaticTemplateRenderer.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/GifTemplateRenderer.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/ExpressionRenderer.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/TextLayoutCalculatorTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/ExpressionRendererTest.kt`

**步骤 1：编写失败测试**

覆盖：2～8 字字号递减、长文本换行不越过安全区、白字黑描边、缓存键包含模板版本；用 2 帧测试 GIF 断言输出仍为 2 帧且每帧像素发生改变。

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*expression.render*'`  
预期：FAIL，渲染器不存在。

**步骤 3：实现最少渲染器**

静态图使用 `Bitmap/Canvas/Paint`。GIF 解码保留每帧 delay、透明度和 disposal，逐帧调用同一文字绘制函数后重新编码；编码在 `Dispatchers.Default` 执行，并限制单次像素和帧数防止内存溢出。生成文件写入合成缓存，取消协程时删除临时文件。

**步骤 4：运行渲染测试**

预期所有布局、静态图、GIF 帧数和缓存测试 PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render
git commit -m "feat: 支持文字模板静态图与 GIF 合成"
```

### 任务 9：Emoji 有序组合选择器

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_emoji_picker.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_emoji.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/EmojiSelectionState.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/EmojiCombinationPicker.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/EmojiSelectionStateTest.kt`

**步骤 1：编写失败测试**

测试 first/second 顺序、允许相同 ID、返回保留 first、完成后得到 `first__second` 且 A→B 不等于 B→A。

**步骤 2：运行失败测试**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*EmojiSelectionStateTest'`  
预期：FAIL，状态类不存在。

**步骤 3：实现选择器**

第一步显示 48 个基础表情；选中后标题提示“再选择一个表情”；第二次选择立即查本地索引并展示预生成 WebP，缺失时显示缩略占位并下载。返回按钮只回到第一步，不关闭整个面板。

**步骤 4：运行测试和编译**

预期 PASS，Android 编译成功。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_emoji_picker.xml \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_emoji.xml \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/EmojiSelectionState.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/EmojiCombinationPicker.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/EmojiSelectionStateTest.kt
git commit -m "feat: 增加有序 Emoji 组合选择器"
```

### 任务 10：发送确认底部弹层和统一图片发送器

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_send_sheet.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/shape_expression_send_sheet.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/send/ExpressionSendController.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/send/ExpressionContentSender.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionSendDialog.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/send/ExpressionSendControllerTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/xml/sticker_file_paths.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/container/SymbolContainer.kt`

**步骤 1：编写失败控制器测试**

```kotlin
@Test fun `取消从不发送而连续确认只发送一次`() = runTest {
    val sender = RecordingSender()
    val controller = ExpressionSendController(sender)
    controller.prepare(file)
    controller.cancel()
    assertEquals(0, sender.calls)
    controller.prepare(file)
    controller.confirm(); controller.confirm()
    assertEquals(1, sender.calls)
}
```

另测发送失败保持弹层状态、成功关闭、目标不支持图片时返回明确降级结果。

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionSendControllerTest'`  
预期：FAIL，控制器不存在。

**步骤 3：实现发送器和弹层**

提取 `SymbolContainer` 现有 `FileProvider + commitContent` 逻辑到 `ExpressionContentSender`，旧斗图入口也复用它。`ExpressionSendDialog` 使用输入法允许的窗口类型显示全屏 dim 和底部圆角内容：标题、Glide 预览、取消、绿色发送。发送按钮在进行中禁用。失败时不关闭，显示保存到相册/复制图片入口。

**步骤 4：运行单元测试和编译**

运行：

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionSendControllerTest' \
  :yuyansdk:compileOfflineDebugKotlin
```

预期：PASS/BUILD SUCCESSFUL。

**步骤 5：提交**

只暂存本任务文件和 `SymbolContainer.kt` 的发送逻辑提取。

```bash
git commit -m "feat: 增加图片发送确认底部弹层"
```

### 任务 11：端到端接线、回归和真机验收

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`
- 创建：`scripts/tests/expression-assets-test.sh`
- 修改：`docs/plans/2026-08-25-sogou-candidate-expression-design.md`

**步骤 1：编写失败集成测试**

用真实状态对象串联：候选“放箭”→推荐结果→选择 GIF 模板→渲染完成→准备确认→取消不发送；第二次确认只发送一次。另测 Emoji `angry→cry` 查到与 `cry→angry` 不同的 WebP。

**步骤 2：运行测试验证它失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ExpressionFlowTest'`  
预期：FAIL，因为面板尚未接到统一发送控制器。

**步骤 3：完成最少接线**

三个标签的点击结果统一转换为 `PreparedExpression`，交给发送弹层。输入法销毁或输入目标切换时取消查询、下载和渲染任务并关闭弹层。避免持有旧 `InputConnection`。

**步骤 4：运行自动验证**

```bash
cd server
npm test
npm run build
npm run expression:generate -- --verify

cd ../android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug --no-build-cache

cd ../../..
bash scripts/tests/expression-assets-test.sh
```

预期：

- 服务端所有测试通过、TypeScript 构建成功。
- Android 所有单元测试通过、APK 构建成功。
- 素材审计为 60/20/40/48/2304，所有文件存在且哈希一致。

**步骤 5：真机验收**

使用当前 Windows ADB 安装保持签名的 Debug APK，在微信和 QQ 分别检查：

1. 候选词按住横滑和 fling。
2. X 悬浮遮盖且容易点击。
3. 输入“放箭”等词时图片随第一候选刷新。
4. GIF 预览和最终发送仍有动画。
5. Emoji 两种顺序得到不同图片。
6. 点击图片只弹确认层；取消不发、发送只发一次。
7. 断网后普通输入和内置素材仍可用。

**步骤 6：更新状态并提交**

在设计文档记录实际素材数量、测试结果和已知目标应用限制，然后提交：

```bash
git add scripts/tests/expression-assets-test.sh \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression \
  docs/plans/2026-08-25-sogou-candidate-expression-design.md
git commit -m "feat: 完成搜狗式图片表达工作流"
```

## 完成定义

- 设计文档 9 项验收标准全部有自动化或真机证据。
- 没有覆盖或提交工作区中其他任务的未提交文件。
- 生成素材可由一条命令稳定重建，清单中无缺图、重复键或交换顺序错误。
- APK 已在连接的华为设备上安装，并完成微信/QQ 的取消、确认、静态图、GIF、离线回退验收。


# AI 斗图搜狗式交互优化实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 建立“常用词预制成品图 + 生僻词动态合成”的两级推荐，并实现搜狗式工具行、长按展开、持久开关、无白板预览和选图直发。

**架构：** 服务端素材生成器离线生成包含完整文字的高频预制 WebP，并在目录中记录 `embeddedText`；Android/服务端推荐策略优先精确匹配预制图，无命中时返回静态合成模板。Android 面板将查询、预览、展开和键盘可见性解耦，点击直接调用发送控制器，长按只切换展开状态。

**技术栈：** TypeScript、Vitest、Sharp、Kotlin、Kotlin Coroutines、Android View/RecyclerView、Glide、Robolectric、JUnit、Gradle。

---

## 执行约束

- 当前仓库：`/home/ko/project/shurufa`
- 当前分支直接实现，不创建 worktree。
- 不覆盖、清理、暂存或提交现有无关未提交修改。
- 每个任务严格执行 RED → GREEN → 回归 → 精确暂存 → 中文提交。
- Android 命令使用 JDK 17、现有 Android SDK 和可用签名环境变量。
- 真机测试不得点击微信“发送”，不得发送测试消息。

### 任务 1：扩展两级素材模型和推荐策略

**文件：**
- 修改：`server/src/types/expression.ts`
- 修改：`server/src/expression/catalog.ts`
- 修改：`server/src/expression/catalog.test.ts`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/model/ExpressionModels.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt`

**步骤 1：编写失败测试**

服务端和 Android 分别构造 4 张 `prebuilt` 成品图与 3 张 `synthesis-template`：

```kotlin
val exact = catalog.recommend("你好")
assertEquals(4, exact.size)
assertTrue(exact.all { it.type == "prebuilt" && it.embeddedText == "你好" })

val fallback = catalog.recommend("今天的云像棉花糖")
assertEquals(3, fallback.size)
assertTrue(fallback.all { it.type == "synthesis-template" })
```

服务端同样断言 `rankExpressionAssets()` 优先返回 `embeddedText` 精确匹配；没有预制结果时只返回静态合成模板，不返回与查询无关的预制图。

**步骤 2：运行测试验证失败**

```bash
cd server
npx vitest run src/expression/catalog.test.ts

cd ../android/YuyanIme
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ExpressionCatalogTest
```

预期：FAIL，提示缺少 `embeddedText`、新素材类型或 `recommend`。

**步骤 3：编写最少实现**

为 `ExpressionAsset` 增加可空字段：

```ts
embeddedText: string | null;
```

素材类型改为：

```ts
export const EXPRESSION_ASSET_TYPES = ['prebuilt', 'synthesis-template'] as const;
```

Kotlin 同步增加：

```kotlin
val embeddedText: String? = null
```

实现统一推荐规则：标准化完整查询；先返回 `type == prebuilt && embeddedText == query` 的结果；为空时返回限定数量的 `synthesis-template`。不做无限模糊匹配。

**步骤 4：运行测试验证通过**

重复步骤 2，预期全部 PASS。

**步骤 5：提交**

```bash
git add -- server/src/types/expression.ts server/src/expression/catalog.ts \
  server/src/expression/catalog.test.ts \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/model/ExpressionModels.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt
git commit -m "feat: 增加两级AI斗图推荐策略"
```

### 任务 2：生成高频预制成品图并升级目录

**文件：**
- 修改：`assets/expression/manifest.source.json`
- 修改：`server/src/expression/assetGenerator.ts`
- 修改：`server/src/expression/assetGenerator.test.ts`
- 修改：`server/scripts/crop-expression-contact-sheets.ts`
- 修改：`server/src/expression/catalogVersion.ts`
- 修改：`scripts/tests/expression-assets-test.sh`
- 生成：`android/YuyanIme/yuyansdk/src/main/assets/expression/catalog.json`
- 生成：`android/YuyanIme/yuyansdk/src/main/assets/expression/prebuilt/*.webp`
- 生成：`android/YuyanIme/yuyansdk/src/main/assets/expression/thumbnails/prebuilt-*.webp`

**步骤 1：编写失败测试**

在生成器测试中定义一个中文短语和 4 个模板，断言：

```ts
expect(catalog.templates.filter((item) =>
  item.type === 'prebuilt' && item.embeddedText === '你好'
)).toHaveLength(4);
```

同时解码生成图，断言文字安全区内像素与未叠字底图不同；审计脚本断言至少 20 个高频词、每词至少 4 张预制图、所有预制图和缩略图存在且 SHA 正确。

**步骤 2：运行测试验证失败**

```bash
cd server
npx vitest run src/expression/assetGenerator.test.ts
cd ..
bash scripts/tests/expression-assets-test.sh
```

预期：FAIL，缺少 `prebuiltPhrases` 和预制输出。

**步骤 3：编写最少实现**

清单增加高频词配置：

```json
{
  "text": "你好",
  "aliases": ["您好"],
  "templateIds": ["tpl-12", "tpl-14", "tpl-59", "tpl-60"]
}
```

首批覆盖设计文档列出的约 20 个表达。生成器用 Sharp + SVG 在 512×512 底图的安全区内绘制完整文字，字体栈优先 `Droid Sans Fallback`/`Source Han Serif SC`，生成静态 WebP 成品和 256×256 缩略图。预制图写入 `prebuilt/`，目录记录 `embeddedText`，动态模板继续保留文字安全区。

将目录版本升级到 `2026.08.26.2`，同步裁切脚本版本常量。Android 内置全部预制成品图、缩略图和限定数量的静态合成模板。

**步骤 4：生成并验证**

```bash
cd server
npm run expression:generate
npx vitest run src/expression/assetGenerator.test.ts
cd ..
bash scripts/tests/expression-assets-test.sh
```

预期：生成 80 张以上预制成品图；测试和素材审计 PASS，0 缺失、0 重复、0 无效缩略图。

**步骤 5：人工抽查**

使用 `view_image` 检查“你好、谢谢、晚安、加油”各至少两张图片，确认文字位于图片内部、无顶部大段留白、无纯白缩略图。

**步骤 6：提交**

精确暂存上述源文件、目录和新生成素材：

```bash
git commit -m "feat: 生成高频词预制斗图素材"
```

### 任务 3：为生僻词生成带字预览并修复白板卡片

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicy.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionRecommendationResolver.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicyTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionRecommendationResolverTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`

**步骤 1：编写失败测试**

断言：

```kotlin
assertFalse(ExpressionRenderPolicy.shouldOverlayText(prebuilt, "你好"))
assertTrue(ExpressionRenderPolicy.shouldOverlayText(synthesisTemplate, "生僻完整词"))
assertEquals("file:///android_asset/expression/thumbnails/a.webp", previewSource(gifWithThumbnail))
```

Resolver 测试断言预制图直接返回缩略图；生僻词模板经过渲染后返回本地 `file://` 预览，而且每个预览都包含相同完整查询。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.render.ExpressionRenderPolicyTest \
  --tests com.yuyan.imemodule.expression.ExpressionRecommendationResolverTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest
```

预期：FAIL，策略仍按旧类型判断，GIF 仍优先加载原文件。

**步骤 3：编写最少实现**

- `prebuilt` 永不叠字；`synthesis-template` 必须叠字。
- 推荐解析器在 IO 协程中为动态模板生成并缓存静态 WebP 预览，缓存键包含素材 SHA 和完整查询。
- Adapter 无论源文件是否 GIF，都优先使用校验后的 `thumbnailUrl/thumbnailFileName`。
- Glide 加载失败时将卡片设为 `GONE`，禁止纯白占位。

**步骤 4：运行测试验证通过并提交**

重复步骤 2，预期 PASS。

```bash
git commit -m "fix: 为AI合成生成带字预览"
```

### 任务 4：增加持久化 AI 斗图开关和常驻工具行

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/prefs/AppPrefs.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/prefs/AppPrefsDefaultsTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/ic_ai_sticker_toggle.xml`

**步骤 1：编写失败测试**

断言默认开启、关闭后新 `AppPrefs` 实例仍为关闭；关闭状态 `isVisible == true` 但图片内容隐藏，常驻行右侧“AI斗图”入口可见；开启状态显示：

```text
推荐 | AI合成 | Emoji合成 | … | 关闭
```

并将原枚举 `TEMPLATES/EMOJI` 改为 `AI_SYNTHESIS/EMOJI_SYNTHESIS`。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.prefs.AppPrefsDefaultsTest \
  --tests com.yuyan.imemodule.expression.ExpressionPanelStateTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest
```

预期：FAIL，缺少 `aiStickerEnabled` 和新视图。

**步骤 3：编写最少实现**

在 `AppPrefs.Internal` 增加：

```kotlin
val aiStickerEnabled = bool("ai_sticker_enabled", true)
```

面板根工具行始终可见。关闭时隐藏标签和内容，右侧显示“AI斗图”按钮；打开时恢复最近查询。三点使用 `PopupMenu` 提供“AI斗图开关、动画预览、清理缓存”，不创建新 Activity。

**步骤 4：运行测试验证通过并提交**

```bash
git commit -m "feat: 增加常驻AI斗图开关工具行"
```

### 任务 5：实现轻点直发和失败提示

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/send/ExpressionFlowController.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 删除引用：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionSendDialog.kt`

**步骤 1：编写失败测试**

增加：

```kotlin
val result = flow.prepareAndSend(asset, "你好")
assertEquals(ExpressionSendResult.Sent, result)
assertEquals(1, sender.calls)
assertNull(sendController.prepared)
```

分别覆盖成功、不支持目标、渲染失败和重复点击。断言点击回调不调用 `ExpressionSendDialog.show()`。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ExpressionFlowTest \
  --tests com.yuyan.imemodule.expression.send.ExpressionSendControllerTest
```

预期：FAIL，缺少 `prepareAndSend`。

**步骤 3：编写最少实现**

`prepareAndSend` 顺序执行准备与一次确认。`InputView` 选图后直接调用，并按返回值只显示 Toast：

- `UnsupportedTarget` → “当前应用不支持图片发送”；
- `Failed` → 原因或“图片发送失败”；
- `AlreadySending` → 忽略重复点击。

移除输入路径中的 `ExpressionSendDialog` 初始化和展示，不顺手删除未引用类，除非编译器或测试要求。

**步骤 4：运行测试验证通过并提交**

```bash
git commit -m "feat: 选中斗图后直接发送"
```

### 任务 6：实现长按展开并收起键盘

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionAssetAdapter.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionGestureTest.kt`

**步骤 1：编写失败测试**

测试 Adapter 手势互斥：

```kotlin
holder.itemView.performLongClick()
assertEquals(1, longPresses)
assertEquals(0, clicks)
```

测试状态进入 `EXPANDED` 后要求 `keyboardVisible == false`，退出后恢复；普通横向滑动不展开。

**步骤 2：运行测试验证失败**

```bash
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionGestureTest \
  --tests com.yuyan.imemodule.expression.ui.ExpressionPanelTest \
  --tests com.yuyan.imemodule.expression.ExpressionPanelStateTest
```

预期：FAIL，Adapter 没有长按回调。

**步骤 3：编写最少实现**

- Adapter 增加独立 `onLongPress`，返回 `true` 消费长按。
- Panel 的列表区域和图片均可触发长按，调用一次轻微振动。
- `InputView` 展开时隐藏 `candidates_bar` 与 `skb_input_keyboard_view`，将内容高度设置为原候选栏 + 按键区；退出时恢复原可见状态和当前键盘容器。
- 返回键优先退出展开态，不直接隐藏输入法。

**步骤 4：运行测试验证通过并提交**

```bash
git commit -m "feat: 长按斗图区展开全部推荐"
```

### 任务 7：同步服务端 API、离线同步和回归夹具

**文件：**
- 修改：`server/src/api/expressions.ts`
- 修改：`server/src/api/expressions.test.ts`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionSync.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionSyncTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`

**步骤 1：编写失败测试**

服务端接口断言：常用词返回 4 张 `prebuilt` 且 `embeddedText` 正确；生僻词返回静态 `synthesis-template`；未知词不再返回空数组。Android 离线与远端结果遵循同一策略，旧请求仍不能覆盖新查询。

**步骤 2：运行测试验证失败**

```bash
cd server
npx vitest run src/api/expressions.test.ts
cd ../android/YuyanIme
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest \
  --tests com.yuyan.imemodule.expression.ExpressionSyncTest \
  --tests com.yuyan.imemodule.expression.ExpressionFlowTest
```

预期：FAIL，旧接口仍使用单层精确匹配语义。

**步骤 3：实现、复测并提交**

接口复用统一推荐策略，序列化新字段；Android 同步合并时保留预制与模板类型。重复步骤 2，预期 PASS。

```bash
git commit -m "feat: 同步两级斗图推荐接口"
```

### 任务 8：完整验证、代码审查和真机验收

**文件：**
- 仅在验证发现本计划范围内问题时修改对应文件和测试。

**步骤 1：服务端全量验证**

```bash
cd /home/ko/project/shurufa/server
npm test
npm run build
npm run expression:generate
cd ..
bash scripts/tests/expression-assets-test.sh
```

预期：所有测试通过；预制图数量、缩略图和 SHA 审计通过。

**步骤 2：Android 全量验证与 APK**

```bash
cd /home/ko/project/shurufa/android/YuyanIme
./gradlew --no-daemon :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug
```

预期：BUILD SUCCESSFUL，0 失败测试，生成 `app/build/outputs/apk/offline/debug/*.apk`。

**步骤 3：代码审查**

使用 `superpowers:requesting-code-review` 检查：

- 常用词图片确实含完整文字；
- 生僻词才进入动态合成；
- 轻点与长按互斥；
- 无确认弹层；
- 开关关闭后工具行仍在；
- 未混入现有采集、位置、Dashboard 等无关修改。

修复关键/重要问题时，每项继续使用 TDD 并独立提交。

**步骤 4：安装与真机验收**

手机重新连接后安装 APK，并验证：

1. 输入 `ni` 时不显示图片；
2. 选择“你好”后出现至少 4 张自带“你好”文字的图；
3. 生僻词出现多张带完整文字的 AI 合成图；
4. 不出现白板卡片；
5. 长按推荐区域隐藏键盘并展示三列全部图片；
6. 返回后恢复键盘；
7. 关闭后只保留空白工具行和右侧“AI斗图”入口；
8. 重新打开后恢复推荐；
9. 点击图片进入发送流程但在安全测试目标中阻止实际发送，确认没有确认弹层。

**步骤 5：最终仓库检查**

```bash
cd /home/ko/project/shurufa
git diff --cached --name-status
git diff --check <design-commit>..HEAD
git status -sb
```

预期：暂存区为空；计划提交范围无 whitespace 错误；原有未提交修改仍原样存在。

**步骤 6：完成分支**

使用 `superpowers:verification-before-completion` 和 `superpowers:finishing-a-development-branch`，报告测试、APK、真机验证和未推送提交状态，由用户决定是否推送。

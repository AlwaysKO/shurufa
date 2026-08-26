# 搜狗式图片推荐交互与键盘布局优化实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 让图片推荐只在文字确认上屏后按词语精确出现，支持清晰标签状态和展开网格，并把模板构图、候选关闭区及中英文键盘几何校准到已连接手机上的搜狗体验。

**架构：** 以候选提交事件作为推荐查询唯一入口；服务端与离线目录共享“精确匹配或空结果”规则，并用推荐类型区分原图发送和模板文字合成。面板用纯状态机控制紧凑/展开布局，键盘尺寸用独立规格对象集中管理。

**技术栈：** Kotlin、Android View/RecyclerView、Robolectric、Kotlin Coroutines、TypeScript、Vitest、Sharp、Gradle、ADB。

---

### 任务 1：把推荐触发从候选变化改为最终上屏

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinatorTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinator.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`

**步骤 1：编写失败测试**

增加以下场景：

```kotlin
coordinator.onComposingChanged("你")
delay(200)
assertTrue(seen.isEmpty())

coordinator.onCommitted("你好")
delay(200)
assertEquals(listOf("你好"), seen)
```

同时验证组合态变化会使旧请求失效，但提交后候选清空不会立即清除已提交查询；完整链路测试必须从 `onCommitted()` 开始。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest --tests '*ExpressionQueryCoordinatorTest' --tests '*ExpressionFlowTest'
```

预期：FAIL，提示 `onComposingChanged` 不存在或仍发布组合态候选。

**步骤 3：编写最少实现**

- 用 `onComposingChanged(text)` 取代 `onFirstCandidate(text)` 的发布职责，只做失效/清理判定。
- `onCommitted(text)` 作为唯一防抖发布入口。
- 在 `InputView` 候选观察者中只清理组合态推荐。
- 在 `chooseAndUpdate()` 的所有最终上屏分支集中调用 `onCommitted()`，删除候选点击回调中的重复通知。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinator.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionQueryCoordinatorTest.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt
git commit -m "fix: 仅在候选上屏后触发表情推荐"
```

### 任务 2：只返回词语精确关联的预制推荐

**文件：**
- 修改：`server/src/expression/catalog.test.ts`
- 修改：`server/src/api/expressions.test.ts`
- 修改：`server/src/expression/catalog.ts`
- 修改：`server/src/api/expressions.ts`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt`
- 修改：`server/scripts/crop-expression-contact-sheets.ts`
- 生成：`assets/expression/manifest.source.json`

**步骤 1：编写失败测试**

服务端和 Android 均增加断言：

```typescript
expect(recommendExpressionAssets(assets, '你好').map(({ id }) => id))
  .toEqual(['hello-high', 'hello-low']);
expect(recommendExpressionAssets(assets, '未知词')).toEqual([]);
```

API 测试额外断言返回项 `type` 为 `recommendation`，未知词 `results` 为空。Android 测试断言推荐结果为复制后的 `recommendation`，而目录原素材仍为 `template`。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd server && npm test -- src/expression/catalog.test.ts src/api/expressions.test.ts
cd ../android/YuyanIme && ./gradlew :yuyansdk:testDevDebugUnitTest --tests '*ExpressionCatalogTest'
```

预期：FAIL，当前实现会把无关热门模板作为兜底返回。

**步骤 3：编写最少实现**

- 将服务端排序函数改为先过滤完整关键词精确匹配，再按热度和原顺序排序。
- `/recommend` 把命中素材输出为 `type: recommendation`。
- Android 离线 `search()` 使用同一过滤规则并返回 `copy(type = "recommendation")`。
- 在源清单生成定义中为常见词配置多个语义匹配素材，至少覆盖“你好、谢谢、加油、晚安、早安、再见、抱歉、喜欢、不要、快点”等；不配置通用兜底。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add server/src/expression/catalog.ts server/src/expression/catalog.test.ts \
  server/src/api/expressions.ts server/src/api/expressions.test.ts \
  server/scripts/crop-expression-contact-sheets.ts assets/expression/manifest.source.json \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionCatalog.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionCatalogTest.kt
git commit -m "feat: 按完整词语返回预制表情推荐"
```

### 任务 3：区分推荐原图与模板文字合成

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicy.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicyTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`

**步骤 1：编写失败测试**

```kotlin
assertFalse(ExpressionRenderPolicy.shouldOverlayText(recommendation, "你好"))
assertTrue(ExpressionRenderPolicy.shouldOverlayText(template, "你好"))
```

并在链路测试中记录渲染调用次数，验证推荐直接准备源文件而模板仅渲染一次。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest --tests '*ExpressionRenderPolicyTest' --tests '*ExpressionFlowTest'
```

预期：FAIL，策略不存在且当前所有带安全区素材都会合成文字。

**步骤 3：编写最少实现**

增加无 Android 依赖的策略对象，仅当 `asset.type == "template"`、查询非空且布局完整时返回 true；`InputView.prepareAsset()` 统一使用该策略。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicy.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/render/ExpressionRenderPolicyTest.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionFlowTest.kt
git commit -m "fix: 推荐原图不再叠加输入文字"
```

### 任务 4：增加标签选中标记和推荐展开网格

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/color/selector_expression_tab_text.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/drawable/selector_expression_tab_background.xml`

**步骤 1：编写失败测试**

- 状态测试：新结果默认为 `COMPACT`，再次点击当前标签或切换标签后为 `EXPANDED`，新查询恢复紧凑态。
- Robolectric 测试：推荐标签 `isSelected == true` 且背景存在；紧凑态为横向 `LinearLayoutManager`，展开态为三列 `GridLayoutManager`；三个标签切换时选中状态唯一。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest --tests '*ExpressionPanelStateTest' --tests '*ExpressionPanelTest'
```

预期：FAIL，展示状态和网格布局尚不存在。

**步骤 3：编写最少实现**

- 增加 `ExpressionPanelPresentation.COMPACT/EXPANDED`。
- `selectTab()` 负责展开；新查询、清理和关闭恢复紧凑态。
- 紧凑态使用 116dp 单行横向列表；展开态使用三列纵向网格和 300dp 内容区。
- 给三个标签应用 selected 文本色与 3dp 底部指示线。
- 展开网格中的图片项改为固定正方形尺寸和一致间距。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ExpressionPanelState.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/expression/ui/ExpressionPanel.kt \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_expression_panel.xml \
  android/YuyanIme/yuyansdk/src/main/res/layout/sdk_item_expression_asset.xml \
  android/YuyanIme/yuyansdk/src/main/res/color/selector_expression_tab_text.xml \
  android/YuyanIme/yuyansdk/src/main/res/drawable/selector_expression_tab_background.xml \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ExpressionPanelStateTest.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/expression/ui/ExpressionPanelTest.kt
git commit -m "feat: 增加表情标签状态与展开网格"
```

### 任务 5：裁去素材顶部冗余并保持文字覆盖在图片内

**文件：**
- 修改：`server/src/expression/assetGenerator.test.ts`
- 修改：`server/src/expression/assetGenerator.ts`
- 修改：`server/scripts/crop-expression-contact-sheets.ts`
- 生成：`assets/expression/manifest.source.json`
- 生成：`server/.runtime/expression-assets/**`
- 生成：`android/YuyanIme/yuyansdk/src/main/assets/expression/**`
- 修改：`scripts/tests/expression-assets-test.sh`

**步骤 1：编写失败测试**

构造上半部空白、下半部有主体色块的测试图，配置 `sourceCrop` 后断言输出 512×512 且顶部像素来自裁剪后的主体区域；资源审计断言全部模板裁剪框在源图范围内、安全区仍在 512×512 输出范围内。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd server && npm test -- src/expression/assetGenerator.test.ts
cd .. && bash scripts/tests/expression-assets-test.sh
```

预期：FAIL，生成器尚不识别 `sourceCrop`。

**步骤 3：编写最少实现**

- 为源模板增加可选裁剪框并在静态图、GIF、缩略图进入 resize 前统一应用。
- 60 个模板使用统一的向下裁剪框去除联系表边框和大块顶部背景。
- 调整文字安全区到裁剪后的画面内部，保持直接覆盖而不是拼接留白区。
- 重新执行 `npm run expression:crop` 和 `npm run expression:generate`，更新运行时与 Android 内置清单/文件。

**步骤 4：运行测试验证它通过**

运行：

```bash
cd server && npm test -- src/expression/assetGenerator.test.ts && npm run expression:generate
cd .. && bash scripts/tests/expression-assets-test.sh
```

预期：PASS，输出仍为 60 templates / 20 GIF / 40 static / 48 emoji bases / 2304 combinations。

**步骤 5：提交**

```bash
git add server/src/expression/assetGenerator.ts server/src/expression/assetGenerator.test.ts \
  server/scripts/crop-expression-contact-sheets.ts assets/expression/manifest.source.json \
  server/.runtime/expression-assets android/YuyanIme/yuyansdk/src/main/assets/expression \
  scripts/tests/expression-assets-test.sh
git commit -m "fix: 让模板主体铺满并在图片内叠字"
```

### 任务 6：把候选关闭区域改为不透明

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/view/CandidateOverlaySpecTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/res/drawable/shape_candidate_action_overlay.xml`

**步骤 1：编写失败测试**

在 Robolectric 中取得关闭按钮背景，断言背景在默认状态下的起始像素 alpha 为 255，而不是透明渐变。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest --tests '*CandidateOverlaySpecTest'
```

预期：FAIL，当前渐变起点透明。

**步骤 3：编写最少实现**

将 drawable 改为使用输入法背景色的纯色矩形，不改变 48dp 点击区域和悬浮层级。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/res/drawable/shape_candidate_action_overlay.xml \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/view/CandidateOverlaySpecTest.kt
git commit -m "fix: 使用不透明候选关闭背景"
```

### 任务 7：校准中文和英文全键布局

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouQwertyLayout.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouQwertyLayoutTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt`
- 修改：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt`

**步骤 1：编写失败测试**

锁定搜狗截图对应的几何：

```kotlin
assertEquals(0.99f, SogouQwertyLayout.letterRowWidth, 0.0001f)
assertArrayEquals(expectedBottomCodes, SogouQwertyLayout.bottomRowCodes)
assertEquals(0.99f, SogouQwertyLayout.bottomRowWidths.sum(), 0.0001f)
assertTrue(SogouQwertyLayout.X_MARGIN_SCALE < 1f)
```

同时校验第二行偏移、Shift/Delete 对称、七键底栏、中文九键纵向间距缩放。

**步骤 2：运行测试验证它失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest --tests '*SogouQwertyLayoutTest' --tests '*SogouT9LayoutTest'
```

预期：FAIL，`SogouQwertyLayout` 尚不存在。

**步骤 3：编写最少实现**

- 创建纯规格对象，按真机截图配置 10/9/9 字母行与七键底栏。
- 中文全键和英文全键统一使用该规格创建行、Shift/Delete、底栏和按键间距。
- 九键只微调纵向 margin scale，保持现有列宽、语音空格和文字回车逻辑。

**步骤 4：运行测试验证它通过**

运行同上，预期：PASS。

**步骤 5：提交**

```bash
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouQwertyLayout.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouQwertyLayoutTest.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/SogouT9Layout.kt \
  android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/keyboard/SogouT9LayoutTest.kt \
  android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/utils/KeyboardLoaderUtil.kt
git commit -m "feat: 校准搜狗式中英文键盘几何"
```

### 任务 8：完整回归、APK 和真机验证

**文件：**
- 仅在发现本计划直接相关问题时修改对应文件；任何修复继续先补失败测试并独立提交。

**步骤 1：运行服务端完整验证**

```bash
cd server
npm test
npm run build
npm run expression:generate
```

预期：全部测试通过，TypeScript 构建成功，素材数量保持不变。

**步骤 2：运行 Android 完整验证**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testDevDebugUnitTest :app:assembleDevDebug
```

预期：全部 JVM/Robolectric 测试通过，生成 devDebug APK。

**步骤 3：运行资源审计**

```bash
cd /home/ko/project/shurufa
bash scripts/tests/expression-assets-test.sh
```

预期：清单、数量、尺寸、哈希和 Android 内置子集全部通过。

**步骤 4：安装到已连接手机并复测**

```bash
ADB=/mnt/c/Windows/Temp/codex-platform-tools/platform-tools/adb.exe
$ADB devices
$ADB install -r android/YuyanIme/app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

手工检查：输入 `ni` 时不显示推荐；选择“你”或“你好”后才显示；未知词不显示；推荐不叠字；当前标签有指示线；点击当前标签展开网格；关闭按钮不透字；中英文键宽、间距和底栏与搜狗截图一致。

**步骤 5：复核提交边界**

```bash
git status --short
git log --oneline --decorate -12
git diff --check HEAD~7..HEAD
```

预期：本任务提交均为分阶段小提交；原有未提交文件仍保持未暂存，且未被纳入任何提交。

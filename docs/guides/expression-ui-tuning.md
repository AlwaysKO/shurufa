# 候选文字和斗图面板：自己调一版

这是 Android 原生 View，不是网页 CSS；改参数后通常要重新构建、安装 APK 并重启输入法，不支持浏览器式热刷新。无需修改手机系统字号。

## 打开工程

Windows Android Studio：打开 `E:\Projects\shurufa-android\YuyanIme`。
WSL 对应工程：`/home/ko/project/shurufa/android/YuyanIme`。先确认 Windows 工程和 WSL 工程确实是同一份文件（编辑后在 WSL 用 `git diff` 确认），不要在两个副本里分别改。

以下源码路径均相对 `android/YuyanIme/yuyansdk/src/main/`。

| 截图位置                 | 文件、参数                                                                                          | 当前值和怎么调                                                                                               |
| ------------------------ | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| 候选行“你、密、米”等汉字 | `java/com/yuyan/imemodule/adapter/CandidatesBarAdapter.kt` 的 `CANDIDATE_TEXT_SCALE`                | 按你自己设置的倍率保留；增大更大、减小更小，本轮不修改                                                       |
| 候选上方“ni”拼音         | `java/com/yuyan/imemodule/view/CandidatesBar.kt` 的 `COMPOSING_TEXT_SCALE`                          | 按你自己设置的倍率保留，本轮不修改                                                                           |
| 推荐行更多/关闭按钮      | `java/com/yuyan/imemodule/expression/ui/ExpressionLayoutMetrics.kt` 的 `COMPACT_ACTION_DP`          | 每颗 `32f` dp，总宽 64dp；增大将挤占标签空间                                                                 |
| 更多/关闭背景上下空隙    | 同文件 `ACTION_VERTICAL_MARGIN_DP`                                                                  | **`3f` dp**；试 `4f` 留白更多、`2f` 留白更少。背景整体高度 = 标签行高 − 两倍空隙，垂直居中；不是图标 padding |
| 推荐图片与上方标签的距离 | 同文件 `CONTENT_TOP_GAP_DP`                                                                         | **`4f` dp**；试 `6f` 更疏、`2f` 更紧。总高度同步补足，不挤掉图片或 Emoji 的净高                              |
| 气泡内部上下留白/尾角    | `java/com/yuyan/imemodule/expression/ui/AiDoutuBadgeDrawable.kt`                                    | `verticalInsetDp=2`、`tailHeightDp=3`，单位 dp                                                               |
| 按钮图标视觉大小         | `res/layout/sdk_expression_panel.xml` 中 `expression_more`、`expression_close` 的 `android:padding` | `6dp`；背景当前高26dp、宽32dp，padding 越大图标越小；这不控制背景外部上下空隙                                |
| 标签左右间距             | 同 XML 中三个 tab 的 `paddingStart/End`                                                             | Emoji 和 AI 合成各4dp，越大越挤                                                                              |
| 标签字号上下限           | `ExpressionPanel.kt` 的 `MIN_TAB_TEXT_SIZE_SP/MAX_TAB_TEXT_SIZE_SP`                                 | 自动缩放并限制单行；改后验证“Emoji合成”完整显示，不依赖省略号                                                |

dp 是布局尺寸，sp 是跟随系统字体缩放的字号。候选沿用现有 DIP 字号计算，仅在候选入口乘倍率，不影响键帽、剪贴板和符号；不是 GIF 内的文字。拼音行按实际字体 ascent/descent 自动补足高度；汉字行保持原触摸高度。继续加大字号后仍须检查裁切和键盘总高度。

**容易踩坑：** XML 只是初始值。`ExpressionPanel.applyLayoutMetrics` 会在运行时覆盖 tab 行高、内容行高、action 区宽高及图片区顶部 padding；只改 XML 的 `layout_height` 看不到预期效果时，应改 `ExpressionLayoutMetrics.kt`。同步检查 `ExpressionPanel.kt` 的紧凑按钮最小高度门槛，不要让它比新行高还大。Emoji 两层选择保留独立 44dp 的内容触摸尺寸，不跟着缩小。

注意：正常 369dp 宽度以及 288dp、1～1.5 倍字体已验证标签完整；288dp 配合 2 倍系统字体时，8sp 自动字号下限仍可能让“Emoji合成”被截断。该极端组合只保证操作按钮不越界，本轮没有继续缩到更小字体或重做标签布局。

## 本轮共行规则

```text
推荐 / AI合成 / Emoji合成         更多 关闭
                 ↓ CONTENT_TOP_GAP_DP = 4f
推荐 GIF 图片
拼音 ni                          AI斗图
候选汉字……
```

`CandidatesBar.kt` 的 `composingRow` 同时承载拼音和右侧按钮，拼音用权重分配剩余宽度、单行过长省略，不能覆盖按钮。`InputView.initExpressionPanel` 绑定原按钮实例，保留原来的主题和收起/恢复回调。

- 有拼音但没有推荐结果：只显示拼音，无无效按钮。
- 有推荐结果：显示 AI斗图，点它收起/恢复已有结果；收起后仍保留按钮。
- 没有拼音也没有推荐结果：整行隐藏，不占高度。
- 总开关关闭或非聊天输入：不显示斗图按钮。首次搜索仍从原工具栏的斗图入口进入。

旧 `expression_tool_row` 仅作为 XML 初始化时的按钮宿主，实际始终 GONE、0 高；按钮会移入候选栏。**不要再调旧工具行高度，也不再有 `COMPACT_TOOL_DP`/`CONTENT_VERTICAL_SPACE_DP` 参数。** 气泡自身 `verticalInsetDp` 只影响内部绘制，不能用于补偿不存在的独立工具行。

要调你问的两处，只需要在 `ExpressionLayo utMetrics.kt` 修改 `CONTENT_TOP_GAP_DP` 和 `ACTION_VERTICAL_MARGIN_DP`，一次调一个，再构建安装比较。空隙不要设负数，按钮上下空隙不应大到挤没图标；保留测试检查小屏、Emoji 可访问尺寸及键盘预算。

## WSL 构建、安装、重新看效果

先确认工作区改的是预期文件。`local.properties` 保留 Windows SDK 路径；2026-09-14 起环境脚本只导出 WSL 环境变量，不再覆盖此文件，不需要备份/恢复 SDK 配置。

```bash
cd /home/ko/project/shurufa/android/YuyanIme
source /home/ko/android-tools/env.sh
./gradlew --project-cache-dir /tmp/shurufa-gradle-wsl-production \
  :yuyansdk:testOfflineDebugUnitTest \
  --tests '*CandidatesBarTest' --tests '*ExpressionPanelTest' \
  --tests '*ExpressionLayoutMetricsTest' --tests '*ExpressionViewportObstructionTest'
./gradlew --project-cache-dir /tmp/shurufa-gradle-wsl-production :app:assembleOfflineDebug
find app/build/unix/outputs/apk -name '*offline*debug*.apk' -o -name '*.apk'
```

安装时将上面找到的 **offline/debug APK** 路径转换给 Windows adb（不要选 release/其他 flavor）：

```bash
ADB=/mnt/e/AndroidSDK/platform-tools/adb.exe
APK=/实际找到的/offline/debug/APK路径.apk
"$ADB" install -r "$(wslpath -w "$APK")"
"$ADB" shell am force-stop com.yuyan.pinyin.offline.debug
"$ADB" shell ime list -s
```

随后在手机输入法选择器重新选择本项目输入法，回到测试输入框：输入“ni”看候选和拼音，输入“谢谢”看三标签、按钮和气泡。截图比较一次只改一个参数。不要用群聊发送测试。

结束后恢复原输入法和常亮设置：

```bash
"$ADB" shell ime set com.sohu.inputmethod.sogou/.SogouIME
"$ADB" shell svc power stayon false
```

Android Studio 也可选 offlineDebug 变体后 Build/Run；原生代码/XML变更不能承诺无需重装或重启 IME。保持 Windows SDK 配置，不要把 WSL SDK 路径作为团队源码提交。

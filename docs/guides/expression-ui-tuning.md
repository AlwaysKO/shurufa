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
| 顶部 AI 斗图底色/圆角 | `java/com/yuyan/imemodule/adapter/CandidatesMenuAdapter.kt` 的 `aiDoutuBackground` | 蓝紫浅渐变，圆角12dp、左右内缩3dp、上下1dp；旧拼音胶囊已隐藏 |
| 按钮图标视觉大小         | `res/layout/sdk_expression_panel.xml` 中 `expression_more`、`expression_close` 的 `android:padding` | `6dp`；背景当前高26dp、宽32dp，padding 越大图标越小；这不控制背景外部上下空隙                                |
| 标签左右间距             | 同 XML 中三个 tab 的 `paddingStart/End`                                                             | Emoji 和 AI 合成各4dp，越大越挤                                                                              |
| 标签字号上下限           | `ExpressionPanel.kt` 的 `MIN_TAB_TEXT_SIZE_SP/MAX_TAB_TEXT_SIZE_SP`                                 | 自动缩放并限制单行；改后验证“Emoji合成”完整显示，不依赖省略号                                                |

dp 是布局尺寸，sp 是跟随系统字体缩放的字号。候选沿用现有 DIP 字号计算，仅在候选入口乘倍率，不影响键帽、剪贴板和符号；不是 GIF 内的文字。拼音行按实际字体 ascent/descent 自动补足高度；汉字行保持原触摸高度。继续加大字号后仍须检查裁切和键盘总高度。

**容易踩坑：** XML 只是初始值。`ExpressionPanel.applyLayoutMetrics` 会在运行时覆盖 tab 行高、内容行高、action 区宽高及图片区顶部 padding；只改 XML 的 `layout_height` 看不到预期效果时，应改 `ExpressionLayoutMetrics.kt`。同步检查 `ExpressionPanel.kt` 的紧凑按钮最小高度门槛，不要让它比新行高还大。Emoji 两层选择保留独立 44dp 的内容触摸尺寸，不跟着缩小。

注意：正常 369dp 宽度以及 288dp、1～1.5 倍字体已验证标签完整；288dp 配合 2 倍系统字体时，8sp 自动字号下限仍可能让“Emoji合成”被截断。该极端组合只保证操作按钮不越界，本轮没有继续缩到更小字体或重做标签布局。

## AI 斗图单入口（2026-09-15 用户确认）

本节取代 2026-09-07 的“拼音与右侧 AI 斗图胶囊共行”布局；拼音本身及字号不变。

- 拼音行旧 `expression_enable` 始终 GONE；没有拼音时不因有推荐图而留下空行。保留内部 View 仅兼容现有绑定，不作为用户入口。
- 顶部工具栏为唯一 AI 斗图入口，使用笑脸对话框与星光图标（`res/drawable/ic_menu_ai_sticker_search.xml`）、蓝紫强调色、淡渐变圆角底、粗体单行文字。其他槽位不变。
- 有输入时点击直接打开 AI 合成无字 GIF 池；再次点击关闭，再次重开。打开后切换 Emoji/推荐标签也不改变“再点关闭”的规则。自动成品推荐已显示时，手动入口先切到合成池。
- 无输入仍提示先输入；普通搜索/密码/不能发图片的输入框不显示推荐。不用手动操作绕过聊天场景门禁。
- AI 合成展示所有无字、可编辑 GIF，相关关键词前置，不再让未知词得到空白页。不现场批量给预览叠字，点选后才用完整输入文字渲染原 GIF。
- 自动成品未命中时，明确玩笑表达可走广池兜底；判断使用本地保守规则，不调用大模型，不保证覆盖任意幽默。跨端样例在 `assets/expression/query/synthesis-intent-cases.json`。
- 四张新素材为隔离样片，待用户动态审核；当前运行目录仍为已有素材，不能把旧模板当作新广覆盖素材已交付。

推荐图片区间距仍由 `ExpressionLayoutMetrics.kt` 的 `CONTENT_TOP_GAP_DP=4f` 与 `ACTION_VERTICAL_MARGIN_DP=3f` 控制。旧 `expression_tool_row` 继续 GONE，不要通过它补高度。

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

## 2026-09-16 推荐/合成分离
- 推荐标签仅在 ExpressionPanelState.results 存在 prebuilt 时显示；未命中时隐藏该标签，不把合成模板放进推荐结果。
- AI标签从 catalog.synthesisTemplates(query) 读取无字池；手动入口保留同词已命中的推荐，默认打开AI；再次点击工具栏关闭。
- 自动空回调不抢页；自动合成兜底后若远端命中成品则回推荐；用户主动选AI/Emoji时不抢页。
- 正式合成仅6个 blank-* 动作GIF，旧60个源归 prebuiltSourceTemplates，仅用于维持原带字推荐，不打入APK合成目录；退役ID通过目录和缓存过滤防止旧图复活。
- 当前gradlew已自动注入project-cache-dir，不要重复传旧示例中的同名参数。此次并行工作使用本地临时init脚本隔离build输出，详情见 docs/plans/2026-09-16-ai-pool-apk-replacement.md。

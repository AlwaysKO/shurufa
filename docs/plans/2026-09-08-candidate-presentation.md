# 候选输入显示修复实现计划

**目标：** 首候选主题强调色、左侧拼音缩小并留白、九宫格候选态显示灰底“确定”，上屏后恢复编辑器动作（聊天换行）。

**架构：** 保留候选栏现有一基激活编号，默认编号 0 映射首项。PrefixAdapter 区分拼音字母与符号字号，不改标点 60f 和四格高度。九宫格 Enter 增加候选确认状态，候选数据变化时刷新键盘状态，联想词不算待确认候选。

**技术栈：** Kotlin、RecyclerView、Robolectric。

1. adapter/PrefixAdapterLayoutTest.kt 添加拼音字号和左右留白测试；新增 adapter/CandidatesBarPresentationTest.kt 验证默认首项、方向键选择与复用恢复颜色。
2. keyboard/ExpressionManualSearchInputViewTest.kt 添加真实键盘候选/清空/联想态切换回归。
3. 运行 :yuyansdk:testOfflineDebugUnitTest 定向用例确认红。
4. 修改 CandidatesBarAdapter.kt、PrefixAdapter.kt、SogouKeyboardTypography.kt、SogouT9Layout.kt、TextKeyboard.kt、InputView.kt 最小实现。
5. 重跑定向和相关键盘测试、构建 offline debug APK；恢复原 Windows local.properties。没有真机截图时不宣称像素级验证。
6. “确定”提交拼音还是首候选：已向用户澄清，答复前不修改既有点击行为。不提交或覆盖工作区已有修改。

## 验证结果
- 新增颜色和拼音用例先观察到断言失败；状态用例需真实挂载 InputView 激活 LiveData 后验证。
- 审查发现全键盘也使用 preferTextLabel，新增 SoftKeyToggle.hasToggleState，仅支持确认状态的键才切换标签和背景。新增全键盘回归先观察到状态 1 被错误切为 8，修正后通过。
- 扩大回归：CandidatesBarPresentationTest、PrefixAdapterLayoutTest、ExpressionManualSearchInputViewTest、SogouKeyboardHitMapTest、SogouKeyboardTypographyTest、CandidatesBarTest，共 88 项，0 失败/错误/跳过。
- :app:assembleOfflineDebug 成功，产物 android/YuyanIme/app/build/unix/outputs/apk/offline/debug/yuyanIme_2026090811_debug.apk。
- git diff --check 通过，local.properties 已恢复。adb devices 无设备，未安装、未进行真机视觉验收。
- 确定键点击提交内容维持原逻辑；本轮仅修改所述显示状态，等待用户明确是否改为选择首候选。

# AI斗图入口与整句合成验证计划

**目标：** 验证现有句意匹配模板、完整原句叠字链路，让工具栏搜索按钮可见地标明“AI斗图”。Emoji 不修改。
**架构：** 保留现有 ExpressionCatalog.synthesisTemplates 与 ExpressionRenderer，不重复实现已有功能。工具栏仅 AiDoutu 显示底部文字，其余项维持图标、尺寸、路由。
**技术栈：** Kotlin、Android XML、JUnit/Robolectric。

1. CandidatesMenuAdapterTest 新增可见文字和复用隐藏回归，先运行确认失败。
2. sdk_item_recyclerview_candidates_menu.xml 增加文字，CandidatesMenuAdapter.kt 按模式显示、主题着色和调整图标空间，不改变点击路由。
3. 运行 Adapter、CandidatesBar、手动搜索、Catalog、匹配、Panel、渲染策略和真实素材渲染测试；构建 offline debug APK。
4. 检查 diff、恢复 local.properties，记录测试和设备验收边界。模板语义覆盖仍受素材库限制。

## 验证记录

- 可见标签测试在旧代码上 5 项中 1 项按预期失败（缺少文字视图）。
- 121 项既有定向回归通过：CandidatesBar 9、手动搜索 InputView 54、Catalog 8、QueryMatching 3、Panel 40、RenderPolicy 3、Renderer 4。真实 tpl-02 生成完整“谢谢你今天帮忙”GIF，帧数和延迟保留，已目视检查文字。
- 批量测试初次默认堆不足，改用临时 init 脚本将测试堆设为 1536m；不改项目配置。布局尺寸断言使用 Robolectric NATIVE 图形模式，避免 legacy 字体度量影响。
- 工作区有其他并行开发：新增 capture 测试依赖尚未完成、并发 Gradle 删除同一编译目录。最终工具栏验证使用 build/ai-toolbar-validation、独立 /tmp 项目缓存，期间曾尝试临时排除外部未完成测试；最终外部依赖已补齐，移除排除配置后正常编译全部测试源码，仅执行工具栏定向测试，不修改外部代码，也不宣称全工作区测试通过。
- 只读审查无阻断缺陷；模板语义覆盖受素材库限制；未做真机聊天发送验收。

- 最终原生图形布局测试与工具栏 5 项测试全部通过，标签完整不裁切且不与图标重叠；offline debug APK 构建成功。累计两批定向验证 126 项通过。SDK 路径已恢复，Emoji 未修改。

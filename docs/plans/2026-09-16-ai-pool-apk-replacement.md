# AI合成图库替换与推荐分离 实现计划

**目标：** 只删除旧AI合成模板，保留带字推荐GIF；命中显示推荐，未命中隐藏推荐标签；新无字GIF真正进入APK。
**架构：** 旧静态源仅保留为带字推荐的构建输入，不输出为合成模板。推荐状态过滤prebuilt；自动合成结果触发AI标签，手动入口保留同词推荐并即时打开无字池。同步时屏蔽旧缓存合成模板。
**技术栈：** Kotlin/Robolectric、TypeScript/Vitest、Sharp、内置image_gen。

1. 在ExpressionPanelStateTest、ExpressionManualSearchInputViewTest和assetGenerator.test写失败回归：干嘛无推荐、谢谢有推荐；旧合成不入catalog/APK但预制图不变。
2. 最小修改ExpressionPanelState/ExpressionPanel/InputView，检查缓存与ExpressionSync不复活旧合成。
3. manifest分开正式templates与prebuiltSourceTemplates，仅保留新六张合成，旧源供带字图构建；生成服务端和Android资源，检查旧模板文件已移除、新六张哈希一致。
4. 继续生成两种不同情绪样片并输出审计/预览；新稿需视觉确认，质量失败不入库。
5. 服务端构建测试、Android定向测试、assembleOfflineDebug；解包APK核对模板ID、GIF字节与旧图不存在；独立代码审查；可用时检查设备，不未经核实宣称安装或真机通过。

本次用户确认删除仅限旧AI合成模板，带字推荐保留；当前目录执行，不创建worktree，不自动提交或清理他人改动。

## 执行中记录
- 生成器回归先红后绿，54项通过。旧60项迁为prebuiltSourceTemplates，仅用于保持历史带字预制原字节，不再输出合成图及其缩略图。
- Android源目录已生成版本2026.09.16.blank-only-6，合成6/预制244/退役60。实际模板目录仅6个GIF，SHA与批准记录一致。
- 本机正式运行目录已替换；244项带字推荐原图和缩略图与旧目录逐文件比对相同，备份保留于server/.runtime/expression-assets-before-pool-1789545163。
- 服务端全量：54个测试文件通过、1个跳过；537项通过、6项原有跳过。使用--testTimeout=120000避免图像批处理在构建争用时触发5秒默认超时。
- 审查要求补双阶段结果和已隐藏推荐页边界；普通构建出现Kotlin旧构造器缓存不一致，后续改用本地build/ai-pool-check隔离输出复验，不改共享SDK配置。
- 本轮新两张隔离试稿在artifacts/expression-character-trials/ai-synthesis-blank-03，机器审计通过，等待用户动态审核，不擅自扩大先前六张批准范围。

## 最终交付与验收（2026-09-16）
- 审查指出的两种异步切页问题均经失败回归后修复；state单独JUnit14项通过。
- Android隔离定向验证82项全通过：Catalog10、PanelState14、Sync14、Panel UI40、生产InputView入口4。测试源为原项目5份测试的本地快照，未改断言绕过其他模块错误；未宣称全量Android测试通过。
- APK构建通过：android/YuyanIme/app/build/ai-pool-check/outputs/apk/offline/debug/yuyanIme_2026091616_debug.apk。
- ZIP检查通过：版本2026.09.16.blank-only-6；合成ID恰为批准6项；模板路径恰6个真实GIF；逐个SHA符合批准记录；60项退役记录；244项带字推荐元数据保留。
- 连接手机安装返回Success，包版本20260916.16，设备lastUpdateTime为2026-09-16 16:08:38。
- 设备已安装base.apk与本地APK SHA完全一致：5ba5978fd7138bebb03542af4a3ebc5f00999b054f6cbb322bea7061ef4acc88。
- 安装后系统默认回退搜狗，已恢复安装前选中的本项目DebugGifImeService，未更改其他系统偏好。
- 未向聊天联系人发送测试图片，未宣称本轮已通过宿主端实际动态发送验收。未清用户应用数据、未部署远端、未自动提交或推送。

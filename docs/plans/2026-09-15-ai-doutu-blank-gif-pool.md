# AI 斗图无字 GIF 池与单入口 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划；当前分支，不创建 worktree。

**目标：** 工具栏 AI 斗图循环开关、删除拼音胶囊，未命中且具有玩笑语气时展示可叠字动态池；先制作 4 张隔离样片。

**架构：** 复用现有目录、文字渲染器与发送器。手动入口直接展示全部可编辑无字 GIF，相关情绪前置但不排除其他情绪；自动仅在成品未命中且本地保守玩笑表达规则命中时兜底。规则不是大模型，不声称理解任意幽默；不新增付费服务或上传输入。保留原有相关模板推荐兼容性。新样片不进 catalog/API/APK；旧图不改。

**技术栈：** Kotlin、Android XML、JUnit/Robolectric、TypeScript/Vitest、内置图像工具、既有 GIF 编码工具。

## 用户确认与现行边界

来源：2026-09-15 本次对话，用户确认删除拼音行右侧胶囊、只保留顶部入口；同意功能与新素材一起做，先验收 4 张再扩充。
自动推荐仅适用可发送图片的聊天编辑器；无输入手动点击保留提示。保留候选字号、Emoji、已有预制图及其他未提交开发。
素材遵守 assets/expression/CREATION-POLICY.md：无字真实姿势动画、约 4 秒、240×240、低于 250KB，用户动态审核与机器验证分离。只提交最终 GIF 及必要文字代码，中间母图只留本地。

### 任务 1：模板池与自动判断
- 修改 Android ExpressionCatalog.kt，新增保守 ExpressionSynthesisIntent.kt；对应 ExpressionCatalogTest.kt / intent 测试。
- 修改 server/src/expression/catalog.ts，独立 synthesisIntent.ts；不修改用户正在编辑的 queryMatching.ts。
- 测试先覆盖手动未知词仍返回多情绪无字可编辑 GIF、空输入不返回、排除成品/静态/无排版资产、相关优先、自动普通词不泛推、调侃未命中兜底、成品仍优先。
- 先运行失败测试，再最小实现，运行服务端定向 Vitest 与 Android 单测。

### 任务 2：顶部入口与 UI
- 修改 InputView.kt：手动直接本地选 AI 合成页；再次点击取消请求并关闭，避免迟到响应复活；第三次恢复。保留输入场景门禁与无字提示。
- 修改 ExpressionPanel.kt：隐藏旧胶囊；不隐藏拼音本身。
- 修改 CandidatesMenuAdapter.kt 和专属图标资源：轻量蓝紫点缀、清楚文字、完整点击区域，复用时不污染其他项。
- 先写 ExpressionManualSearchInputViewTest / ExpressionPanelTest / CandidatesMenuAdapterTest 的失败用例；实现后跑相关回归。

### 任务 3：四张无字动作样片
- 目录 artifacts/expression-character-trials/ai-synthesis-blank-01，trial-only。
- 四方向：写实动物侧眼嫌弃、原创中国成年男性偷笑、极简双角色抓狂、原创立体角色得意。
- 内置图像工具生成每张 4×3 十二姿势母版；真实过渡、固定主体及背景、下方留文字区、无文字和品牌。
- 沿用既有工具裁切编码，不用缩放/倒放/交叉淡化伪装动作。核查原图、解码帧、尺寸/时长/大小/循环/SHA，输出预览与来源记录。
- 若工具不可用，明确报告并停止素材制作，不擅自切换付费 CLI。

### 任务 4：交付验证
- 定向测试、Android offline debug 构建、git diff --check、独立代码审查。
- 记录测试数量与失败情况；未连接设备不声称真机发送成功；未审核样片不声称广覆盖素材池已交付。

## 2026-09-15 实施与验证记录

- 本地规则与完整无字 GIF 池、手动打开/关闭/跨标签关闭、拼音胶囊隐藏、顶部笑脸星光图标与蓝紫底色已实现。原有相关模板匹配保持兼容；广池兜底只增加在明确玩笑表达场景。
- 手动选图直接用当前本地目录，不发起关键词推荐请求；自动推荐继续复用原缓存与异步管道。预制成品仍原图发送，合成模板点选时叠完整当前输入。
- 服务端第一轮全套475项中新增兜底1项预期失败；Android模板9项中2项预期失败、UI100项中4项预期失败。
- 旧UI回归另发现3处旧契约断言（统一图标颜色、未知词空态、手动必联网），依据已确认的新契约更新，不以改测试掩盖代码错误。
- 独立审查发现认真否定语境误触发与切换标签后不能循环关闭；增加共享17例和跨标签用例，先确认失败，再修正。复审无新的重要问题。
- 最终 Android 定向142项0失败：Catalog 9、Intent 1（共享17例）、QueryMatching 3、PanelState 11、QueryCacheSync 11、Panel 40、InputView 55、Adapter 5、RenderPolicy 3、Renderer 4。
- 服务端全套49文件494项通过（`npm test -- --maxWorkers=1`）；`npm run build`通过。环境有已存在的Fontconfig缓存版本警告，不作为动画视觉认可。
- Android `:app:assembleOfflineDebug`通过，产物 `android/YuyanIme/app/build/unix/outputs/apk/offline/debug/yuyanIme_2026091516_debug.apk`。Gradle首次组合命令将 `--tests` 放在 assemble 后导致命令解析失败，调整到 test 任务参数后正常完成；不是源码编译失败。Native库存在不能strip提示，保留原样打包。
- 测试临时 init 脚本只设置1536m堆、单测试进程；项目配置不变。最初两轮临时替换SDK路径已恢复，最终直接使用本机env.sh与原Windows local.properties。未改候选字号、Emoji实现或用户其他内容库修改。
- 四张新样片机器检查和SHA核对通过；位置 `artifacts/expression-character-trials/ai-synthesis-blank-01/preview.html`，来源/提示词/已知边界见同目录 production-notes.md。不是正式新图库已经覆盖完整情绪。
- 未安装/发布APK、未部署API、未向真实聊天发送，未提交或推送Git。等待用户动态样片反馈后再扩充并安排接入。

### 收尾环境读取异常

最终测试/构建成功及142项XML计数、APK路径/大小读取后，Android目录 `android/YuyanIme` 突然返回 `Input/output error`（再次用ls/stat确认）。因此收尾全仓库 `git diff --check` 与忽略索引检查无法可靠完成；不能将读取异常期间列出的文件当作真实删除或忽略异常，不执行清索引、恢复文件或挂载修复。先前成功构建/测试日志保留在 /tmp/shurufa-blank-final-android.log，当前服务端和四张GIF仍可读。需恢复该目录访问后复核Android工作区与APK实体；未声称已安装或发布。

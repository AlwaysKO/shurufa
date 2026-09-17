# web03 关键词 GIF 制作与交付计划

**目标：** 优先补齐真实后台空组「好久不见、欢迎、我来了」，每组四风格，争取12张合格成品；失败不凑数。

**授权与边界：** 依据当前用户请求与 CREATION-POLICY.md 2026-09-16 后续修订，来源及技术检查通过后默认入库，不冒称用户逐张观看。不改旧图、删除记录、无字合成库及其他会话改动；不推送或打包 APK。

**方案：** 在原有已登录 Windows Chrome Dev 中逐张独立对话生成4×3母图，下载原图并解码、记录提示词/时间/来源/SHA后删除对应对话并复查。复用 framePoseGrid 和 renderReferenceCharacterGif，最小扩充固定清单；不改门禁，不生成 HTML。

## 执行顺序
1. 实际后台接口盘点：三个用户当前均315组、230空组、27组1～3张；图片张数不等于风格数。完整快照仅本地 server/.runtime/keyword-gif-inventory-2026-09-17.json。
2. 既有导入器 dry-run：181项与当前批准清单SHA完全一致，184项已有、15项排除，扫描范围内无遗漏待补录。未知结构不据此宣称已扫描。
3. 每张先归档与清理，后检查头顶/手脚/连续性/真实姿势；问题母图保留并标失败，不能靠裁切掩盖。
4. 在 server/src/expression/referenceCharacterRenderer.test.ts 先增加 web03 固定身份、中文字幕、来源门禁与20帧4000ms测试，运行看其因未知ID失败；再仅扩充 referenceCharacterRenderer.ts 清单使测试通过。
5. 新增 server/scripts/render-web03-framed.ts，限定本批，通过已核实 SHA/清理证据及统一缩放、显式脚底基线生成 output/gifs、逐帧和报告。逐帧仅本地。
6. 运行定向测试、构建和实际GIF审计；执行 npm run expression:import-keywords，对比新增SHA确认导入识别。实际后台与推荐接口验证可见、删除入口、URL可下载解码及SHA一致。删除防复活使用隔离测试用户/测试夹具，不能删除用户现有图。
7. 保存真实数量、对话清理与未完成事项。仅最终GIF和必要代码/文本可入Git，不使用git add -f。

## 初始浏览器诊断
WSL localhost:9222 拒绝连接；Windows Chrome Dev 的/json端点404，但 DevToolsActivePort 所指 WebSocket 可连接并执行CDP。没有重启浏览器或新建登录配置。工作区「有成员达到使用上限」横幅存在，但第一张实际生成成功，不能据横幅断言当前账号被限额。

## 执行完成
2026-09-17：本批三组各4张、共12张实际入库并通过后台/推荐/下载/SHA/删除防复活验收。13个任务生成对话全部清理，1份技术失败稿保留并重新生成，不计入成品。详见 `artifacts/expression-character-trials/web-original-03-animated/verification.json` 与 `ui-verification.json`。没有推送或发布APK；整库尚余227空组。

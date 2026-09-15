# 第三批情绪与互动动态图实现计划

> 使用 superpowers:subagent-driven-development，子代理 TDD 实现→规格审查→质量审查；主代理负责原创素材。在当前 main 工作，不创建 worktree，保留用户及并行任务所有已有修改。

**目标：** 用户要求继续制作更多关键词，积累后统一加入 APK。本轮采用下一组高频情绪词：开心、难过、生气、震惊、抱抱，各 8 张，共 40 张。延续每词 4 bundled＋4 remote。本轮不接正式 catalog、不部署接口、不构建/安装 APK。

**架构：** 延用 daily-01/02 的四姿势真透明 PNG →确定性中文→240px GIF 链。仅增受控 daily-03 词组，避免通用化重构；默认旧批不变。素材保存在 assets/expression/batches/daily-03，评审包保存在 artifacts/expression-batches/daily-03。

## 任务 1：最小批次入口

文件 server/src/expression/expressionBatch.ts、expressionBatch.test.ts。
1. 先写 daily-03 验证/路径隔离测试，执行 `npx vitest run src/expression/expressionBatch.test.ts --maxWorkers=1` 观察 RED。
2. 增加 daily-03 五词白名单，保持固定 40、每词4+4、未知批次拒绝和默认 daily-01 兼容。不改其他渲染质量门禁。
3. GREEN 后独立规格审查→质量审查。精确路径提交代码，不纳入其他任务文件。

## 任务 2：原创制作

每词八种表现：饭团核心角色、动物、生活人物、抽象图形、毛绒/黏土、手绘、虚构真人、拟人物件。角色姿势体现本词情绪机制而非只换文字。伤心表现为安慰性情绪；生气只做无害气鼓鼓，不涉及攻击。

使用内置 imagegen，真实透明2x2四姿势，无文字/品牌/水印/知名角色/名人。原图保留提示词、生成路径、SHA；中文使用确定性渲染器。16帧、1600ms、240x240、无限循环、低于250KB。

每词渲染 partial 后审查四关键帧和原件；分隔不居中仅用透明边界 poseRects 无损裁切，真正重叠/畸形需返工原件。所有40齐且开发审查通过后才完整发布评审目录。

## 任务 3：验证交付

执行完整 `npx tsx scripts/render-expression-batch.ts --batch=daily-03`，report 40/40、humanReview=pending；独立解码尺寸/帧数/每帧时长累加/循环/大小/SHA/首帧缩略图，提供 inventory、preview、联系表和审查记录。

执行 npm test、必要时保持原超时的串行复跑；npm run build、npm run expression:prototype，清楚区分并行其他任务失败和本次。交付最终规格→质量审查。保留原始日志；仅提交本批文件。后续批次继续增词，统一接入 APK 的时点另行协调，不自行一次性生成余下数百张。

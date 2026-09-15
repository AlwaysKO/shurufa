# semantic-01 原创素材生产实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 生产“懂了”一组四张原创动态表情，作为同义表达共享动作的首个新增批次。

**架构：** 复用现有四姿势母版、确定性中文字幕、GIF 审计和联系表生成链。仅增加受控批次 `semantic-01`：关键词“懂了”，4 bundled + 0 remote；bundled 是后续分发计划，本轮不自动接入 APK。参考只学习情绪和动作机制，不复制搜狗图片、人物或构图。

**技术栈：** TypeScript、Vitest、Sharp、既有 GIF 渲染器、原创图片生成。

---

### 任务 1：批次入口（子代理）
- 修改：`server/src/expression/expressionBatch.test.ts`
- 修改：`server/src/expression/expressionBatch.ts`
1. 先测“懂了”4 bundled 通过；错误数量、remote、错词被拒绝；旧批次数量不变；新批次部分渲染隔离。
2. 运行 `cd server && npx vitest run src/expression/expressionBatch.test.ts`，确认新增功能缺失导致失败。
3. 白名单新增 `semantic-01`，关键词仅“懂了”，remote 预期为 0；不改变质量门禁。
4. 重跑同一测试并执行 `npm run build`，预期通过。

### 任务 2：原创素材与验收（主代理）
- 创建：`assets/expression/batches/semantic-01/manifest.json`、`masters/`、`poses/`
- 生成：`artifacts/expression-batches/semantic-01/`
1. 四种风格各生成四个真实关键姿势；人物使用虚构中国人；无文字母版保留。
2. 填写来源 `ai-original`、生成提示、动作脚本、四姿势路径和 4 bundled 分配。
3. 运行 `cd server && npx tsx scripts/render-expression-batch.ts --batch=semantic-01`。
4. 验证 total=4/pass=4/fail=0、240×240、10–20 帧、循环、低于 250KB；人工检查 GIF 和联系表。
5. 完成规格审查、代码质量审查、文件归属检查后提交；不修改已有正式 catalog 或自动打包 APK。

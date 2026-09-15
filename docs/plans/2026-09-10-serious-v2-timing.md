# 你认真的节奏修正版实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 生成独立的 3 秒评审样片，改善四姿势快速倒放的跳帧感。

**架构：** 专用脚本读取九姿势 3×3 母版，按比例 floor 分格，顺序播放 16 帧。复用固定字体与现有 GIF 审计，不修改旧渲染器、正式 catalog、Android 或归档索引。

**技术栈：** TypeScript、sharp、Vitest。

### 任务 1：时间轴、分格与编码
- 创建 `server/src/expression/seriousV2Renderer.ts` 和 `.test.ts`。
- 先写失败测试：16 帧、3000ms、姿势顺序不倒退、初始400ms、质疑姿势700ms；1254以外非3整数倍尺寸完整覆盖；真实 GIF 解码与静态母版拒绝。
- 运行 `cd server && npx vitest run src/expression/seriousV2Renderer.test.ts`，确认 RED 后最小实现再验证 GREEN。
- 240×240，9个独立姿势，重复帧仅作停顿，无淡化；维持 250KB、动画差异和闭环审计。

### 任务 2：独立样片脚本
- 创建 `server/scripts/render-serious-v2.ts`。
- 固定读取 `assets/expression/batches/scene-rich-03-serious-v2/master.png`，输出独立目录的 GIF、首帧 WebP、报告及新旧并列预览。
- 审计通过后才写成品；仅评审，不覆盖旧版。
- 运行 `npx tsx scripts/render-serious-v2.ts`；查看实际 GIF 后人审，不以机器审计代替节奏判断。

### 任务 3：验证与交接
- `cd server && npm test && npm run build`；`git diff --check`。
- 由主代理审查提交，只暂存本任务文件，不触碰已有 Android/client 工作区。

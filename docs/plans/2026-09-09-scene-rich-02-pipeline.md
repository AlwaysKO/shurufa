# scene-rich-02 评审生成链实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 复用完整场景 GIF 生成器，为“你懂的、细说、什么事、原来如此”生成独立评审批次，不改正式 catalog 或 APK。

**架构：** 在已有入口添加 `--batch` 明确白名单，默认保留 scene-rich-01；校验器按批次绑定词表和 ID 前缀，输出仅由受控批次派生。避免复制生成脚本，也不开放任意目录或批次。

**技术栈：** TypeScript、Vitest、Sharp、既有四姿势审计与确定性字幕。

## 步骤
1. 在 `server/src/expression/sceneRichRenderer.test.ts` 添加 02 正例、跨批次词和 ID 拒绝、未知批次及 CLI 参数负例，执行定向测试并确认新功能缺失导致失败。
2. 最小扩展 `sceneRichRenderer.ts` 批次定义及 CLI 解析；修改 `server/scripts/render-scene-rich-01.ts` 使用白名单派生源/输出路径，验证清单批次一致。保留所有 16 帧、四姿势、240px、250KB、缩略图和 review-only 门禁。
3. 新批次通过合成四姿势母版测试；已有 01 真实母版测试不删除。实际 02 母版由主代理提供后运行生成链。
4. 执行服务端测试与构建，规格审查、质量审查和素材视觉验收交由主代理组织。保持 client 用户改动不动，不提交。

# 已确认参考形象动态试稿 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 把用户已确认的熊猫头“真的假的”、蘑菇头“让我想想”静态试稿制作成两张约4秒的连续表演GIF，保持已认可形象。

**架构：** 产物隔离在artifacts/expression-character-trials/reference-01-animated，不改正式素材和旧图。新增小型固定双图试稿renderer/CLI；复用现有4秒时序、4×3裁切和GIF审计。参考图不是ai-original，不伪造来源或授权，sourceType=user-provided-reference、trial-only；不进入APK/API发布白名单。

**技术栈：** 内置imagegen、TypeScript/sharp、Vitest。

1. imagegen分别以两张已确认PNG为参考制作12姿势母版，保留源图/提示词/SHA；视图检查脸和手势连续性，必要时定向修图。
2. TDD：新测试先对旧12renderer运行新双图配置，观察因不支持参考试稿失败；新增referenceCharacterRenderer.ts/.test.ts与render-reference-characters.ts。审计参数仅真正需要id/frameCount/durationMs，将审计函数类型收窄以避免试稿伪装ai-original，不改变审计实现或正式来源白名单。
3. 240×240、20帧、4000ms、原12姿势顺序播放；底部白色条黑字匹配已认可静态试稿，不使用单图平移。首帧WebP fallback。
4. 运行实际渲染和质量门禁、抽帧检查、生成动态preview.html；全量服务端测试和构建、diff检查及独立审查。保留trial-only/动态人审pending，不重打APK。

## 实施结果

- 已生成两份 12 姿势母版及动态 GIF；蘑菇头经两次图像工具定向修复循环接缝，原失败母版保留在 rejected 中。
- 针对图像工具 1447×1087 单像素尺寸误差新增 RED→GREEN 测试，仅允许最近整数 4×3 网格宽高各不超过 2px 的归一化；未修改 GIF 审计算法或阈值。
- 最终真实产物审计 2/2 通过，均为 240×240、20 帧、4000ms；熊猫头 105027 字节，蘑菇头 117399 字节。已抽查第 0/5/10/15/19 帧，字幕与形象保留。
- 定向测试 19/19 通过；服务端 npm run build 通过；git diff --check 通过；独立只读审查无阻塞，母版及成品 SHA 与来源记录/报告一致。
- 动态预览：artifacts/expression-character-trials/reference-01-animated/output/preview.html。仍为动态待用户确认的隔离试稿，未加入 APK/API，未修改旧素材。

- 全量服务端测试：424 通过、2 失败（44 个测试文件中 42 通过）；失败位于并行开发的关系 AI 测试，本轮不修改其代码，不声称全仓测试通过。

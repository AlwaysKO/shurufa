# 两张新稿的构图整理计划

用户于2026-09-16回复“可以”，允许对生成后真实姿势统一缩小、对齐固定脚底并补白。本次执行范围为上一轮无道具雪纳瑞和完整坐姿男性，原图及旧GIF不变，不自动扩展为长期全库修改授权。

1. 新建独立 `server/src/expression/gridFraming.ts`，只接收严格4×3正方格PNG、12个明确脚底参考值、统一缩小比例和目标脚底；输出同尺寸新PNG与变换记录。每格统一缩放，不按逐格包围框单独缩放或水平居中；只允许整格内容留在画布内，不能裁切原图。输入错误拒绝，不能伪造新动作。
2. TDD：先写 `gridFraming.test.ts`，验证统一尺度/基线、不同姿势保留、原Buffer不变、错误比例/参考值/非4×3输入拒绝；跑失败后最小实现。
3. 在独立 `web-original-01-framed` 目录保留指向原图的SHA来源链、12格人工复核脚底与变换记录；用现有固定清单renderer导出，旧render算法及发布门禁不改。
4. 验证实际GIF尺寸/帧数/时间/大小/fallback，查看真实抽帧与源图，核对原图和旧GIF哈希。代码与视觉独立审查；提交候选最终GIF、必要代码和文字，不提交PNG/预览/失败图，不push。

命令：`cd server && npx vitest run src/expression/gridFraming.test.ts src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts && npm run build`。

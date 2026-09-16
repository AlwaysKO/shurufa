# web02：晚上好 / 中午好网页制作新候选

日期：2026-09-16。用户连续制作授权；沿用此前明确同意的新稿统一缩放、脚底基线对齐和补白。并未取得这些成品的静态或动态批准。

## 结果与来源

- 晚上好：暹罗猫、黑白刺猬、黏土河狸、原创中国成年男性，各1张。
- 中午好：写实小狗、黑白麻雀、黏土小鹿、原创中国成年男性，各1张。
- 共8张隔离候选，全部 `trial-only` / `humanAnimationReview: pending` / `publicationAllowed: false`，不接入APK/API，不push。
- Chrome Dev 已登录网页逐图生成。8次首稿+4次重做，共12份原图、12个专用对话；逐份下载、解码、SHA归档后删除对话并检查。完整提示词与来源在 `sources/*/source/`，汇总在 `verification.json`。未操作用户旧聊天。
- 母图与整理后的PNG、WebP、HTML、抽帧拼版只留本地。新克隆无法单独复现，必须另拷归档原图。

## 重做及保留

首稿未删除，保留在对应 `sources/<id>/source`，以下四项为 rejected/source-attempt-only，不计入候选；选用对应 `-v2`。

| 首稿 | 原因 | 第二版调整 |
|---|---|---|
| siamese-evening | 挥爪中途换侧 | 双爪固定，眼神、耳朵和点头表演 |
| man-evening | 抬手中途换侧 | 双手同步举起、回收 |
| puppy-noon | 相邻行耳朵进入前一格 | 强化整格留白及行间隔 |
| sparrow-noon | 翅膀动作换侧且出现额外姿势 | 双翅对称展开与回收 |

没有靠缩放修掉换侧错误；重新生成解决表演问题。透明背景按现有白底合成，不误判成黑色背景。

## 构图和验收

固定脚本 `server/scripts/render-web02-framed.ts`：逐源SHA门禁、显式12基线、每张全部姿势相同缩放（0.74–0.80）、整格不裁剪、白底与统一目标基线0.69。没有单图伪动画，没有改旧渲染器/时间线/审计阈值。

8张均240×240、20帧、4000ms、无限循环，59,201–96,959 bytes，低于250KiB；WebP首帧的alpha及可见RGB逐像素一致。`verification.json`记录原文件SHA与独立Pillow验证；4张已跟踪旧网页候选GIF与HEAD字节一致。未跟踪旧诊断GIF只记录当前SHA，不声称有基准对照。

95/96整理后的格子下26%诊断区完全空白；河狸格7仅极少浅影像素（比例0.0001163），最终字幕区与肢体不重叠。抽帧所见无明显肢体截断、双侧突然交换或字幕盖脚。每张表演和遗留审美风险见 `visual-review.json`。实际循环接缝、表情力度和文字语义仍待用户播放确认，不能把抽帧/机器合格写成动态批准。

独立只读代码审查未发现阻断问题。建议将来强化 provenance 与源SHA/sourceType的一致性断言；本批已独立逐文件核对一致，不为该建议扩大本轮改动。

## 本地预览

`output/preview.html` 展示全部8张真实GIF；`output/audit-montage-0.png` / `-1.png`仅供抽帧诊断。

## 测试记录

本轮固定8项用例按未知ID失败→追加清单→通过的TDD步骤执行。全量 `npm test`：536通过、6跳过、1失败，失败为 `assetGenerator.test.ts` 的184张预制GIF用例超过默认5000ms；单独默认超时复现。未修改该不属于本轮的文件或放宽素材审计。最终定向/构建与超时诊断结果见后续追加记录。

最终复核：`referenceCharacterRenderer.test.ts` + `gridFraming.test.ts` 共32/32通过；`npm run build` 退出0。对上述预制素材超时用例仅用命令行 `--testTimeout=30000` 做诊断，1/1通过（约24.17秒），说明默认5秒不足以覆盖本次运行，未发现断言错误；没有改测试配置，默认全量仍如实记为未全绿，不将诊断替代默认回归结果。

另行复核 prototypeAudit / prototypePublication / prototypeManifest / prototypeRenderer / prototypeReport 共92/92通过；与上面32项合计本轮相关定向124/124通过。提交前 `git diff --cached --check` 无问题，`git ls-files -ci --exclude-standard` 无输出；仅纳入8张GIF及必要源码/文本，PNG、WebP、HTML、Zone.Identifier 未纳入。仓库提交前 loose对象18.04MiB，pack138.57MiB。其他并行工作区的Android/素材目录改动未触碰、未暂存。

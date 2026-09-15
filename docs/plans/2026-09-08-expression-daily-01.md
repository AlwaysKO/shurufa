# 日常原创动态图库第一扩展批次

> 使用 superpowers:subagent-driven-development；代码任务TDD→实现→规格→质量审查。当前main原目录，不创建worktree，不修改已有未提交Android/发送/T9功能。

**用户确认：** 你好、早安、晚安、好的、对不起，每词8张，共40张。每词精选4张内置APK、另4张接口按需下载并缓存。先生成联系表让用户视觉验收，再正式接入，不一次生产400张。

**本次交付边界：** 40张评审素材及manifest中的bundled/remote标记；不在视觉验收前覆盖正式catalog/Android assets、不部署接口。本轮不混入微信发送与性能重构。未来正式链负责每词仅打包4张，远端4张提供URL+SHA并复用本地缓存。

## 任务1：内容清单与原创生成

文件 assets/expression/batches/daily-01/manifest.json、masters/*.png、poses/*/pose-0{1,2,3,4}.png。
每项记录keyword/text/style/direction/distribution/sourceType=ai-original、完整prompt、动作脚本、4个poseFiles、masterFile。每次imagegen生成一张无文字2×2关键姿势表，真实四姿势不是单图缩放；禁止品牌、水印、乱码、现有IP与明星脸。保留生成原件与来源路径记录。

## 任务2：最小批次生成入口（TDD）

新增server/src/expression/expressionBatch.ts及测试、server/scripts/render-expression-batch.ts。复用现有prototypeRenderer/audit；必要时仅提取渲染器使用的keyword宽类型，不放松旧12张prototype清单门禁。验证五词每词8张、4bundled+4remote、唯一ID/路径、合规来源、四姿势与GIF尺寸/帧数/大小。源文件缺失必须明确失败，不能占位假称成品。2×2原件按floor(width/2)、floor(height/2)中线无损裁切；奇数尺寸余下1px归右/下，保留全部像素和alpha，不缩放、补底或重绘。支持仅渲染已指定ID用于逐张视觉检查，完整报告不得把部分批次计40通过。

**视觉审查后补充：** 部分生成原件的四格分隔并非精确中线，可在批次项显式登记人工确认的 `poseRects`（按四姿势顺序的 `left/top/width/height`）。四矩形须整数、正尺寸、界内、互不重叠且完整覆盖原件；支持上下行不同竖分界、左右列不同横分界。只无损裁切，保留全部原始像素与 alpha，不自动猜测、不涂抹散点、不缩放或补底；未配置仍采用上述缺省中线。原透明度、四姿势不同与 GIF 审计门禁保持不变。

输出 artifacts/expression-batches/daily-01/{gifs,thumbnails,contact-sheet*.webp,preview.html,report.json}；240×240 GIF、10–20帧、800–2000ms、循环、<250KB；确定性中文叠字，首帧WebP fallback；每张SHA/大小/帧数/时长。
运行npx vitest run src/expression/expressionBatch.test.ts先RED/GREEN；最终npm test、npm run build、npm run expression:prototype，原12项门禁不变。

## 任务3：审查交付

逐张看4姿势及GIF抽帧，审情绪/构图/肢体/循环/风格差异，审计失败项返工。输出每词8张联系表和可播放HTML，标明4内置/4联网。规格审查后质量审查，最终核对工作区仅本任务变更进入提交。人审未通过不写正式素材链，不宣称Android/接口接入完成。

## 用户视觉验收结论

用户已查看 `artifacts/expression-batches/daily-01/preview.html`，明确确认：“这里的40张可以”。本批五词40张全部通过用户视觉验收，对应素材提交 `e847c8e`。

后续正式接入沿用清单分配：每词4张内置APK，共20张；其余每词4张，共20张通过接口联网获取并缓存本地。此次验收不代表APK/API/缓存链已经接入，也不代表微信动态发送问题已真机验收。生成时的report和preview保留原始“pending”快照，以本节后续用户确认记录为准。

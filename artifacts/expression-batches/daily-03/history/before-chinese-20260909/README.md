# 第三批：情绪互动五词四十张原创动态图

**机器审计 40/40，开发侧逐项视觉双审完成；用户视觉验收待确认。用户要求继续积累素材后统一加入 APK，本批尚未接入正式 catalog、API 或 Android。**

## 查看

- `preview.html`：40 张真实 GIF 动态预览。
- `contact-sheet.webp`：总表；`contact-sheet-1.webp` 至 `contact-sheet-5.webp` 依次为开心、难过、生气、震惊、抱抱。
- `inventory.tsv`：每项尺寸、帧数、总时长、大小、SHA-256、计划分发标记。
- `report.json`：完整机器与逐帧审计，humanReview=pending。

每词 8 张，前 4 bundled（计划内置），后 4 remote（计划接口联网获取并缓存）。这只是素材分发标记，不代表本批已部署。

## 规格及来源

40 张均为 240×240、16 帧、1600ms、无限循环，最大 228396 字节，低于 250KB。20 张计划内置 GIF 共 3611029 字节，联网 GIF 共 3510295 字节；不含缩略图及 APK 压缩开销。另有 40 张首帧 WebP。

每张以 4 个真实不同动作姿势生成动画，中文由确定性渲染器生成，不用单图缩放冒充关键姿势。素材全部 sourceType=ai-original；提示词、动作、生成来源和原件 SHA 见 `../../../assets/expression/batches/daily-03/manifest.json`，同目录保存 40 张 master 和 160 个 RGBA pose。

风格覆盖原创饭团角色、动物、生活人物、抽象图形、毛绒/黏土、手绘、虚构真人及拟人物件。不宣称全面版权清查或用户视觉验收。

## 开发侧审查与返工

逐词按规格审查→质量审查实看四姿势原件及 GIF 第 0/3/5/8 帧，均无剩余阻断。本批未擦除原件 alpha，也未额外修改裁切门禁。

- sad-cloud-drizzle：首版弧线像笑嘴、易误读笑哭；新版去弧线，仅雨云与落雨，复审通过。
- angry-office-pout：首版第 3 姿势三手；图片编辑第二版为 RGB 棋盘伪透明而拒收。最终第三版全新生成，不交叉臂，两手清楚，复审通过。
- angry-clay-boar：首版跺脚姿势多一条腿；新版固定双腿站立并改变双臂、表情，两臂两腿复审通过。

未采用原件及对应记录保存在源目录 `rejected/`，不进入预览或成品清单。`verification/`保存当前生气、震惊、抱抱的审查合图；开心、难过已在中断前完成双审，旧临时合图未保留，不伪造该历史证据。

非阻断美术建议：震惊各风格中有较多“张嘴→捧脸→捂嘴”动作，后续批次应继续丰富动作表达，避免仅材质变化；抽象心形/光圈部分被文字遮住，但外部开合仍可辨。

## 验证

本次重新执行并保存日志：

- `npm test`：27 套、282 项全部通过（默认并行，无放宽超时）。
- `npm run build`：退出 0。
- `npm run expression:prototype`：原样板 total=12/pass=12/fail=0。
- `npx tsx scripts/render-expression-batch.ts --batch=daily-03`：40/40，complete=true，humanReview=pending。
- 独立 Pillow 解码 40 GIF：240px、16 帧、逐帧当下累加时长、loop=0、大小和 SHA 与 report 一致；40 缩略图、160 RGBA pose、每词4+4、master SHA 齐全。

存在 Fontconfig 缓存版本警告；上述命令均退出成功。`verification/`有日志、独立审计脚本和结果。核验脚本会更新 inventory，应在此仓库路径执行；完整渲染会原子替换输出目录，需先保留 README/inventory/verification。

本次未运行正式 expression:generate、正式素材审计脚本、Android 测试/构建/安装或真机验证，因为当前只制作评审素材。既有正式素材与用户其他修改不纳入本次提交。

所有本轮写入以 ko 身份执行，并检查本批源、成品、计划、代码、测试及构建产物的 owner/group；本批路径均为 ko:ko，未递归修改无关路径的归属。

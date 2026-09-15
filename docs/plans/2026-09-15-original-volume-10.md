# 第十批原创 GIF 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 饿了、心累、嘘各4种原创风格，12项隔离试稿并本地提交。
**架构：** 原固定清单/12格渲染/20帧4秒时间线不变；新增本批映射和独立资产目录。机器、静态抽帧、真实动态与用户语义认可分开。
**技术栈：** imagegen、TypeScript、sharp、vitest。

## 设计与取舍
用户已授权自主选题、继续批量生产和本地提交。本批不修旧失败项。相比继续同义大笑/惊讶，选饥饿、情绪疲劳和安静手势；相比大幅全身运动，固定胸像和高位手部降低裁切漂移。
- 饿了：眼神寻找吃的→期待→嘴角短暂舔唇→收舌、渴望眼神与微开嘴，第7/8格保留饥饿期待，不是吐舌卖萌。
- 心累：轻皱眉→眼睑下垂→闭眼轻叹→不悦嘴角下垂，第7/8格保持疲惫；不是睡着或大哭。
- 嘘：同一侧手/爪/鳍自锁骨前抬到嘴前，严肃收唇、第7/8格停留，缓退。动物普通爪而非人手；男性仅一根食指伸直。
风格为写实动物、黑白线条动物、平滑黏土动物、原创中国成年男性。先独立真实动物饿了样片核对行注册及循环再扩展。不将上批行注册修复经验直接当永久规则：本轮尝试在提示词中显式指定每行头顶相对同一方格坐标。

## 任务1：固定清单 TDD
文件 server/src/expression/referenceCharacterRenderer.test.ts、referenceCharacterRenderer.ts、server/scripts/render-reference-characters.ts。
1. 追加固定12项文案、来源、人物元数据和禁发布测试。
2. server运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts -t volume10，预期未知清单失败。
3. 仅追加ORIGINAL_VOLUME10_ITEMS、白名单和CLI volume10分支。
4. 同测试预期通过；不重构其他批次。

## 任务2：生成及修订
创建 artifacts/expression-character-trials/original-volume-10-animated/masters/、source-manifest.json、初检记录。
1. imagegen生成4列3行白底12姿势源图，保留完整prompt/source/SHA。
2. 先一张用原审计和真实GIF第10帧检查，之后补其余11。
3. 检查源PNG/alpha/方格，逐项审计，失败完整留证。若有明确裁切/姿势/循环错误，用imagegen有限修订，原稿rejected，不改门禁。
4. 标准CLI npx tsx scripts/render-reference-characters.ts volume10 生成。无法通过则如实隔离，不凑数。

## 任务3：验证与交付
1. npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts && npm run build。
2. 从实际GIF重算240×240、20帧、4000ms、loop0、<250KB、SHA、首帧WebP像素一致；核对所有来源及此前129张GIF哈希。
3. 最终真实GIF帧0/5/10/13/19抽帧，检查手部/情绪/循环风险，独立只读审查。
4. 写preview.html、production-notes.md、visual-review.json、verification.json。双pending，publicationAllowed=false，无实际播放不得说动态通过。
5. git diff --check，显式路径add与commit --only；当前分支，不worktree、不push、不发布，保留其他改动。

## 执行结果
12初稿、8修订；最终机器12/12，10待审候选，海豹心累/橘猫嘘2项漂移待返工，尚不满足后两词各4候选目标。未实际动态播放/用户批准。
固定清单TDD RED→GREEN，相关40/40与build通过。12来源链、8修订、13rejected、实际GIF/fallback及此前129GIF哈希核验。
显式三行坐标未稳定解决注册，保留局部失败经验，不升级永久规则。独立代码/资产复核完成，按结果分开展示，所有项禁发布。

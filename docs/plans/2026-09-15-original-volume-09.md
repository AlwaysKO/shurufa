# 第九批原创 GIF 实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 忍住、迷茫、没眼看各4种原创风格，共12张隔离待审GIF，本地提交。
**架构：** 沿用固定清单、原12格渲染器和原机器门禁，不改时间线或旧批次。完整来源链与真实GIF抽帧单独留证；失败不混入通过预览。
**技术栈：** imagegen、TypeScript、sharp、vitest。

## 设计与取舍
用户已授权自主选题并继续批量生产。相比再做同义开心/难过，选克制、迷失、遮眼三种不同表演；相比大幅全身动作，采用固定胸像、眉眼与高位手部，降低字幕遮挡和漂移。
- 忍住：闭嘴压笑→嘴角欲扬→抿紧嘴、鼓一点脸颊、眯眼压住笑意，第7/8格保持峰值，9起回正。不是单纯生气。
- 迷茫：左右找线索→内眉上扬、眼神失焦、嘴微张，第7/8格保持不知所措；不加问号代替表演。
- 没眼看：察觉→皱眉→固定同一侧手/爪抬至双眼→盖眼略露不悦嘴形，第7/8格保持，9起退回胸前；每格手/爪始终可见，不能换边或穿脸。
四风格：真实动物、黑白简笔动物、黏土动物、原创中国成年男性。源图4列3行、白底、12独立姿势、末格回初态。保留第8格峰值是本批姿势设计，不是已证实永久规则。

## 任务1：固定清单（TDD）
文件：server/src/expression/referenceCharacterRenderer.test.ts、referenceCharacterRenderer.ts、server/scripts/render-reference-characters.ts。
1. 追加volume09固定12项/文案/来源/男性/禁止发布测试。
2. 在server运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'volume09'，预期未知候选失败。
3. 仅追加ORIGINAL_VOLUME09_ITEMS、验证白名单及CLI volume09分支。
4. 同命令预期通过；不重构其他分支。

## 任务2：源图与试验
创建 artifacts/expression-character-trials/original-volume-09-animated/masters/、source-manifest.json。
1. 先真实动物忍住样片，记录完整prompt、生成源路径、SHA及PNG形状。
2. 用原渲染器审计并检查真实GIF抽帧，情绪峰值在第10输出帧。
3. 扩展其余11项；保存初检、失败证据。必要时imagegen定点修订，保留rejected原稿与哈希。不降低门禁。

## 任务3：验收、交付
1. npx tsx scripts/render-reference-characters.ts volume09；预期12/12，失败如实隔离。
2. npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts && npm run build。
3. 重算实际GIF240×240/20帧/4000ms/loop0/<250KB、SHA、首帧WebP像素一致。核对来源SHA及旧GIF哈希。
4. 从最终GIF解码0/5/10/13/19帧；记录手部、语义、循环风险。无播放能力不得声称实际动态验收。
5. 编写preview.html、production-notes.md、visual-review.json、verification.json；两项review pending，publicationAllowed=false。
6. 请求独立只读审查；git diff --check，显式路径git add及git commit --only。保留其他工作区变更，不push。

## 执行结果
- TDD固定清单RED→GREEN，相关39/39及build通过。
- 12初稿+8次修订；最终11GIF通过，鸭v4循环失败，三次修订后停止并隔离，迷茫3张未满足4张目标。
- 新增本批render-audited-subset.mts和verify-subset.mts：严格鸭单个loopClosure例外，11+1原子分流；集成RED→GREEN及坏源/缺源不覆盖旧输出。
- 来源链/实际GIF/首帧回退图/此前118张哈希复验；最终真实GIF抽帧、独立代码及资产审查完成。实际动态播放与用户语义批准未完成，禁止发布。
- 原稿、修订和失败证据均保留；无旧图或审计阈值修改。详情见本批production-notes.md。

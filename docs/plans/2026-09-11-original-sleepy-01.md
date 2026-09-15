# 「困了」四风格 GIF 实现计划

**目标：** 用户继续授权下，自选「困了」制作四风格独立试稿，旧图不动、不发布。
**架构：** 固定 sleepy01 清单与CLI隔离目录，复用4秒十二姿势20帧渲染器、字体与原质量审计；静态/动态均pending。
**技术栈：** 内置imagegen、TypeScript/sharp、Vitest。

## 设计

写实奶油猫哈欠、极简猫头鹰眼皮打架、黏土企鹅瞌睡点头、原创中国成年男性强撑后哈欠。与继续做确认手势相比，睡意可通过眉眼嘴形表达，避免换手风险；与大动作打瞌睡相比，小幅头姿利于稳定循环。第8格是清晰困意停顿，9-12格逐步回正。要求白底、完整形体在上78%，人像双手不入镜；原稿发生错误则留rejected，定向修图而非放宽审计。

## 步骤

1. imagegen生成四份4×3母版，保存原始源图路径、提示词、SHA与人物China/male/adult设定。
2. 在server/src/expression/referenceCharacterRenderer.test.ts加入四项渲染/错误来源/发布/字幕/男性元数据测试，先运行看到清单缺失失败。
3. server/src/expression/referenceCharacterRenderer.ts新增ORIGINAL_SLEEPY_ITEMS固定清单，server/scripts/render-reference-characters.ts新增sleepy01固定分支、隔离目录、双pending及待审标题。不改旧分支与审计实现。
4. npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/sceneRich12Renderer.test.ts；npm run build。
5. npx tsx scripts/render-reference-characters.ts sleepy01；真实成品原门禁240×240、20帧、4000ms、循环、低于250KB和首帧WebP。
6. 检查母版/解码抽帧/来源哈希/fallback一致性，独立只读审查，git diff --check；交preview.html给用户实际播放验收，不把机器通过当视觉批准。

依据：项目AGENTS.md、CREATION-POLICY.md全部修订、上一批original-received-01-animated/production-notes.md。Vault最小检索无新增GIF创作经验。上一批换翼与黑底问题促成本批明确固定肢体和白底，不提升为新的永久规则。

## 执行结果

四款真实GIF原门禁通过；21/21相关测试、构建和diff检查通过，独立审查无阻塞。企鹅字幕遮脚经母版留白修正，失败证据保留；代价为角色略小。最终SHA与fallback核对通过。详见对应批次production-notes.md，形象及动态仍待用户审核，未发布。

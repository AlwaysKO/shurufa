# 「收到」四风格 GIF 实现计划

**目标：** 沿用户自行选词与继续授权，制作「收到」四风格独立动态试稿，旧图不动。
**架构：** 新增固定 received01 批次，复用现有渲染器、字体、4秒时间线及原审计。形象/动态均 pending，trial-only，不发布 APK/API。
**技术栈：** 内置 imagegen、TypeScript/sharp、Vitest。

## 设计

写实小狗竖耳点头、极简小鸟敬礼、黏土水獭举爪、原创中国成年男性点头。比延续四款侧眼更能区分词义；比复杂道具互动减少连续性风险。生成不同角色和表演，不从上一批更换字幕。第8格作为明确回应停顿，9-12格回正；上下肢和镜头固定，手势只用同侧。上一批小鸟脚靠近字幕，本批提示词要求全部形体在上78%、下22%留白，此为本批制作调整，不新增永久规则。

## 实施与验证

1. 保存四份12姿势4×3母版、完整提示词与源图SHA；检查肢体、首尾和情绪变化。
2. 在 server/src/expression/referenceCharacterRenderer.test.ts 新增四项来源/字幕/发布与男性元数据测试；先运行确认固定清单缺失失败。
3. 在 server/src/expression/referenceCharacterRenderer.ts 最小添加 ORIGINAL_RECEIVED_ITEMS；server/scripts/render-reference-characters.ts 加 received01 固定路由与双pending、待审标题。
4. 运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/sceneRich12Renderer.test.ts 及 npm run build。
5. 实际 npx tsx scripts/render-reference-characters.ts received01；240×240、20帧、4000ms、低于250KB、循环、首帧WebP，原审计不放宽。
6. 解码抽帧、SHA核对、独立只读审查、diff检查；交动态preview.html。无动态浏览器工具，不冒充已完成实际播放视觉验收。

依据：CREATION-POLICY.md全部修订、项目AGENTS.md、original-look-01-animated/production-notes.md。Vault定向检索仅命中斗图交互与记忆边界，无新增GIF创作经验，不引入无关交互改动。

## 实施结果

四张实际GIF原门禁通过，20/20相关测试及服务端构建通过，独立审查无阻塞。小鸟同侧翅膀与白底经内置图像工具定向修正，失败证据保留。源图/GIF/rejected哈希及fallback核对通过。生产记录见对应artifacts批次production-notes.md；实际动态播放仍待用户检查，未发布。

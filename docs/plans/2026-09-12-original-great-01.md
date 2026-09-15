# 「太好了」四风格 GIF 实现计划

**目标：** 用户继续授权，自选开心词「太好了」，四风格隔离试稿，旧图不动、不发布。
**架构：** 新增great01固定清单及CLI，复用既有4秒渲染器/字幕/原审计，静态动态均pending。
**技术栈：** 内置imagegen、TypeScript/sharp、Vitest。

设计：写实金色小狗开心咧嘴、极简螃蟹举双钳、黏土猪拍前蹄、原创中国成年男性由惊喜到笑开。比延续无语侧眼更能覆盖积极情绪；比全员跳跃，固定身体、独立眉眼嘴形和对称肢体更利于连续性。第8格庆祝停顿、9-12格回正。要求整个形体及举钳落在上75%，下25%白底留字幕。

1. 生成四份4×3十二姿势母版，保存提示词/来源路径/SHA与人物China/male/adult创作设定。
2. referenceCharacterRenderer.test.ts添加固定四项渲染与来源/字幕/发布/人物测试，先见失败；referenceCharacterRenderer.ts最小新增ORIGINAL_GREAT_ITEMS；render-reference-characters.ts新增great01隔离目录和待审文案，不改旧分支。
3. 相关两文件Vitest测试与npm run build；实际CLI渲染240×240、20帧、4000ms、循环、低于250KB、首帧WebP，不放宽原审计。
4. 检查母版和解码抽帧，重点钳数/蹄分离/字幕留白；有错留rejected后定向修图。核对SHA/fallback，独立只读审查与diff检查。
5. 交preview.html供用户实际播放验收；当前无动态浏览器工具，不以抽帧或机器通过代替用户视觉批准。

依据：项目AGENTS.md、CREATION-POLICY.md全部修订、前批production-notes。Vault定向检索无新增GIF要求。

## 执行结果

四款真实GIF原审计通过，相关测试23/23与构建通过，独立审查无阻塞。螃蟹第8格眼柄问题经图像工具修正，原证据留rejected；最终SHA和fallback复核通过。形象/动态仍待用户审核，未发布，详见对应批次production-notes.md。

# 「无语」四风格 GIF 实现计划

**目标：** 用户授权继续，自选「无语」四风格隔离试稿，旧图不动、不发布。
**架构：** 最小新增speechless01固定清单/CLI，复用现有渲染器与原审计；静态动态均pending。
**技术栈：** 内置imagegen、TypeScript/sharp、Vitest。

设计：写实哈士奇抬眼、极简原创青蛙抿嘴、黏土水豚叹气、原创中国成年男性欲言又止。相较再次侧眼或困倦模板，本批先有尝试回应再克制的嘴形变化；相较摊手互动，固定肢体减少换边风险。第8格关键无奈表情，9-12格回正；上75%容纳形体、下25%留白，避免前批字幕遮脚。原创蛙不复刻网络梗形象。

1. 生成四张4×3十二姿势母版，保存提示词、源图路径、SHA和人物China/male/adult元数据。
2. referenceCharacterRenderer.test.ts新增四项测试先失败；referenceCharacterRenderer.ts最小新增ORIGINAL_SPEECHLESS_ITEMS；render-reference-characters.ts新增speechless01固定入口、隔离目录和双pending标题，不重构旧分支。
3. 相关两文件Vitest测试及npm run build；实际CLI渲染240×240、20帧、4000ms、循环、低于250KB、首帧WebP，原审计不放宽。
4. 母版/解码抽帧检查、来源哈希和fallback像素比对；独立只读审查及diff检查。错误母版/产物保留rejected。
5. 交付动态preview.html，当前无动态浏览器工具，不宣称完成实际播放视觉验收；继续不等于批准旧图或发布。

依据：项目AGENTS.md、CREATION-POLICY.md全部修订、original-sleepy-01-animated/production-notes.md。Vault定向检索无新增GIF规则。

## 执行结果

四款真实GIF原审计4/4通过，相关测试22/22、构建与diff检查通过，独立审查无阻塞。SHA及fallback核对一致，前批旧GIF未变。已看母版与解码抽帧；实际动态播放仍待用户验收。详见对应批次production-notes.md；未发布。

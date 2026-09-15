# 「委屈」四风格 GIF 实现计划

**目标：** 用户继续授权下，自选四风格委屈表演；独立试稿，不动旧图、不发布。
**架构：** hurt01固定清单/CLI最小扩展，复用4秒十二姿势20帧渲染、字幕及原审计，静态动态均pending。
**技术栈：** 内置imagegen、TypeScript/sharp、Vitest。

设计：写实小猎犬欲言又止、极简兔垂耳抿嘴、黏土刺猬轻抱自己、原创中国成年男性强忍情绪。相比全员流泪，采用眉内端、嘴形、视线与头姿演出克制的委屈，避免泪滴首尾不连续；相比大幅蜷缩，固定躯干更利于循环。第8格委屈停顿，9-12格回正；上75%形体，下25%白底字幕安全区。

1. 生成四份4×3母版，保留提示词、来源路径、SHA及人物China/male/adult设定。
2. referenceCharacterRenderer.test.ts先添加四项来源/字幕/发布/男性元数据测试并见失败；referenceCharacterRenderer.ts新增ORIGINAL_HURT_ITEMS；render-reference-characters.ts新增hurt01固定分支/隔离目录/双pending标题，不改旧分支和审计阈值。
3. 相关两文件Vitest测试、npm run build；实际CLI原门禁240×240、20帧、4000ms、循环、低于250KB、首帧WebP。
4. 母版和解码抽帧查双耳、双手、脸型及字幕留白；错误稿保留rejected。核对SHA/fallback像素，独立审查、diff检查。
5. 交preview.html，当前无动态浏览器工具，不把静态抽帧或机器通过当用户动态批准。

依据：项目AGENTS.md、CREATION-POLICY.md全部修订、前批production-notes，Vault定向检索无新增GIF规则。

## 本轮结果

代码最小扩展与 RED/GREEN 验证完成，相关24/24、build通过；最终机器4/4、SHA/fallback通过。但兔子实际抽帧有遮脚和上下漂移，visualAssessment=needs-rework，批次未完成视觉目标。预览正常展示其余三张、兔子放失败折叠区。未批准静态/动态、未发布、未修改前五批20张GIF。详情见本批production-notes.md。

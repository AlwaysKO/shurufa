# 原创动态第三批制作与验收记录
日期：2026-09-13。范围：尴尬 / 期待 / 疑惑，每词4风格，共12张；本地提交，不推送、不发布、不改旧图。

## 沿用依据
- 项目 AGENTS.md、assets/expression/CREATION-POLICY.md：原创溯源、真实多姿势、逐项机器及人工门禁；不把继续生产当作通过验收。
- Vault 跨项目工作记忆入口及匹配项目原文：记忆只作上下文，不更改权威政策。
- original-volume-02-animated/production-notes.md：独立批次、保存失败版本及修订链、从最终 GIF 解码检查、禁止用机器通过替代视觉通过。
- 实现计划：docs/plans/2026-09-13-original-volume-03.md。

## 产物与来源
12份 imagegen 原创4×3母版，每份12姿势，经既有渲染器生成20帧、4000ms、240×240、无限循环 GIF，附无损首帧 WebP。
本批字节范围70872～135653，均低于250KB。中国成年男性均为虚构原创，不仿真人或IP。
manifest.json 保存完整初始提示、生成源路径、原始和当前 SHA、修订提示及失败母版。所有角色/人工动画验收 pending，publicationAllowed=false。

## 修订与视觉分流
- man-awkward 初稿 loopClosure=0.20147569444444444，超过上限0.13350694444444444。保留 rejected/man-awkward-v1-loop.png；使用 imagegen 修订下排与首尾对齐，第二版通过原门禁。没有修改审计阈值。
- turtle-awkward：机器通过，但最终解码抽帧显示恢复段整体上移。manifest.visualTriage=needs-rework，主预览独立折叠隔离，不计入11张待复核候选。暂未完成修复。
- seal-eager：恢复段轻微位置变化；corgi-eager：首尾耳顶高度变化。实际循环播放需重点复核。
- 小鹿联系图曾被独立审查怀疑贴顶；抽查当前GIF未证实截耳，不将疑点说成已确认问题。证据图从最终GIF生成，生成后没有再改GIF。
- 本轮只能解码抽帧，未完成实际动态播放验收，不能宣称11张视觉通过或词义全部达标。

## 验证证据
- TDD：新增固定volume03测试先触发未知候选拒绝（RED），增加最小固定清单/CLI分支后GREEN。
- 最终 npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/sceneRich12Renderer.test.ts：2文件、27/27通过。
- npm run build：退出0。测试有现有 Fontconfig 缓存版本警告，未修改系统缓存。
- 最终 npx tsx scripts/render-reference-characters.ts volume03：12/12机器通过；report.json保存参数与审计。
- 12项初始生成源、最终母版、修订源、失败源及GIF SHA匹配。
- 12个首帧WebP与GIF第0帧解码像素完全一致。
- 三张 motion-evidence-1/2/3.png：每组4角色，各取GIF第0/5/10/13/19帧，由最终文件解码。
- 旧8批共48个GIF的SHA与原报告一致。
- 独立只读代码审查未发现阻断；产物审查确认乌龟应隔离，未确认其他新增阻断，并核对源链和fallback。

## 交付状态
根 preview.html 为人工复核入口：11张待动态复核 + 1张待返工。output/preview.html 是渲染器自动机器试稿页面，不含额外视觉分流，以根入口及本记录为准。
无用户人工批准，无APK/API接入，无推送及发布。保留全部试稿不等于批准全部试稿。

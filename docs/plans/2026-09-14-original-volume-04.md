# 十二张原创 GIF 第四批实现计划

> 执行技能：superpowers:executing-plans；当前分支，不创建 worktree。
**目标：** 害羞、放心、无奈各4种风格，共12张独立原创试稿并本地提交，不改旧图，不发布。
**架构：** 只追加固定 ORIGINAL_VOLUME04_ITEMS 和 CLI volume04；独立 original-volume-04-animated。复用渲染、审计及首帧fallback，不重构。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计
沿用用户已授权的自主选题与每批12张。相较单词12外观，3词各4风格扩展语义；不扩大到几十张以免无法逐项核验。
- 害羞：写实暹罗猫、极简小鼠、黏土兔、原创中国成年男性。视线下垂、含蓄微笑、微低头、抬眼恢复；不以尴尬强笑替代。
- 放心：写实金毛、极简水豚、黏土海狮、原创中国成年男性。紧眉松开、缓慢合眼呼气、轻舒嘴角，再睁眼。
- 无奈：写实哈士奇、极简鸭胸像、黏土考拉、原创中国成年男性。眉峰抬起、半垂眼、嘴角微撇、轻歪头后回正。
所有胸像不画手，留头顶与字幕安全区。固定相机、尺度与基线，首尾中性完全对齐，真实12姿势而非平移缩放。细微位移风险仍需最终GIF检查，不承诺生成工具完全遵守。

## 步骤与验收
1. server/src/expression/referenceCharacterRenderer.test.ts 复制volume03测试结构至volume04，固定新12 id/字幕，拒绝来源伪造/发布/错误字幕；运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'twelve volume04'，预期未知候选RED。
2. server/src/expression/referenceCharacterRenderer.ts 加固定清单；server/scripts/render-reference-characters.ts 追加分支、双pending标题与状态；运行两相关测试及npm run build，预期GREEN。
3. 独立 artifacts/expression-character-trials/original-volume-04-animated/masters 保存12 imagegen源PNG，manifest保存完整提示/source/SHA，男性metadata；逐项审计，失败保留rejected再有限修订。
4. npx tsx scripts/render-reference-characters.ts volume04；生成最终解码证据，核对12个GIF参数、源链、fallback像素、旧60GIF哈希，视觉异常隔离。实际动态未播放则明确pending。
5. 独立只读审查，根preview与production-notes及计划结果；验证链接、git diff --check，显式范围本地提交，无push/APK/API接入。

## 执行结果
12份原创母版及GIF/首帧/来源链/预览已生成。考拉两次修订后机器过门禁；小鼠漂移减轻；鸭子两轮修订仍有漂移，隔离待返工，其余11项待动态复核。最终机器12/12、相关28/28及build通过，旧60GIF哈希一致。独立审查完成，放心组语义需复核。实际动态尚未验收，未批准/推送/发布。

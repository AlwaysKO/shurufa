# 第八批原创 GIF 实现计划
> 使用 superpowers:executing-plans 执行，当前分支，不创建worktree。
**目标：** 不服/崩溃/佩服各4风格，共12张独立原创试稿，本地提交、不发布。
**架构：** 追加固定ORIGINAL_VOLUME08_ITEMS及CLI volume08，独立original-volume-08-animated；不改旧渲染器、审计、时间线。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计与取舍
继续3词×4，比只做单词12外观扩大语义；不继续反复改旧失败猪/熊。胸像为主，佩服采用单侧胸前赞许动作；避免所有词同一套笑脸或全套复杂手势。
- 不服：写实柴犬、黑白鸭、黏土小牛、原创中国成年男性；压眉、撇嘴、下巴微抬、侧眼挑战，保持身体不动。
- 崩溃：写实橘猫、黑白企鹅、黏土树懒、原创中国成年男性；眉内角抬高、眼睑挤紧、苦脸张嘴，不能误画成笑；无新增泪水粒子代替表演。
- 佩服：写实浣熊、黑白兔、黏土水獭、原创中国成年男性；目光确认、眉抬起、真诚点头和胸前单侧赞许手爪，人类明确拇指向上，动作侧别不换。
第8格为关键动作，已核对sceneRich12Timeline的pose7合计900ms；本轮提示词要求第7/8格保持峰值，第9格才开始回正。先第一张核对源图及审计再扩展，不宣称提示词必能实现。
白底4×3，首尾主体注册，源方格底部留足白区。12真实过渡编20帧4000ms，至少4真实不同姿势，静态/动态仍pending。

## 实施与验证
1. server/src/expression/referenceCharacterRenderer.test.ts加volume08固定12项/字幕/原创/人物/禁发布断言；npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'twelve volume08'观察未知候选RED。
2. server/src/expression/referenceCharacterRenderer.ts追加固定清单并纳入校验；server/scripts/render-reference-characters.ts追加CLI固定分支、独立目录与双pending。相关测试+build验证GREEN。
3. artifacts/expression-character-trials/original-volume-08-animated/masters保存12PNG，source-manifest.json保留prompt/source/SHA。源图/alpha检查后运行npx tsx scripts/render-reference-characters.ts volume08；有限修订，不放宽审计。
4. 实际GIF格式/帧/时长/尺寸/字节/循环/hash、首帧fallback像素一致，之前106GIF哈希不变；抽取含第8格峰值的实际GIF帧检查，另记视觉/语义状态，不冒充实际播放。
5. 独立只读复核，preview/visual-review/production-notes/verification；git diff --check后仅显式本轮路径本地提交，不push，不带入并行改动。

## 执行结果
- 固定清单/CLI/test已完成，RED后GREEN；最终2相关文件38/38通过，build成功。
- 12源图及2次手部修订完整留档；标准CLI最终12/12机器通过。
- 12实际GIF元数据/字节/hash、12fallback像素、12来源链/2修订与106旧GIF哈希已核对；最终真实GIF抽帧完成。
- 第8格900ms峰值在抽帧中保留；仍未实际播放，形象/动态/语义待审、禁止发布。
- 独立只读代码及资产复核无新增阻断，兔/男手部遮挡改善；根预览与风险/生产/验证记录完成，仅显式路径本地提交。

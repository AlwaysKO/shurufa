# 第七批原创 GIF 实现计划
> 使用 superpowers:executing-plans 逐项执行；当前分支、不建worktree。
**目标：** 加油、拜托、冷静各4风格，共12张独立原创试稿；本地提交，不发布。
**架构：** 追加ORIGINAL_VOLUME07_ITEMS、CLI volume07和固定清单测试；不改渲染和审计。独立original-volume-07-animated保存源与输出。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计
继续3词×4风格，而非扩成单词12外观；不反复编辑上批猪，保留其失败。纯表情容易语义重复，本批增加有限、完整可见的手/爪动作，承担肢体连贯性检查成本。
- 加油：写实金毛、黑白青蛙、黏土熊、原创中国成年男性。眼神坚定、眉微压，一侧握拳/爪轻抬，微笑鼓励，逐渐放下。
- 拜托：写实狸花猫、黑白海豹、黏土仓鼠、原创中国成年男性。内眉抬起、目光恳切、双手/爪合拢胸前、小幅低头后回正。
- 冷静：写实哈士奇、黑白企鹅、黏土水豚、原创中国成年男性。先绷紧、缓慢呼气放松，双掌/爪朝下轻压再回原位，不长闭眼伪装睡觉。
每张12格真正过渡，20帧4秒，关键姿势约900ms；主体/手脚全部在源方格上部安全区，底部留白；首尾位置统一。此定位方案是待验证尝试，不升为长期规则。

## 执行与验证
1. server/src/expression/referenceCharacterRenderer.test.ts增加volume07的12固定id/字幕、来源、人物及禁发布断言。运行npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'twelve volume07'；先未知候选RED。
2. server/src/expression/referenceCharacterRenderer.ts追加清单并纳入校验；server/scripts/render-reference-characters.ts增加固定volume07分支、独立目录、双pending。相关测试及npm run build验证GREEN。
3. artifacts/expression-character-trials/original-volume-07-animated/masters存12源PNG，source-manifest.json存完整prompt/source/SHA。先源图检查，再npx tsx scripts/render-reference-characters.ts volume07；失败保留rejected，有限源修订，不放宽审计。
4. 从真实GIF核验尺寸/帧/时长/字节/循环/SHA、fallback像素；核对之前95张GIF不变。真实GIF解码抽帧，视觉/语义另外分流；不称已播放或已批准。
5. 独立只读审查，根preview.html、visual-review.json、production-notes.md及验证记录；git diff --check，显式本轮路径本地提交，不带入并行编辑。

## 执行结果
- RED未知候选后实现固定清单/CLI/test；最终2相关文件37/37通过，build成功。
- 12源图完成，4张加油各修订两次，保留完整源链；小熊仍loopClosure失败，隔离，无GIF。
- 最终11GIF：加油3/拜托4/冷静4，不宣称12张及全部每词4风格目标完成。
- 11fallback像素、12源链、8修订/rejected与95旧GIF哈希核对，真实GIF抽帧完成。
- 独立只读复核确认男性截拳修复及隔离状态；冷静停顿/语义问题待实际播放，双pending、禁发布。
- 根preview、visual-review、production-notes与verification记录本批结果；显式本轮路径本地提交、不推送。

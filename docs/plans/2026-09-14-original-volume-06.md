# 十二张原创 GIF 第六批实现计划
> 使用 superpowers:executing-plans 逐项执行；当前分支，不创建worktree。
**目标：** 警惕、满足、纠结各4风格共12张独立原创试稿，本地提交、不发布、不改旧图。
**架构：** 追加固定ORIGINAL_VOLUME06_ITEMS与CLI volume06，独立original-volume-06-animated；不改渲染/审计。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计
延续用户自主选题与一次多做授权，3词各4比单词12外观更扩大语义，仍可逐项审核；不扩大到几十张。
- 警惕：写实虎斑猫、黑白猫头鹰、黏土狐獴、原创中国成年男性。目光锁定侧方、眼睑收紧、眉压低、下巴稍收后逐渐回正。
- 满足：写实橘猫、黑白水獭、黏土猪、原创中国成年男性。嘴角缓慢抬起、眼角轻弯、微抬下巴、温和闭嘴笑；不靠长时间闭眼冒充。
- 纠结：写实边牧、黑白兔、黏土熊猫、原创中国成年男性。眉峰内收、视线左右犹豫、嘴角左右偏、轻歪头后回正；无道具不画手。
胸像、纯白、等方格定位及留白，12真实姿势/20帧4000ms，首尾中性对齐。上批定位提示不稳定，本轮仍只是待验证假设，不升永久规则。

## 步骤与验证
1. server/src/expression/referenceCharacterRenderer.test.ts加固定12id/字幕、来源/禁发布/人物元数据测试；npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'twelve volume06'先未知候选RED。
2. server/src/expression/referenceCharacterRenderer.ts加清单；server/scripts/render-reference-characters.ts加volume06独立目录与双pending。两相关测试及npm run build预期GREEN。
3. 独立artifacts/expression-character-trials/original-volume-06-animated/masters保存12原图，manifest记录prompt/source/SHA。npx tsx scripts/render-reference-characters.ts volume06；失败保存rejected有限修订，不放宽门槛。
4. 最终GIF参数、哈希、首帧WebP像素、旧84GIF哈希，最终解码抽帧。视觉/语义单独分流，不把机器通过当动态批准。
5. 独立只读审查、根preview/visual-review/production-notes，链接与git diff --check；显式路径本地提交，保留其他会话改动。

## 执行结果
- 已增加固定清单/CLI/test，RED后GREEN；最终两相关文件36/36通过，build成功。
- 12母图完成，猪两次修订仍loopClosure失败，隔离保存3版及来源，未完成12张/满足4风格目标。
- 原审计隔离输出11GIF，报告12/11/1；11首帧像素及12源链、84旧GIF哈希验证，抽帧证据完成。
- 独立只读审查提出异常分类风险已收紧，复核无新增阻断；实际动态播放未做，双pending、禁发布。
- 生产记录/来源/预览/视觉分流和验证结果位于本批根。仅本地显式路径提交，不推送。

# 十二张原创 GIF 第五批实现计划

> 使用 superpowers:executing-plans 逐项执行，当前分支，不创建worktree。
**目标：** 傲娇、心虚、失落各4风格，共12张独立原创试稿；只提交本轮，不发布、不改旧图。
**架构：** 固定 ORIGINAL_VOLUME05_ITEMS + CLI volume05 分支，独立 original-volume-05-animated；不改渲染/审计。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计
按用户自主选题与批量授权，选择3词各4而非单词12外观，扩大语义覆盖；不一次做几十张以免无法逐项检查。
- 傲娇：写实白猫、黑白小狐狸、黏土仓鼠、原创中国成年男性。轻挑眉、侧眼、微抬下巴、抿嘴忍笑，不等同发怒。
- 心虚：写实腊肠犬、黑白小狗、黏土浣熊、原创中国成年男性。左右偷瞄、眉不对称、嘴紧闭、微缩下巴，不靠外加汗滴。
- 失落：写实拉布拉多、黑白企鹅、黏土熊、原创中国成年男性。目光落下、内眉抬起、嘴角垂下、轻低头后渐回正，不笑不流夸张泪。
本轮新图尝试更明确等方格定位和留白；这是生成提示假设，不升为永久规则，也不保证工具遵守。保存全部修订源。

## 实施与验证
1. server/src/expression/referenceCharacterRenderer.test.ts 追加volume05固定12项测试：正确字幕、ai-original/trial-only/禁发布、中国成年男性metadata、拒绝来源伪造与错误字幕。运行 npx vitest run src/expression/referenceCharacterRenderer.test.ts -t 'twelve volume05'，先观察未知清单RED。
2. server/src/expression/referenceCharacterRenderer.ts 增加固定清单；server/scripts/render-reference-characters.ts 加volume05、独立目录及双pending标题。运行两相关测试与npm run build，预期GREEN。
3. artifacts/expression-character-trials/original-volume-05-animated/masters 保存12母版，manifest记录prompt/source/SHA；npx tsx scripts/render-reference-characters.ts volume05。失败保留rejected有限修订，不降低门槛。
4. 验证最终12GIF格式/尺寸/20帧/4000ms/循环/体积/哈希，首帧WebP像素，旧72GIF不变；解码抽帧逐项查看，视觉异常单独分流；未实际动态播放则不批准。
5. 独立只读审查、根preview/visual-review/production-notes，链接与git diff --check；显式路径本地提交，保留其他会话改动。

## 执行结果
12份母版、GIF/首帧、来源链、抽帧及预览已生成。男性傲娇2次、小熊1次修订后过机器门禁；腊肠犬对齐修订退化，保存v2后恢复本轮v1隔离needs-rework，不计视觉合格。11项仍待动态复核。最终机器12/12、相关29/29、build通过，旧72GIF哈希一致。独立代码与产物审查完成，实际动态/语义未批准，不推送或发布。

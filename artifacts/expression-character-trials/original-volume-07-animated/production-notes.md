# 第七批原创 GIF 生产记录
日期：2026-09-14。范围：用户授权继续、自主选题、批量生产及本地提交；不push。

## 依据与设计
已读取项目AGENTS.md、完整assets/expression/CREATION-POLICY.md、第六批production-notes.md及Vault跨项目入口匹配的《GIF创作与三层验收》，目标子目录未发现额外AGENTS.md。
沿用原创可溯源、中国成年男性、每词4风格、真实关键姿势、4秒/20帧/240×240/低于250KB、首帧WebP、独立试稿和三层验收；不改旧图，不把“继续”当批准。
本批原计划加油/拜托/冷静各4，共12。实际输出11机器通过GIF：加油3、拜托4、冷静4；小熊失败隔离，因此12张及加油4风格目标未完成。
在表情之外加入握拳、合掌、双手向下轻压；代价是手爪裁切、侧别与动作停顿需要单独检查。未修改原渲染、审计、时间线或发布门禁。

## 生成、检查与修订
12初始母图及完整提示词/生成来源/SHA见source-manifest.json。首组PNG带alpha，既有渲染器按原流程铺白；后两组明确要求不透明纯白。源图检查与最终GIF检查分开。
- 初始12项机器审计均通过；初检JSON按三个词分组保存。
- 加油4张的手爪进入字幕安全区，男性最严重；金毛和青蛙姿势3到4抬手换边。这证明机器审计不能识别所有视觉错误。
- v2通过imagegen缩小主体、留白并固定观众左侧抬拳。4张v1保留rejected，不采用代码修图或改渲染器躲避问题。
- v2金毛loopClosure失败：actual0.27765625 > expected0.2505208333333333；小熊失败：0.3273611111111111 > 0.2930989583333333。青蛙/男性机器通过。
- 比较源格定位，发现缩小后中/下排主体向上漂移。金毛/青蛙/小熊v3按固定362px行高尝试下移中下排，保持表情与抬拳侧别。金毛恢复机器通过；青蛙仍通过。不能宣称定位完全精确。
- 小熊v3仍失败：actual0.28706597222222224 > expected0.2794270833333333。停止继续试错，保留v1/v2于rejected、v3于masters，无失败GIF输出。
- 男性v2静止拳仍在源图底边截断；v3仅将该拳抬到胸前。最终实际GIF抽帧及独立审查确认未再截拳，仍不构成动态批准。
- 四张加油各有初稿及两次修订，完整8条修订源链保留；其他8张未返工。
- 标准CLI volume07按原门禁失败中止；不伪造12/12。render-audited-subset.mts仅接受固定小熊单个loopClosure原审计失败，任何其他异常终止；仍使用原审计，原子输出11张及total12/pass11/fail1报告。

## 验证证据
- 固定volume07的12项测试：先观察未知候选RED，再追加清单/CLI分支。
- 最终相关测试：npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts，2文件37/37通过。
- npm run build退出0。不是全项目测试；Fontconfig缓存版本警告仍存在，未宣称零警告。
- 最终隔离重跑：cd server && npx tsx ../artifacts/expression-character-trials/original-volume-07-animated/render-audited-subset.mts，11通过/1失败。
- 11GIF均240×240、20帧、4000ms、loop0，最大138635字节；通过项issues为空。11个GIF哈希、首帧fallback解码像素一致。
- 12完整来源链、8修订及rejected SHA核对；此前95GIF与各自报告SHA一致，未改变。
- output/motion-evidence-1/2/3.png来自最终GIF实际解码帧0/5/10/13/19；加油3行，其余4行。没有用母图冒充成品证据。
- 只读独立代码及资产复核完成，无新增阻断；男性截拳问题在最终抽帧中关闭。助手审查不是用户审美认可。

## 视觉及语义状态
没有实际动态播放；staticCharacterReview与humanAnimationReview均pending，publicationAllowed=false。
金毛首尾体态/位置仍小幅变化，青蛙的鼓励语义与回环需复核。拜托组手爪较完整，仍可能与祈祷/感谢混淆。
冷静组下压动作在中段出现，但关键停顿附近已回收，可能削弱安抚语义；不能宣称已实现理想表演，需要去字幕动态审核。此问题如后续获准改进，应先单图验证动作峰值与实际停顿姿势对齐，不自动扩大为全局规则。
根preview.html为含完整分流的入口；visual-review.json记录风险。output/preview.html只导航回根，熊单独隔离，不计入可审GIF。

## 交付与复现边界
只本地提交本批资产、计划和清单/CLI/test三个代码文件；不带入Android/T9/chat等并行改动，不接入APK/API、不发布、不push。
隔离脚本重跑会原子替换output；必须之后重新生成抽帧证据和导航，不能保留旧GIF对应证据。熊修好后才重新走全批CLI与三层验收，不改报告数字冒充合格。

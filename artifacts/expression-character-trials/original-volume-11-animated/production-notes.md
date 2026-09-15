# 第十一批原创 GIF 生产记录
日期：2026-09-15。沿用户“继续”、自主选题、多生产、本地提交授权；不push、不发布。

## 来源、边界和执行中修订
开始读取项目根AGENTS.md、完整assets/expression/CREATION-POLICY.md、Vault跨项目工作记忆入口、《GIF创作与三层验收》和第十批production-notes，最小检索相关知识/决策/反馈。目标路径未发现子AGENTS.md。
沿原创可追溯、多风格、真实关键姿势、中国成年男性、旧图不改、三层验收。新增知识强调聊天意图优先，不能只换画风堆数量。
本轮期间其他工作更新了AGENTS.md/政策/.gitignore，并将仓库历史重置为707fddf。本轮只重新读取并遵守当前规则，不执行历史清理、推送或用户并行改动管理。
新的Git边界：只有最终导出GIF及必要代码、文字、清单进入Git；母图、姿势帧、抽帧、WebP试稿、HTML预览和rejected原稿仅本地保存。源图追溯规则仍有效，完整来源提示词和SHA入清单，原图不删除。新克隆无法直接重渲染，需要另拷本地masters归档。

## 聊天意图设计
不听：梗犬侧眼后闭眼转脸；黑白鼠双爪捂耳；黏土水獭先侧眼再捂耳；男性闭眼捂耳。
懂了：暹罗猫疑问眼神到小幅点头；黑白猫头鹰眉眼和嘴形；黏土浣熊歪头点头；男性抬眉竖食指。
紧张：垂耳犬左右观察和忧虑眼神；企鹅双翼贴胸；仓鼠攥爪；男性握手咬唇。
不是通过字幕换词冒充新动画。实际语义仍须用户审核；可能分别误读成傲娇/卖萌/有主意/害怕/拜托等，visual-review.json逐项列明。
本批沿12源姿势→20帧4000ms，源pose7在输出帧9–12合计900ms，回正始于第9源格；未改现有渲染器、阈值或时间线。

## 生成、失败与修订
先梗犬样片原审计通过，并看真实GIF的0/5/10/13/19帧检查起始/峰值/回正，再扩展余11项。抽帧不是动态播放验收。
12张初稿，source-manifest.json记录完整提示词、生成源路径、原始和当前SHA及版本链。
初稿机器10通过2失败：mouse-refuse loopClosure 0.1713194444 > 0.1224392361；raccoon-understand 0.2664930556 > 0.1650173611。没有把失败稿作为输出GIF。
独立复核还发现两男性手部和仓鼠爪部被字幕裁切，暹罗猫首尾上移约14px。共8次imagegen修订：
- mouse v2调整行注册仍失败：0.1325173611 > 0.1216579861。v3进一步末行位置后通过；实际掌部完整，底部是腕臂/躯干截断。
- raccoon v2调整行注册后机器通过；小幅点头近似侧歪，仍待语义复核。
- man-refuse/man-nervous/hamster-nervous各v2抬高手掌/爪；真实GIF抽帧及独立复核确认掌指完整露出。
- siamese v2调整行注册引入邻格身体跨行残片，真实GIF顶部出现细棕线；v3所有格统一缩小解决残片，但首尾耳顶54→37仍约17px上移。有限修订后停止，needs-rework，不计候选。
退役源图和阶段抽帧均保留本地rejected；12文件SHA核验。全部8次修改由imagegen完成，不用Python/像素工具修源。
部分图像违背不透明白底提示，返回透明通道；源格式如实记录source-format-checks.json。原renderer已有白底flatten，不改变源图像素，不以黑底透明显示直接判图。
提示词控制行位置仍不可靠；机器循环门禁也未捕获明显漂移。不把本轮局部改善提升为长期已验证方法。

## 验证
固定清单TDD先运行volume11新测试，因未知候选失败；最小追加12项/CLI后，首次代码复制误带重复resolveReferenceMaster声明导致解析错误，查明区间复制原因，仅移除重复声明后GREEN。没有重构其他批次。
最终命令（server目录）：
- npx tsx scripts/render-reference-characters.ts volume11：12/12机器通过，原子输出。
- npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts：2文件41/41。
- npm run build：tsc与scripts类型检查，退出0。
Fontconfig缓存版本警告仍存在，不是全仓库验证，也不是零警告。
从最终真实GIF逐一重算：240×240/pageHeight240、20帧、4000ms、loop0、<250KB，最大132352字节；SHA/字节/delay与报告一致。12张首帧WebP解码RGBA与GIF第0帧完全相同。
12来源链、8修订、12rejected哈希核验；此前六单词批次和volume01–10共141张GIF与各自报告SHA一致。
本轮临时验算脚本 /tmp/v11-verify-assets.mts；实际GIF证据为output/motion-evidence-1/2/3.png（每组4行、每行帧0/5/10/13/19）。PNG证据仅本地，不能当用户播放或审美认可。
独立审查两轮未发现三个新增清单/CLI/test文件的阻断代码问题，确认修订后手爪完整及暹罗猫需隔离。

## 最终状态与交付
output/report.json真实保留12技术通过、0失败；12个导出GIF包含1个仅供问题追溯的待返工GIF。visual-review.json为11项pending、siamese-understand needs-rework。
根preview.html仅展示11项候选，暹罗猫在独立待返工区给问题链接，不作为正常候选；output/preview.html导航根页。标准CLI重建后要重新生成分流预览，不能拿通用模板冒充审核通过。
不听/紧张各4项候选；懂了只有3项候选，不宣称已满足每词4张可用素材。全部staticCharacterReview/humanAnimationReview仍pending、publicationAllowed=false。未实际动态播放，未接入APK/API。
依最新政策提交12个最终导出GIF与必要文字/清单、计划及3个代码文件；本地预览/源图/废稿仍保留但不入Git。只做本地提交，不push，不修改并行Android/T9/client/chat/API改动。

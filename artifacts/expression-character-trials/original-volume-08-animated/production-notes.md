# 第八批原创 GIF 生产记录
日期：2026-09-14。用户授权继续、自主选题、批量生产并本地提交；不push、不发布。

## 来源与范围
已读取项目AGENTS.md、完整assets/expression/CREATION-POLICY.md、第七批production-notes.md、Vault跨项目入口与匹配的《GIF创作与三层验收》；目标子目录没有额外AGENTS.md。
沿用原创溯源、中国成年男性、每词4风格、真实关键姿势、独立试稿、旧图不改、三层验收及禁发布。用户“继续”不等于视觉批准。
本批不服/崩溃/佩服各4，共12张机器通过GIF；不是12张获用户验收素材。未动第六批猪、第七批熊等旧失败项。

## 设计及本轮尝试
上批冷静在关键停顿附近已收回动作。本轮先核对sceneRich12Timeline：pose7（第8格）在输出帧9至12合计900ms。
不改时间线，而在源姿势提示中要求第7/8格保留情绪峰值，第9格再回正。先生成柴犬独立样片，原审计通过，检查实际GIF第10帧确实侧眼抬下巴；这是单图尝试证据，不是全局视觉认可。
不服通过压眉/侧眼/微抬下巴；崩溃通过内眉抬起、痛苦闭眼和下弯张嘴；佩服通过辨认、点头及胸前赞许手爪。没有用单图缩放或粒子代替动作。
原计划动物仅普通抬爪，但实际采用拟人拇指姿势；企鹅黑白主体带黄色喙，兔子有动作线。以上偏差如实保留，不能宣称严格符合全部风格提示。

## 生产与修订
- 12张初稿PNG均机器审计通过，initial-report.json留存初始全报告；首两组还保留分组初检JSON。
- source-manifest.json记录全部完整提示词、外部生成源、当前母版SHA。source-format-checks.json记录最终PNG尺寸及alpha；单像素网格舍入沿用原渲染器，不改几何规则。
- 实际GIF抽帧发现兔子/男性佩服手部被y200开始的字幕区遮住，源手部太靠下。机器通过不代表该错误可接受。
- 两项各一次imagegen修订，仅抬高手/爪与腕部，不改变人物比例、头部注册、字幕区或时间线。v1 PNG及初稿抽帧存rejected，manifest保留原始SHA、修订prompt/source/SHA。
- 重跑标准CLI volume08，12/12通过，不需隔离脚本，不放宽任何审计。最终抽帧确认兔/男手部完整可见，独立审查未发现必须新增隔离的问题。

## 验证
- TDD先观察volume08固定清单测试因未知候选RED，后追加清单和CLI分支。
- 最终：npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts，2文件38/38通过。
- npm run build退出0；不是全项目测试。Fontconfig缓存版本警告仍存在。
- npx tsx scripts/render-reference-characters.ts volume08：12/12机器通过，issues全空。
- 从最终GIF重新读取format、width/pageHeight、pages、逐帧delay总和、loop及实际文件字节数：GIF/240×240/20帧/4000ms/loop0，最大136573字节，低于250KB；与报告SHA匹配。
- 12个首帧WebP与GIF第0帧解码像素完全一致；12条原始来源链、2条修订与3份rejected文件SHA匹配；此前106张GIF与各自报告SHA一致。
- 三份最终motion-evidence均由真实GIF帧0/5/10/13/19解码，每组4行；第10帧为第8格峰值。输出重建后重新生成，未用母图替代。
- 独立只读代码/资产复核无新增阻断；尚未实际动态播放、未取得用户视觉/语义认可。

## 三层验收与风险
机器12通过；staticCharacterReview/humanAnimationReview仍pending，publicationAllowed=false。
崩溃峰值苦脸较明确；不服可能被读成生气/嫌弃，佩服可能只是点赞。兔子动作线闪现、浣熊/水獭首尾小幅位置或轮廓变化需实际播放。
保持第8格峰值的尝试在抽帧中比上批“停顿时已收势”更符合本轮姿势设计；尚不能证明实际流畅度或情绪识别改善，不写成永久规则。
根preview.html包含完整风险；visual-review.json单独记录，机器报告不改成视觉批准。

## 交付与复现
只提交本批资产、计划、固定清单/CLI/test三个代码文件。保留Android/T9/chat等并行工作，不入APK/API，不push、不发布。
CLI重跑会原子替换output；之后需重建最终抽帧和预览导航，不能保留与旧输出绑定的证据。本批旧失败初稿保留，不静默删除。

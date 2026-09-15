# 第十批原创 GIF 生产记录
日期：2026-09-15。用户继续授权自主选题、批量制作、本地提交；不push、不发布。

## 来源和边界
读取跨项目记忆入口、项目AGENTS.md、完整CREATION-POLICY.md、第九批生产记录及Vault《GIF创作与三层验收》，按GIF关键词检索知识卡/决策/反馈，无新增适用制作规则。目标目录未发现子AGENTS.md。
保留原创可追溯、中国成年男性、多风格、真实姿势、旧图不改、三层验收。未动旧失败项、Android/T9/chat等并行工作。不把继续当逐张批准。

## 本批设计与局部试验
饿了通过寻找、短暂舔唇、期待眼神；心累通过下垂眼睑、闭眼叹气、嘴角下垂；嘘通过同侧高位手爪到嘴前和严肃侧眼。
固定12格组成20帧4000ms，pose7在输出帧9至12合计900ms，9源格后回正。时间线、渲染器、审计阈值没有改动。
上批方格行位置逐渐偏移，本批先在提示词显式指定三行绝对头顶/身体位置。先柯基样片原审计通过并看实际GIF第10帧，再扩展11项。这仅是本轮尝试。
事实表明显式坐标仍未稳定解决注册：独立复核继续发现触顶/漂移，不能声称试验已成功，更不能写成永久规则。只记录在本批，不更新Vault权威。

## 生成与修订
12初稿、8次imagegen修订；source-manifest.json保留完整提示词、外部生成源、原始/当前/修订SHA以及修订参考原稿位置。所有原生成路径保留。
初稿12/12机器通过（initial-checks.json），却并不视觉合格。
- 嘘橘猫v2、狐狸v2、男性v2：抬高起始手爪。狐狸去掉毛绒改平滑黏土；峰值爪仍拟人手指状，未完全符合普通无手指爪提示，作为造型偏差待审。
- 第一次独立视觉复核：海豹恢复段上移约22px，柯基/橘猫后段耳尖触有效顶边y2。保留pre-margin-report.json与rejected抽帧，再修源图，不改机器门禁。
- 海豹v2：调整行注册，漂移减小但仍约12px，needs-rework。
- 柯基v2：统一缩小留白后触顶改善，但loopClosure失败（margin-revision-checks.json）。标准CLI中止，原output原子保护未被覆盖。随后v3调整行位置，机器通过；最终首尾顶界约27→19，约8px剩余差异，待动态复核。
- 橘猫v3：统一缩小留白解决触顶，但回正仍上移。v4进一步行注册调整，机器通过且不再触顶，但首尾顶界约42→24、约18px漂移。累计三次修订后本轮停止，needs-rework。
- 所有退役源图/抽帧留rejected，13文件SHA均验证，不静默覆盖证据。
首次标准成功输出后，间隔修订曾失败；不能说“所有尝试都通过”。最终标准CLI已重跑12/12通过。

## 最终分流（机器与视觉分开）
技术报告output/report.json保持12通过/0失败，准确记录真实技术审计，不伪造视觉通过。
visual-review.json：10项pending；seal-weary、gingercat-shush为needs-rework。根预览只播放10项候选，2项在独立待返工区提供问题GIF链接，不能计为可用素材。output/preview.html只导航根预览，避免通用页混展示。
12个GIF均保留供技术复现，其中2个仅为待返工证据。不是12张视觉合格；心累/嘘各只有3张候选，尚未达到每词4张。
独立复核确认柯基截耳解决、海豹及橘猫仍需返工。未实际动态播放、未取得用户语义或审美认可。staticCharacterReview/humanAnimationReview仍pending，publicationAllowed=false。

## 验证
- 固定清单TDD：先未知volume10候选测试RED，追加12项/白名单/CLI后GREEN，不重构其他分支。
- 相关测试：server下 npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts：2文件40/40；npm run build退出0。不是全仓库测试，Fontconfig缓存版本警告仍在。
- 最终 npx tsx scripts/render-reference-characters.ts volume10：12/12。未新增隔离渲染脚本或放宽门禁。
- 实际GIF重算：240×240、pageHeight240、20帧、逐帧delay总和4000ms、loop0，最大133047字节，均<250KB；文件SHA/字节数/延迟与报告一致。
- 12首帧WebP与实际GIF第0帧解码像素完全一致；12来源链、8修订、13rejected哈希核对；此前129张GIF与原报告SHA一致。
- source-format-checks.json记录最终PNG尺寸/alpha。部分初稿违背不透明白底提示返回带alpha图；原渲染器已有白底flatten，未修改源像素或伪称所有源图不透明。最终真实GIF白底检查，不以工具的黑底透明显示直接判图。
- 三份最终motion-evidence由最终真实GIF解码0/5/10/13/19帧，包含两项返工证据。margin-recheck.json记录辅助顶界测量；不是完整注册误差或新审计阈值。
- 机器通过不代替耳尖/主体漂移检查；饿了可能读成期待，心累可能读成困倦，嘘动物爪可能读成思考。全部待实际播放与用户语义验收。

## 交付与复现
本地仅提交本批资产/计划和固定清单、CLI、test三文件，不push、不入APK/API、不替换旧图。
标准CLI原子重建output后，须重新生成真实GIF抽帧及output预览导航，保留visual-review的分流状态，不把通用模板当成批准结果。

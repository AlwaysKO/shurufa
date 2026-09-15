# 第九批原创 GIF 生产记录
日期：2026-09-15。用户授权继续、自主选题、批量生产及本地提交；不push、不发布。

## 来源与范围
读取跨项目工作记忆入口、项目AGENTS.md、完整assets/expression/CREATION-POLICY.md、第八批production-notes.md、已认可旧样片记录、Vault《GIF创作与三层验收》。核实旧样片路径存在，未重新宣称其视觉验收。检索知识卡/决策/反馈的GIF关键词，无新增适用于本批的已确认制作规则；目标目录无子AGENTS.md。
遵守原创溯源、中国成年男性、多风格、真实姿势、旧图不改、独立试稿。未改任何旧失败项、APK/API或其他Android/T9/chat工作。

## 设计
忍住用侧眼察觉、欲笑、抿嘴鼓腮、闭眼克制；迷茫用寻找、内眉抬起、视线失焦、微张嘴；没眼看用同侧手/爪从胸前抬到遮眼、保留不悦嘴形、退回。
四风格为真实动物、黑白简笔动物、黏土动物、原创中国成年男性。提示词固定相机/体型/方格，末格回初态，中文由原渲染器确定性绘制。
先比格犬样片，机器通过，真实GIF第10帧检查峰值；可能更像满足笑，因此不认为语义已通过。第7/8源格保留峰值只是本轮设计尝试，未验证为永久规则。
保留原sceneRich12Timeline：20帧4000ms，源pose7在输出帧9至12停900ms；不以改时间线或降低审计掩盖源图问题。

## 生成与错误
12项初稿、8次imagegen修订。12完整提示词、外部源路径、原始/当前SHA、各修订prompt/source/SHA/参考旧版位置见source-manifest.json；原生成路径未删。
迷茫鸭首次服务端返回output moderation_blocked、无图；同一无害提示重试一次成功，错误码/请求号留在manifest，不绕过审核。
初稿逐项原审计10通过、2失败（仓鼠/水豚loopClosure）；标准CLI首次因仓鼠失败中止且无全批output。initial-checks.json保留全部结果。
调试临时检查器最初写错sharp导入路径，核实require.resolve后修正；元数据检查器一度假定ensureAlpha().stats()含第4通道，改按原PNG元数据/通道读取，重跑通过。没有因此改生产渲染或门禁。

## 修订与停止
- hamster-restrain：v1有毛发且循环失败；v2改为平滑黏土仍循环失败；检查行注册，第二/三行相对方格逐渐上移。v3提示逐行下移，不改姿势内容，循环通过。
- capybara-lost：v1/v2循环失败；v3明确逐行下移后循环通过。不是宣布首尾像素一致。
- man-facepalm：v1真实GIF起始手部被字幕截断；v2抬到锁骨下，最终手指和掌部可见，腕部仍近下沿。原稿和初次真实GIF抽帧保留。
- duck-lost：v1机器通过但鸭头/眼睛/羽簇有熟悉IP联想，未直接采用；v2整体改为扁椭圆头、圆眼、小喙、无衣物羽簇，源图透明（原renderer白底flatten），循环失败；v3修注册时模型误把连续迷茫动作变成12种情绪，有爱心/哭泣/X眼，机器却通过。源图和真实GIF抽帧证明机器通过不代表语义正确；v4重新画连续迷茫表演，但loopClosure 0.05579861111111111 > 0.03732638888888889，再次被拦。
- 鸭累计三次修订后本轮停止，不再盲目追加，不用v1/v3凑数。当前v4留masters，v1/v2/v3留rejected，标needs-rework，不输出成品GIF。迷茫仅3张通过，尚不满足每词4张交付目标。
上述行注册改进仅为本批证据，不写入Vault成为永久规则。旧批次不返工。

## 分流与验证
- 最終标准CLI volume09退出1，原因鸭loopClosure；不能写成标准全批通过。
- render-audited-subset.mts复用前批隔离流程，每项仍调用原renderer。只允许duck-lost且唯一loopClosure失败，其余任何异常/数量变化立即中止。原子输出11通过+1失败报告，失败项没有GIF/缩略图/poses。
- verify-subset.mts先因新脚本缺失观察RED，脚本加入后在临时副本上实际验证GREEN：成功11+1、另项损坏必须中止、鸭文件缺失必须中止，旧output报告字节不变。没有修改真实母版做破坏性测试。
- 相关测试：npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts：2文件39/39。固定12项测试先未知清单RED，追加白名单后GREEN。npm run build退出0。
- Fontconfig缓存版本警告仍存在；不是全仓库测试，也未声称无警告。
- 真实GIF重算：11个格式gif，240×240、pageHeight240、20帧、4000ms、loop0，最大128580字节；逐帧延迟与报告一致、SHA一致。11个WebP与实际GIF第0帧像素完全一致。
- 12条来源链、8修订、11份rejected哈希核实；此前118张GIF与各自报告SHA一致，未更动。
- output/motion-evidence-{1,2,3}.png均从最终真实GIF解码帧0/5/10/13/19。第二组第二行空白代表鸭无成品，不能拿旧版帧替代。
- 独立代码审查无阻断；独立资产复核同意鸭继续隔离，未发现其余11项必须新增隔离的问题。具体风险见visual-review.json。不是用户视觉批准。

## 三层验收与交付
机器11通过/1失败；staticCharacterReview与humanAnimationReview均pending，publicationAllowed=false。只有抽帧检查，未实际动态播放，未获用户语义/审美认可。
忍住可能读成满足、憋气或忍痛；迷茫可能读成惊讶/担心；没眼看可能读成头疼/懊恼。青蛙首尾微上移、企鹅鳍峰值变宽弯折、手腕近字幕等需播放复核。
根preview.html展示11 GIF与鸭待返工说明，output/preview.html仅导航到完整状态。源码/报告不伪装视觉通过。
本地只提交本批资产/计划、固定清单/CLI/test三个代码文件；无push，无APK/API发布。重跑隔离脚本会原子重建output，此后须重新生成最终抽帧和预览导航，不能沿用旧证据。

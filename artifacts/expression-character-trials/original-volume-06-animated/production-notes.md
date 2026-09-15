# 第六批原创 GIF 生产记录
日期：2026-09-14。用户授权继续、自主选题、批量生产并本地提交；不推送。

## 依据与边界
已读取项目 AGENTS.md、assets/expression/CREATION-POLICY.md、第五批 production-notes.md，以及 Vault 跨项目入口匹配的《GIF创作与三层验收》。
沿用原创溯源、中国成年男性、每词4风格、真实表情变化、独立目录、旧图不改、三层验收和禁发布。用户“继续”不等于画面确认。
计划是警惕/满足/纠结各4，共12；实际完成11张机器通过GIF，满足的小猪失败，因此本批12张及满足4风格目标未全部完成。

## 生成及返工
- 12张原创12格母图，完整提示词及生成来源见 source-manifest.json；保留外部生成原件、当前母版和SHA256。
- pig-content v1 loopClosure：actual 0.17487847222222222 > expected 0.15151909722222223。
- 假设是首尾姿势定位/几何不一致；v2仅尝试注册及首尾对齐，仍失败：0.16637152777777778 > 0.14917534722222223。
- v3更明确固定轮廓位置与表情序列，仍失败：0.18796875 > 0.15824652777777778。停止重复修订，标 needs-rework。v1/v2位于 rejected，v3保留 masters，不输出失败GIF。
- 不修改、绕过或放宽审计。标准全批CLI因猪失败而中止，没有产出伪造的12/12报告。
- 使用本批隔离复现脚本 render-audited-subset.mts：仍调用原渲染与审计，仅接纳已知猪的单个loopClosure失败，其他异常全部终止；仅原子输出11张通过项，并报告total12/pass11/fail1。
- 初次临时脚本.ts在非ESM目录的顶层await转换失败，改为.mts后可运行，没有改项目模块配置。
- 脚本初审发现仅按失败id分流可能吞掉源文件异常，已收紧；验证已知loop接受、缺失/损坏/其他审计错误均拒绝。

## 验证
- 新增volume06固定12项及来源/人物/禁发布测试；先观察未知候选RED，再实现清单和CLI分支。
- 最终运行：npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts，2文件36/36通过；npm run build退出0。不是全项目测试。
- 最终运行：npx tsx ../artifacts/expression-character-trials/original-volume-06-animated/render-audited-subset.mts，11通过/1失败。
- 11张均240×240、20帧、4000ms、无限循环，最大150502字节，低于250KB；机器原审计通过项issues为空。
- 11张WebP fallback与GIF第0帧解码像素完全一致。12条完整源链、11GIF SHA及之前84GIF SHA核对通过。
- 保留Fontconfig缓存版本警告，不能宣称零警告。
- 输出重建后重新生成三份motion-evidence，来自实际GIF帧0/5/10/13/19；满足3行，其他4行，非从母图冒充。

## 画面与语义审核
只做实际GIF解码抽帧检查，没有实际动态播放；staticCharacterReview/humanAnimationReview均pending，publicationAllowed=false。
抽帧未见明显裁切或大幅漂移，但虎斑猫转头回正和小幅定位变化要动态复核。狐獴/熊猫实际偏毛绒3D，不算已达成黏土外观。
猫头鹰可能读成生气，橘猫满足较弱，边牧/熊猫可能读成疑惑；满足组也可能只是普通开心，需要去字幕动态语义审核。
独立只读审查确认来源链/分流/脚本修复，无新增阻断；不代表12张目标完成、形象或动态获批。
权威预览入口为根preview.html，逐项风险见visual-review.json；output/preview.html只链接回该入口。

## 交付范围
只提交本批资产、计划及固定清单/CLI/测试三个代码文件。不改旧GIF，不带入Android/T9/chat等并行工作，不push，不入APK/接口，不发布。
复现隔离输出会替换output，之后必须重建抽帧证据及预览导航；失败项修好后应重新使用标准全批CLI并重新验收，不直接篡改分流报告。

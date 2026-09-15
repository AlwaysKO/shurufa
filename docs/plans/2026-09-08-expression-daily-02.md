# 第二批五词原创动态图实现计划

> 使用 superpowers:subagent-driven-development，代码TDD、规格审查、质量审查。当前main，保留用户86270b9，不创建worktree。

**用户确认：** 哈哈、加油、收到、可以、再见，每词8张，共40张。4 bundled＋4 remote；先动态预览看样，用户确认后再正式接入。“哈哈哈”在正式接入阶段作为哈哈搜索别名评估，本次图内固定文字为哈哈。

**架构：** 复用已有四姿势PNG→确定性中文→240px GIF的批次生成链。只增加受控daily-02选择，不复制渲染器、不改变旧daily-01与原12项门禁。

## 任务1：批次入口（子代理TDD）

文件server/src/expression/expressionBatch.ts、expressionBatch.test.ts、server/scripts/render-expression-batch.ts及必要最小CLI测试。
1. RED：daily-02五词40项能独立验证和渲染，仍要求每词4+4；混词/未知批次拒绝；默认daily-01仍兼容。
2. GREEN：受控批次选择，仅daily-01/daily-02，CLI --batch=daily-02，输出目录隔离，partial统计实际数量；缺原件失败不发布。
3. 保持poseRects无损完整裁切、真实透明PNG、4不同姿势、原GIF全部质量门禁。
4. 规格→质量审查，npm test/build/prototype验证旧12；不运行正式expression:generate或Android构建。

## 任务2：原创制作（主代理）

assets/expression/batches/daily-02/{manifest.json,masters,poses}；用内置imagegen，每项一张真正透明2x2四姿势表，无文字、品牌、水印、明星脸、现有IP。manifest记录完整prompt、动作、原件SHA、sourceType=ai-original、分发标记。

每词前4：原创饭团核心角色、原创动物、原创生活人物、动态抽象图形；后4：毛绒/黏土、手绘简笔、虚构真人、原创网络梗物件。所有四姿势真实局部动作，不用单图缩放替代。

加宽中央透明间距；必要时人工poseRects完整保留原像素，实体重叠或伪透明必须返工，不抹碎片冒充合格。

## 任务3：审查交付

每词partial生成并视觉审查全部4姿势与GIF关键帧；全部齐后 --batch=daily-02 完整生成report total40/pass40/fail0，humanReview仍pending。240x240、16帧、1600ms、loop0、<250KB，40首帧WebP；联系表、动态preview、inventory SHA/大小/帧数/时长及审查记录。

用户看样前不覆盖正式catalog/APK，不部署API，不操作手机。逐文件提交本批原件/姿势/成果/测试，机器通过与用户验收明确区分。

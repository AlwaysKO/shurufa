# 十二张多词原创 GIF 实现计划

**目标：** 根据用户要求一次多生产，新增「震惊」「嫌弃」「得意」各4种风格，共12张隔离试稿，并提交当前及新批次成果到本地Git；不推送、不发布、不批准失败稿。
**架构：** 复用固定清单渲染器，增加单个volume01固定12项批次，使用既有12姿势/20帧/4000ms和原门禁；不改旧图。保留失败和溯源证据，逐项机器/抽帧检查。
**技术栈：** imagegen、TypeScript/sharp、Vitest。

## 设计与取舍

相比同词增加12种皮肤，本次3词各4风格更能扩大语义覆盖；相比一次几十张，12张便于逐项检查。角色采用写实动物、极简动物、黏土动物与原创中国成年男性；人像不复制明星。尽量使用胸像与无道具动作，降低脚/手/耳跨格漂移风险，但仍以实际抽帧为准。源图失败不能通过降低门禁解决。

- 震惊：虎斑猫、极简青蛙、黏土猫头鹰、男性；由注意→眼睑打开→嘴张开→惊讶停顿→恢复。
- 嫌弃：哈士奇、极简鸭、黏土羊驼、男性；嗅/注视→眯眼→微皱鼻→偏头侧眼→恢复。
- 得意：狐狸、极简壁虎、黏土海豹、男性；确认→单侧挑眉→自信微笑→轻抬下巴→恢复。

## 执行步骤

1. 先验证当前相关24项测试/build及差异，选择本对话GIF相关路径提交；保留委屈兔子needs-rework。
2. referenceCharacterRenderer.test.ts添加12项固定字幕、来源、发布禁止、人像元数据用例，先运行观察固定清单拒绝的RED。
3. referenceCharacterRenderer.ts增加ORIGINAL_VOLUME_ITEMS；scripts/render-reference-characters.ts增加volume01固定分支、独立original-volume-01-animated目录与双pending。只作最小扩展，不重构旧分支。
4. imagegen生成12份母版，逐张保存prompt/source/SHA；运行固定CLI，如失败逐项诊断并保留失败证据，不弱化原审计。
5. 运行相关测试/build、输出SHA/首帧像素一致检查，查看实际GIF抽帧。报告机器与视觉分别记录，未通过的隔离。
6. 独立代码/产物审查，写production-notes，git diff --check后提交新批次。交付统一预览和实际提交号，明确人工动态待审。

## 实施结果

已按3词×4风格产出12张，固定volume01批次原审计12/12通过；相关25/25测试与build通过，来源SHA/fallback12/12一致。猫/鸭/青蛙返工及独立审查证据见本批production-notes.md。统一预览在artifacts/expression-character-trials/original-volume-01-animated/preview.html。仍待用户实际动态审核，未发布；此前五批已提交0717ec6，新批次另行提交。无永久固定12张规则写入。

# 网页首批 GIF 闭环计划

> 执行使用 superpowers:executing-plans；当前分支，不建 worktree。

目标：按用户授权修改提示词，提高下午好四种风格的12格母图质量，并复用现有20帧/4000ms渲染和审计，不改变发布门禁。

1. 顺序使用独立网页对话生成考拉、男性、雪纳瑞、黑白海豹；每张保存原始PNG、完整提示词、来源URL、SHA后删除对应新对话并刷新确认。失败暂停。
2. 与首稿比较动作顺序、同侧手爪、底部26%留白、12格布局、首尾注册；不合格保留独立原稿，不冒充完成。
3. 仅扩展 `server/src/expression/referenceCharacterRenderer.ts` 固定四项 `ORIGINAL_WEB01_ITEMS`，保持原校验、时间线与审计不变。先在同目录 test 文件追加四项固定身份/原创来源/不发布/人像中国成年男性测试，运行看到 unknown item 失败后实现，再跑回归。
4. `server/scripts/render-reference-characters.ts` 仅接入 web01 批次，输出 `artifacts/expression-character-trials/web-original-01-animated`；没有源文件的失败图不伪造。
5. 运行渲染、相关 vitest 和 build；独立核对GIF帧数、时长、循环、体积、fallback，抽帧检查不替代用户播放验收。必要代码审查；只提交本任务最终GIF和文本/必要代码，不提交母图或其他并行改动。

验证命令：`cd server && npx vitest run src/expression/referenceCharacterRenderer.test.ts src/expression/prototypeAudit.test.ts`、`npm run build`、`npx tsx scripts/render-reference-characters.ts web01`。

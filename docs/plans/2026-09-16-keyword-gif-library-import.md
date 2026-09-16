# 全量关键词 GIF 入库与后台删除 实现计划

**目标：** 按用户“此前所有的都入库”授权，补录所有可用关键词成品，默认供推荐；后台直接预览和删除，不生成独立HTML交付。
**架构：** 保留现有运行库及原始GIF字节，用受控清单补充此前未发布成品，不重跑APK素材生成或覆盖另一个任务的无字合成库。后台和推荐读取同一合并目录；按用户+SHA保存删除标记，重复补录不复活，版本随可见目录变化。受鉴权的文件路由只允许清单内文件。原图、旧报告不改。
**技术栈：** TypeScript/Express/PostgreSQL/Vue，Vitest/pg-mem/supertest。

1. 盘点 `artifacts/expression-character-trials/*/output/report.json`、`artifacts/expression-batches/*/report.json`、现有184项发布目录；排除rejected/样例/已被修订版替代/无来源许可/无字合成底图；逐项保存补录或排除理由。
2. 新增 `server/src/expression/keywordGifLibrary.ts` 与测试：白名单清单读取、SHA去重、安全文件路径、默认授权记录；导入脚本 `server/scripts/importKeywordGifs.ts` 检查已有审计和实际GIF字节，原子写清单，重复执行幂等。
3. 新增迁移 `019_keyword_gif_removal.sql`：user_id+sha256删除标记。先写API失败测试，再在 `stickers.ts` 增加系统素材删除，`stickerLibrary.ts` 与 `expressionSnapshot.ts` 同步过滤；修改 `app.ts` 接入白名单文件服务。测试用户隔离、删除后目录/推荐消失、版本变更、重导不恢复和路径拒绝。
4. 修改 `client/src/views/Stickers.vue`、`client/src/api/index.ts`：所有关键词系统GIF显示删除按钮和二次确认，个人图沿用已有删除；删除当前用户目录引用，不销毁制作原件。通过前端构建。
5. 实际运行迁移与全量导入；检验晚/午/下午分组、总量、GIF字节与真实接口，相关测试和服务/前端构建。默认授权不伪称逐张审美验收。保留已有未提交并行改动，不自动提交其内容，不push。

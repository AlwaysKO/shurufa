# 表情素材库当前页批量删除 实现计划

> **For Claude：** 使用 superpowers:executing-plans 逐任务实现；在当前分支开发，不创建 worktree。

**目标：** 用户确认全选仅针对当前页，提供勾选、全选、全不选、删除选中及二次确认。

**架构：** Vue 按 SHA 保存当前页选择，加载列表时清空选择；后端通过公共图库事务锁按 SHA 删除聚合记录，保存现有 keyword_gif_removal 删除标记，保留原文件与制作归档。公共图库导出及导入携带并应用素材删除标记，避免重新导入复活。删除影响所有设备，不涉及合成库。

**技术栈：** Vue 3、TypeScript、Express、PostgreSQL、Vitest。

### 任务 1：删除接口
- 文件：server/src/api/stickerMaterials.ts、server/src/stickers/materials.ts、server/src/api/stickerMaterials.test.ts。
- 先测最多 30 张、非法参数/未登录拒绝、同 SHA 历史记录整体删除、其他素材保留、删除标记、原文件保留与重复导入拒绝。
- 新增 POST /sticker-materials/delete，要求 confirm=DELETE；缺失 SHA 拒绝整批，不静默扩大范围。
- 在公共图库锁中完成操作，成功后导出元数据。

### 任务 2：共享清单恢复
- 文件：server/src/stickers/bundle.ts、server/src/stickers/bundle.test.ts。
- 先测 material: 前缀删除记录传播、旧清单不恢复本地已删除素材。
- 仅应用素材删除标记，不改变原系统素材删除语义。

### 任务 3：页面选择及确认
- 文件：client/src/views/StickerMaterials.vue、client/src/api/stickerMaterials.ts、client/tests/sticker-materials.test.ts。
- 先测当前页全选/全不选、翻页清空、取消不删除、成功刷新、失败提示、提交期间禁止重复操作。
- 删除确认说明张数、全设备共享影响、保留原文件；成功清空选择并刷新分组。

### 验证
- 项目根执行 server/node_modules/.bin/vitest run client/tests/sticker-materials.test.ts server/src/api/stickerMaterials.test.ts server/src/stickers/materials.test.ts server/src/stickers/bundle.test.ts。
- client 与 server 分别 npm run build；git diff --check。
- 本轮不操作真实素材，不自动发布、不提交其他未提交修改。

## 实施与验收记录

- 已实现当前页勾选、全选当前页、全不选、删除选中；翻页/筛选清空选择，确认期间锁定选择、编辑和上传，取消/离开页面不提交。
- 删除接口按 SHA 清除同图全部历史记录，保留原文件，写入 `material:<sha>` 删除标记并导出共享清单；拒绝旧清单和两个手动上传入口恢复已删除素材。
- 代码审查补齐目标服务器历史空 SHA 副本的核验与清理，以及旧 `/stickers` 上传入口防恢复；对应回归已覆盖。
- 2026-09-30：`vitest run --maxWorkers=2 --exclude '**/.runtime/**' client/tests server/src/stickers server/src/api/stickerMaterials.test.ts server/src/api/stickerImport.test.ts server/src/api/stickers.test.ts`：27 文件、384 项通过；4 项 PostgreSQL 测试在此命令跳过，随后用独立临时 PostgreSQL 实例分别运行 materials.postgres.test.ts、bundle.postgres.test.ts，4 项全部通过，实例已停止。
- 前端构建通过（现有 bundle 大小警告）；服务端编译及差异检查另以本轮最终命令核对。
- 不修改真实素材、不新增迁移、不发布线上、不自动提交 Git；浏览器实际点击和线上部署尚未验收。本次仅后台改动，不打包或安装 APK。

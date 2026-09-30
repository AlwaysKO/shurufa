# 表情素材库批量上传与本地独立导出实现计划

> 使用 superpowers:executing-plans / test-driven-development 执行，完成前独立审查与 verification-before-completion。

**目标：** 按用户 2026-09-30 明确要求，在本地完成可见素材库入口、上传前去重、已有图关键词回显、新图待标记和无需配对的导出工具，不再只写操作手册。
**架构：** 复用现有素材 API 与 collect 只读采集器。浏览器 SHA256 先匹配、串行上传缺图、显示逐项结果；独立工具只导出原图到本机，不使用后台凭据。配对保留折叠可选。
**技术栈：** Vue/TypeScript/Vitest；Python 3.12/unittest；Windows PowerShell。

## 任务 1：素材库入口及批量上传
文件：client/src/App.vue、main.ts、views/StickerMaterials.vue、views/Stickers.vue、api/stickerMaterials.ts；新增必要 batch 模块和 client/tests 对应测试。
1. 测试先覆盖多选队列内去重、线上 existing/missing/unavailable、哈希响应校验、丢响应回查、失败继续、取消、重试和卸载防旧状态污染。
2. 运行 client 目录 ../server/node_modules/.bin/vitest run tests/sticker-materials.test.ts 及新增测试，记录红灯。
3. 最小实现独立导航入口与批量选择进度。已有匹配不改原关联，显示关键词；新图未分配。保持原 tab 兼容，配对折叠。
4. 定向旧图库/鉴权/导航测试及 npm run build；合成素材浏览器验收。

## 任务 2：独立导出工具
文件：tools/wechat-sticker-import/export.py、test_export.py、install-export.ps1、export.ps1。
1. 用 fake collect 测试原图路径结果、输出隔离、真实计数、失败与 Ctrl+C、无令牌依赖和日志脱敏，先红后绿。
2. CLI 只要求微信账号/程序/输出目录；以 SHA 文件名复用 collector，不转码、不触碰聊天或上传。
3. 单独用户级安装器，不要求 Origin/配对，不改已有助手配置，独立 venv 与用户私有目录，提供可双击快捷方式。
4. Linux/Windows Python 测试、Windows 安装及实际入口验证；真实照片仅本地。

## 任务 3：交付
1. 独立规范/质量审查两部分；修复及复验。
2. 更新 docs/WECHAT_STICKER_IMPORT_GUIDE.md 与工具 README 的真实入口/状态，记录验收。
3. 仅提交本任务文件，保留其他会话 Android/AGENTS 改动。当前授权完成本地开发，不因旧授权擅自将当天未知提交推至生产；上线前核对推送范围、备份与部署状态。
4. 明确本机可用、已提交、已部署三种状态，告知用户实际工具 Windows 路径。

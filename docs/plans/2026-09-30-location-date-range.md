# 位置轨迹跨天查询 实现计划

> **For Claude：** 按 superpowers:executing-plans 逐任务执行，当前分支开发，不创建 worktree。

**目标：** 默认北京时间今天，支持开始/结束日期与包含今天的近 7 天、近 30 天快捷查询。

**架构：** 位置接口增加 from/to 自然日参数，以开始日零点（含）和结束日次日零点（不含）筛选，保持 date/days 旧调用兼容。页面改为双日期，快捷按钮一次更新两端，保留设备范围、请求竞态保护和地址轮询。沿用最多 1000 条限制，明确提示查询范围可能被截断；不跨日连线。

**技术栈：** Vue 3、TypeScript、Express、Vitest。

### 任务 1：先写失败测试
- server/src/api/locationResolution.test.ts：跨天与跨月边界、结束日包含、非法/缺失/反向范围、用户/设备范围。
- client/tests/location-track.test.ts：默认今天、7/30 自然日、手动范围、今天恢复、轮询和旧请求保护。
- 运行 server/node_modules/.bin/vitest run --exclude '**/.runtime/**' client/tests/location-track.test.ts server/src/api/locationResolution.test.ts，确认新增测试因功能缺失失败。

### 任务 2：最小实现
- server/src/api/dashboard.ts：新增范围参数校验和 SQL 边界，保留旧 date/days。
- client/src/api/index.ts：传递 from/to。
- client/src/views/LocationTrack.vue：双日期、今天/近7天/近30天，更新空态和截断文案；单日仍保留前后一天，跨天按整段平移一天。
- 不新增数据库结构，不修改 Android 或其他并发工作。

### 任务 3：验证与审查
- 回归 client/tests/location-track.test.ts、location-analysis.test.ts、server/src/api/locationResolution.test.ts。
- 前后端 npm run build；git diff --check；只读代码审查。
- 不操作真实位置数据，不自动部署或提交；浏览器/线上验收单独说明。

## 验证记录（2026-09-30）

- 新增功能测试先失败：缺少范围参数支持与快捷按钮；实现后 3 个定向测试文件共 48 项通过。
- 覆盖北京时间当天、近7天/30天含今天、跨月/闰年/跨年、结束日全天、手动范围与整段平移、非法/不完整/冲突参数、清空恢复今天、地址轮询与过时响应保护。
- `npm run build --prefix client` 与 `npm run build --prefix server` 均通过；前端保留既有大 chunk 警告；`git diff --check` 通过。
- 沿用最多1000条限制，超过后明确提示缩短范围或按设备筛选；不把局部记录分析当作完整轨迹。
- 未执行真实位置查询/修改，未新增 SQL，未部署、未提交 Git；浏览器实际操作与线上页面尚未验收。未改动其他并发工作。

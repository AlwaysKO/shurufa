# 应用使用日期快捷筛选与自身排除实现计划

> **For Claude：** 使用 superpowers:executing-plans 逐任务实现。

**目标：** 用户已确认不记录妙言自身，历史数据不删除但不参与页面统计；仅按日期查询，今天/昨天/近7天/近30天即点即查，默认今天。
**架构：** 保留使用段状态机对自身切换事件的处理，只抑制自身使用段输出，避免误计到前一个 App。后台统一筛掉自身包名，保持 gap。前端按北京时间日期转换为左闭右开的整日范围。
**技术栈：** Kotlin/JUnit、Vue/Vitest、Express/PostgreSQL。

1. 前端 `client/tests/app-usage.test.ts` 先改日期口径测试，新增默认今天、四档快捷日期、跨月/跨年与非法日期测试；运行见红后修改 `client/src/views/AppUsage.vue`；运行前端测试与构建。
2. Android `UsageSessionEngineTest.kt` 增加自身切入/切出、恢复旧状态回归；运行见红后在 `UsageSessionEngine.kt` 的 usage 输出处排除精确自身变体包名，保留事件边界；运行 usage 模块测试。
3. `server/src/api/appUsage.postgres.test.ts` 新增历史自身排除、普通相似包名不误伤、明细/汇总/时间轴/显式筛选一致测试；独立临时 PG 运行见红；修改 `server/src/api/appUsage.ts` 两处查询条件；不迁移、不删除记录。
4. 浏览器验收日期控件、快捷选中态、真实数据及窄屏；审查 diff，运行相关测试。沿用原签名构建非 testOnly APK，复制到 E 盘，验证版本/签名/哈希；本轮未请求 Git 提交/推送，不擅自处理其他脏改动。

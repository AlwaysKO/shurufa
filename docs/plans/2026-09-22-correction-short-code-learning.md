# 严格纠错学习与短码学习实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 严格同码立即纠错不奖励原词，1～2键在原生合法候选内学习重排。

**架构：** 用户2026-09-22确认：上屏2秒内开始键盘删除，完整删除本词，15秒内原位置同码换词。为不破坏已有累计快照协议，持久化临时奖励（本机排序可见、备份/报告不可见），观察窗结束后事务化转正式学习；严格纠错只取消本笔临时奖励。历史次数、词条及其他设备证据不减。短码使用独立原生内排序，不跨码召回。

**技术栈：** Kotlin、SQLite、Robolectric/JUnit、TypeScript/Vitest。

## 任务1：短码回归与最小实现
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/OfflinePersonalCandidatesTest.kt`；`server/src/api/mobileReports.test.ts`、`personalDictionary.test.ts`。
- 先增加1/2键学习、编码隔离、无注入、同字不同索引、分页测试，运行失败。
- 修改 `OfflineT9Candidates.kt`、`LocalInputStore.kt`、`PersonalDictionaryModels.kt` 与两处服务端校验；只放宽数字短码存储，召回仍保留原范围。
- 运行定向回归并确认通过。

## 任务2：严格纠错及持久化临时奖励
- 创建 `data/completion/CorrectionLearningTracker.kt` 与对应测试；测试时间边界、删全后换词、删空、部分删、异码、异位、快照断裂、发送/切换、重复事件。
- `LocalInputStore.kt` 新增临时奖励表（版本10），事务确认/撤销；测试历史不减、重启恢复、重复确认幂等、未确认不上传、分段奖励一起取消。
- `OfflineT9Candidates.kt` 提供临时学习入口与到期结算；`ImeService.kt` 两个上屏入口与键盘删除统一接入，宿主变化/发送/目标切换/隐私不满足则断开纠错追踪。失败上屏不学习。
- 候选排序只额外合并尚未正式结算的奖励，导出/上报只包含正式学习；不修改历史远端数据。

## 任务3：回归、审查与交付
- Gradle定向单测：`:yuyansdk:testOfflineDebugUnitTest`，包含候选、提交、追踪、数据库和字典同步。
- 服务端：`npx vitest run src/api/mobileReports.test.ts src/api/personalDictionary.test.ts` 和 `npm run build`。
- 审查实际调用链及 diff，保留工作区已有改动，不自动提交/推送/安装。
- `:app:assembleOfflineDebug`；原签名校验、非testOnly、元数据版本和SHA256；固定交付 `E:\Projects\shurufa-android\apk`。
- 真机未操作，不把单测当真机效果验收。

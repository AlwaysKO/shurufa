# 候选读音重复计算优化实现计划

> 使用 superpowers:executing-plans 与 test-driven-development 执行。

目标：依据真机候选热点采样，复用固定正则及一次候选刷新内的规范化结果，不改变候选、排序、学习和禁词语义。

1. 新增 CandidateReadingMemoTest：同键只计算一次（包含null）、按词文及读音双键隔离、不同刷新实例独立；先运行失败再实现最多512项的刷新级缓存，满额直接计算、不丢候选。
2. PersonalWordReading、InputSpellingMatch、T9Spelling 和 OfflineT9Candidates 固定 Regex 提至对象字段，表达式逐字不变。用既有拼写/读音测试和源码热路径保护测试验证不在方法内反复编译。
3. OfflineT9Candidates.select 创建独立 memo，替换重复 normalize；空 extraReadings 时直接返回null，无需规范化。分页闭包仅保留本次缓存，无全局个人候选缓存，不影响后续学习刷新。
4. 运行 completion/个人词库/输入退格关联回归、代码审查、原签名 APK 交付。实际真机关闭采样后的体验仍需用户验收；不擅自安装或提交其他脏改动。

## 执行记录

- 用户已明确授权：测试和原签名校验通过后保留数据覆盖安装。
- 新增3项约束测试，先用透传规范化实现及旧Regex路径运行，3项均发生预期断言失败（未复用、无缓存上限语义、未预编译）。随后实现刷新级memo和固定Regex复用。
- 原始 trace 无文字参数，仅用于已确认热点。此次不新增全局个人候选缓存，不改读音匹配边界和排序，不尝试同时改数据库或后台采集。

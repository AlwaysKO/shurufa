# 原生九宫格拼写校验实现计划

目标：截图 284269 首选遍天下、预编辑 b'tian'x。修复原生候选未按实际读音校验以及预编辑使用过滤前首选的问题。
架构：在原生候选进入排序及分页时使用 comment 拼音直接校验，不再依赖本地词库是否收录该词。前面音节全拼，末音节允许正在输入的前缀，保留完整前缀候选供分段选字。展示拼音按有效候选与原始数字重新对齐，首音节选项使用完整未锁定输入。
技术栈：Kotlin/JUnit、现有 Rime JNI（不修改预编译二进制）。

1. 写原生 comment 回归：遍天下/被调整/蹦跳着/便条纸拒绝，不高兴保留；末音节部分拼写、完整前缀、字母输入不变；分页索引。
2. 运行失败测试，再实现通用拼写校验，接入 OfflineT9Candidates、CandidateSelection 与 RimeEngine。
3. 上方拼音用新首选重算，避免旧分段长度截断新首选；历史不能绕过原生校验。
4. 运行定向测试、工具回归和 APK 构建。恢复 local.properties，不自动提交或安装。没有连接真机，不宣称真机验证。

验证结果：先复现原生四个错误词未被过滤及预编辑错误截断的失败测试（初次测试夹具清空静态词库导致另三项污染失败，已在 cleanup 重置词库消除污染）。最终 35 项定向单测全通过，13 项 Python 工具回归全通过，assembleOfflineDebug 成功，git diff --check 通过。独立审查无阻断问题，已修正字母输入原生注释赋值及 minSdk23 API 兼容问题。local.properties 已恢复。

产物：android/YuyanIme/app/build/unix/outputs/apk/offline/debug/yuyanIme_2026091012_debug.apk。
边界：未连接真机；原生 comment 格式依据现有 JNI 使用方式及二进制内嵌方案 always_show_comments/spelling_hints 推断，单测使用完整读音夹具。锁拼音/显式分词/选短词后的既有非 unlocked 输入路径不在本轮修改范围。不承诺真实设备上“不高兴”一定为首选。

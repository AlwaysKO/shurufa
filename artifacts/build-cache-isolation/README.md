# 2026-09-08 KSP 跨系统执行历史隔离

根目录旧 .gradle/8.9/executionHistory/executionHistory.bin 中同时存在 Windows 与 WSL 绝对路径。只隔离 build 目录不足以隔离 Gradle 任务执行历史；settings.gradle 内设置 startParameter.projectCacheDir 的 Gradle 8.9 最小探针亦证实太晚。

修复：
- gradle.properties 默认 .gradle/windows，涵盖 Windows Studio Tooling API。
- POSIX gradlew 启动参数显式 .gradle/unix，Cygwin/MSYS 保留 Windows 默认。
- 旧目录保留但不再使用；没有删除源码或手机数据。

验证：
1. 新增两个回归测试先红后绿；sh -n、git diff --check 通过。
2. Windows :app:assembleOfflineDebug 成功，1m19s。
3. WSL :yuyansdk:kspOfflineDebugKotlin 和 :yuyansdk:kspOfflineDebugUnitTestKotlin 成功，3m18s；没有禁用测试 KSP，也没有使用前轮临时 init script。
4. 返回 Windows :app:installOfflineDebug 成功，6s；59 个构建任务保持 UP-TO-DATE，仅安装任务执行，未重新打开 Studio。
5. 日志报告 Installed on 1 device，手机 ELI-AN00，安装版本 20260908.09，包名 com.yuyan.pinyin.offline.debug。覆盖安装，未卸载、未清数据。
6. 实际执行历史分别出现在 .gradle/windows/8.9 与 .gradle/unix/8.9。

本轮是构建缓存修复，不声称已真机验收拼音质量或双端事件上报。前轮 473 项业务测试未在本轮重新全量运行；本轮验证真实双端编译/KSP/安装。

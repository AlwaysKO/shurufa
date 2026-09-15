# 已安装 APK 的九宫格隔离基线

日期：2026-09-14。仅测试输入引擎，不是应用改动或发布产物。

`comparison.json` 保存 8 组原生候选/本地后处理结果；6 组目标首选，2 组失败。
`T9Baseline.java` 为设备端测试程序，未调用选词/学习，不读取用户数据库。

## 复现条件

1. 取得同一版本 APK（SHA-256 见 JSON），提取 arm64 的 libyuyanime.so；不要使用搜狗原生库。
2. 以 Android SDK 的 android.jar 编译 Java 文件，用 d8 把所有输出 class 转为 classes.dex。
3. 在设备上创建全新测试目录，将 dex 和 so 放入；代码会从 APK 提取内置 assets/rime 到该目录的 data 子目录。
4. 将 dex 和 APK 加入 CLASSPATH，以 app_process 执行 T9Baseline，传入测试目录和设备上的 APK 路径；java.library.path 指向测试目录。
5. 原始输出的 CODE 行为 Rime 100 项首屏，AFTER 行为同 APK 的 OfflineT9Candidates.select 处理后前 10 项。

调用形式（所有占位符需换成已核实路径）：

```text
CLASSPATH=<classes.dex>:<installed.apk> app_process -Djava.library.path=<isolated-dir> /system/bin T9Baseline <isolated-dir> <installed.apk>
```

测试依赖该调试 APK 的类名及反射接口；更换版本后不能保证直接适用。测试 Context 仅提供 assets 和 packageName；不启动正式 IME 服务。必须使用全新隔离目录，绝不传入真实用户 rime 目录。不要为测试修改手机正在使用的输入法或清除其数据。

未覆盖：真实个人历史、锁拼音、分段选词、翻页、实际编辑器提交及性能。不得把这份结果描述为完整实机验收。

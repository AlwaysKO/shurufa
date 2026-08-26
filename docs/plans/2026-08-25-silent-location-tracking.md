# 静默尽力型位置轨迹实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 在无常驻通知的前提下，通过输入法活跃定位和被动定位形成稀疏路线，并在 Android 客户端过滤陈旧、低精度、过于频繁和未确定移动的位置。

**架构：** 新增纯 Kotlin `LocationUploadPolicy` 负责可单测的过滤和距离计算。`DataCollector` 负责监听器生命周期、互斥上报及 SharedPreferences 持久化；`ImeService` 只传递输入活跃状态。服务端接口和管理后台保持不变。

**技术栈：** Kotlin、Android `LocationManager`、Kotlin Coroutines、SharedPreferences、JUnit/Robolectric、Gradle。

---

### 任务 1：定位上传策略

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/LocationUploadPolicy.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/collect/LocationUploadPolicyTest.kt`

**步骤 1：编写失败测试**

覆盖以下行为：首次有效位置通过；超过一分钟的缓存位置拒绝；精度超过 200 米拒绝；成功上报后一分钟内拒绝；移动距离未超过 `max(50, 两次精度之和)` 拒绝；超过阈值通过。

**步骤 2：运行测试验证失败**

运行：

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*LocationUploadPolicyTest' --offline
```

预期：FAIL，提示策略类型不存在。

**步骤 3：实现最少策略**

实现 `LocationCandidate`、`UploadedLocation`、`LocationUploadPolicy.shouldUpload()` 和 Haversine 距离。常量为：最大年龄 60 秒、最短成功间隔 60 秒、最大精度 200 米、基础移动距离 50 米。

**步骤 4：运行测试验证通过**

重复步骤 2，预期全部 PASS。

### 任务 2：监听器生命周期与持久化

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/ImeService.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/collect/LocationUploadPolicyTest.kt`

**步骤 1：补充失败测试**

补充边界：恰好一分钟允许、定位时间写入候选值、负精度/未来时间拒绝。

**步骤 2：实现 DataCollector 接入**

- 分离被动监听器与活跃监听器。
- 新增 `setInputActive(context, active)`。
- 输入活跃时注册 GPS/网络；不活跃时注销它们。
- 权限有效时注册被动监听。
- 统一调用策略；用 `Mutex` 串行判定和 HTTP 请求。
- 仅成功时写入 SharedPreferences。
- `occurred_at` 使用 `Location.time`。

**步骤 3：接入 ImeService 生命周期**

在 `onWindowShown` 激活定位，在 `onWindowHidden` 停止主动定位；销毁服务时确保标记为不活跃。

**步骤 4：运行定位策略和完整 SDK 测试**

```bash
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --offline
```

预期：全部 PASS。

### 任务 3：后台定位权限与构建策略

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml`

**步骤 1：添加权限声明**

增加 `android.permission.ACCESS_BACKGROUND_LOCATION`，不增加任何前台定位 Service 和通知代码。

**步骤 2：构建 Debug 和 Release**

```bash
cd android/YuyanIme
./gradlew :app:assembleOfflineDebug :app:assembleOfflineRelease --offline
```

预期：BUILD SUCCESSFUL；Release 仍使用 HTTPS API 配置。

### 任务 4：真机验证

**文件：** 无生产文件修改。

**步骤 1：覆盖安装 Debug APK**

使用 Windows ADB 执行 `adb install -r --no-streaming`。

**步骤 2：授予定位权限**

向 Debug 包授予精确位置和后台位置权限，确认 AppOps 允许后台定位。

**步骤 3：验证首个真实位置**

启动本地一键服务，激活输入法，确认 `ShurufaCollector` 输出位置成功且数据库产生当前荣耀设备的位置。

**步骤 4：验证限流和静止去重**

保持静止超过一分钟，确认数据库不新增；触发其他地图应用定位或实际移动后，确认只有满足距离阈值的新点才新增。

**步骤 5：运行项目回归检查**

```bash
scripts/tests/android-network-security-test.sh
scripts/tests/reporting-scripts-test.sh
scripts/tests/start-script-test.sh
```

预期：全部 PASS。

# 微信 8.0.78 红包继续开发前核验

日期：2026-10-09。基线提交：`83bf15d`，当前分支 `main`。
承接 [跨电脑交接](../plans/2026-10-09-redpacket-handoff.md)。本记录是已执行核验，不代表 Hook 已载入或红包已领取。

## 现有功能回归

在 macOS 当前工作区执行：

```sh
source .runtime/macos/android-env.sh
./android/YuyanIme/gradlew -p android/YuyanIme \
  :yuyansdk:testOfflineDebugUnitTest \
  --tests 'com.yuyan.imemodule.data.redpacket.*' --offline --console=plain
```

结果：`BUILD SUCCESSFUL`，11 个测试套件、138 项测试，失败/错误/跳过均为 0。
有既有 Android API 弃用等编译警告。JUnit XML 实际位于
`android/YuyanIme/yuyansdk/build/unix/test-results/testOfflineDebugUnitTest/`。
日志：`/tmp/shurufa-redpacket-20261009-baseline.log`。

这只验证已有通知/可见页面状态机，不能证明通知全关时发现红包，更不能替代真机到账验收。
工作区原有通话录音修改及测试保留，未重置、未提交、未安装。

## 真机及分身隔离

只读 ADB 核验连接设备 `AQUL024807002303`，荣耀 `ELI-AN00`，Android 16。
本次重新枚举用户，主用户为 0，分身用户为 128；两者微信均已安装。

分别执行 `pm path --user 0 com.tencent.mm` 和 `pm path --user 128 com.tencent.mm`，
返回完全相同的 `/data/app/.../com.tencent.mm-.../base.apk`。
`dumpsys package com.tencent.mm` 仅列一组代码路径、版本和签名，两个用户各有数据 inode。

**结论：此系统分身不是独立安装包。不能将重签覆盖同包名 APK 视为“只改分身”。**
若安装被签名不匹配拒绝，不能通过卸载、清数据来绕过。
本次没有执行这些操作，也没有启动微信、点击红包、主动亮屏/息屏或读取聊天数据库。

只读属性仍为 `flash.locked=1`、`vbmeta.device_state=locked`、`verifiedbootstate=green`。
shell 为 uid 2000，`command -v su` 无结果；分别筛选 user 0 / 128 的常见框架包名无结果。
包名筛选不能排除隐藏/改名框架，因此结论是**未确认有可用 Hook 环境**。
直接不指定用户的包枚举曾因平行空间 user 100 权限被拒绝，后续改为明确指定 0 / 128；没有申请额外权限。

## 本机安装包静态核验

从上述只读安装路径重新导出 APK，本地分析，不上传、不放入 Git。
大小 `280614450` 字节，SHA256：

`41f7dc1f720767fa78fa20dd13ea034b817bbf6ebd23dfd1324c647499c9c1ba`

与交接文档及本机已有分析副本哈希相同。设备版本仍为 `8.0.78 / 3180`。
使用本机 Android SDK 35.0.0 的 `dexdump -d`，核对实际声明与指令，未实例化请求、未调用资金接口。

| 项目 | 当前 APK 中已核验的声明 |
|---|---|
| Receive 特征所有者 | `com.tencent.mm.plugin.luckymoney.model.n6`，`classes13.dex`，精确标签 `MicroMsg.NetSceneReceiveLuckyMoney` |
| Receive 构造 | 无参；`(int, int, String, String, int, String, String)` |
| Open 特征所有者 | `com.tencent.mm.plugin.luckymoney.model.h6`，`classes13.dex`，精确标签 `MicroMsg.NetSceneOpenLuckyMoney` |
| Open 构造 | 无参；两个 int 加七个 String；两个 int 加八个 String |
| 两类业务回调 | `void onGYNetEnd(int, String, org.json.JSONObject)` |
| 网络基类 | `com.tencent.mm.modelbase.m1`，`classes11.dex` |
| 分发器 getter | `com.tencent.mm.network.s dispatcher()` |
| 请求方法 | `int doScene(com.tencent.mm.network.s, com.tencent.mm.modelbase.u0)` |
| 底层分发 | `int dispatch(com.tencent.mm.network.s, com.tencent.mm.network.y0, com.tencent.mm.network.l0)` |
| 通用回调接口 | `com.tencent.mm.modelbase.u0`，`classes.dex`：`void onSceneEnd(int, int, String, com.tencent.mm.modelbase.m1)` |

Receive / Open 的实际继承链为：

```text
n6 / h6 (classes13.dex)
  -> luckymoney.model.e6 (classes13.dex)
  -> luckymoney.model.q5 (classes3.dex)
  -> wallet_core.model.d1 (classes3.dex)
  -> modelbase.m1 (classes11.dex)
```

不能沿用参考代码注释中的旧类名/继承链；`wallet_core.model.d1` 的包路径也不是 `plugin.wallet_core.model.d1`。
业务回调和通用回调是不同层级，参数和错误语义不能混用。
随后对 APK 根目录全部 17 个 DEX 执行精确标签复核，三个标签分别只有上述一个类持有。
这是此 APK 的静态唯一定位证据，仍不构成 Tinker 运行类或 Hook 载入验证。
`...ReceiveLuckyMoneyUnion` / `...OpenLuckyMoneyUnion` 是其他类，不能使用模糊包含匹配取第一个候选。

WCDB 的 `database.SQLiteDatabase` 和 `compat.SQLiteDatabase` 均在 `classes11.dex` 有实际定义。
两者均有以下方法，返回 `long`：

```text
insert(String, String, ContentValues)
insertOrThrow(String, String, ContentValues)
replace(String, String, ContentValues)
insertWithOnConflict(String, String, ContentValues, int)
```

这仅证明可选 Hook 点存在，尚未证明真实新消息经过它们。
探针须只接受成功写入、明确入站、明确群会话的候选，避免包装方法嵌套导致重复事件；不能在数据库线程做截图、联网、全量 DEX 扫描或持续轮询。

Receive 回调指令可见 `hbStatus`、`receiveStatus`、`timingIdentifier` 等字段读取。
Open 回调还涉及实名引导和拦截结果；不可以“请求发出”或“回调发生”当作到账。
字段存在不证明枚举值语义、专属红包适用性或领取许可，仍需离线控制流分析及受控运行验证。

补充使用官方 JADX 1.5.6 定向导出 `n6`、`h6`、`q5`，三次命令均正常结束。
下载包 SHA256 与官方 release 资产 digest 一致：
`545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974`。
`q5.doScene` 的 DEX 指令确认保存当前请求的回调，并返回底层 dispatch 或宿主测试响应路径的 int 结果，不能仅以反射调用未抛异常判定发送成功。
JADX 的 `q5` 复杂错误分支存在局部变量恢复不完整，控制流语义必须回到 DEX 核验，不能直接复制反编译 Java 当实现。

本地证据位于 Git 忽略目录 `.runtime/redpacket-hook-20261009/`：
`tag-owners.json`、`all-dex-tag-owners.json`、`class-definitions.json`、定向 `*.dexdump`、`method-signatures.json`、
`baseline-tests.json`、只读导出的 APK 及固定 SHA 的参考源码。没有保存通知或聊天正文。
定向反编译输出 `n6.java`、`h6.java`、`q5.java` 同样仅留在此本地忽略目录。
首次 dexdump 读取因 DEX 字符串编码失败，后续仅在显示转换时使用替换字符；上表 ASCII 类型/签名完整，不将乱码作为匹配证据。

## 方案选择与剩余工作

建议先做独立、默认关闭、只识别不领取的 Hook 探针，和输入法生产模块解耦：

1. **版本与范围门禁：** 校验宿主包、版本、主进程和明确选择的 Android 用户；未知用户、版本不符或签名/类定位歧义时不挂载。用单元测试验证拒绝路径。
2. **运行类核验：** 待宿主实际初始化完成后核验真实 ClassLoader、WCDB 方法签名及特征。只报告匹配数量与状态，不输出聊天正文、群名或完整红包凭据。
3. **识别测试：** 只计数成功入库的群红包候选，过滤私聊、自发、失败入库、普通消息；有界去重，不增加轮询或持有唤醒锁。用离线样本测试并构建独立 APK，安装前仍须解决设备隔离。
4. **领取状态机另行推进：** 精确请求/回调关联、发送失败门禁、超时与未知结果不重放、最终结果验证、专属红包适用性及游戏/输入保护；未获得允许前不调用资金接口。

继续扩展通知/界面路线只能完善已有能力，无法满足系统通知全关且微信不在前台的核心目标；副屏路线已有未解决焦点/黑屏/能耗问题，本轮不重做。
Root/重签覆盖主微信均不作为默认方案。当前手机上的隔离运行方式尚未成立，不能称为“只安装到分身就可以”。

探针方案已请求用户确认，确认前没有新增正式实现、安装包交付或手机注入。
确认门禁来自交接文档第 9 节“设计并经确认后实现”，不是静态核验或本地回归必须另行授权。

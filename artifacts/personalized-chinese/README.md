# 安卓中文候选个性化测试包

- APK：`shurufa-personalized-chinese-20260908.apk`
- 包名：`com.yuyan.pinyin.offline.debug`
- 版本：`20260908.00`（2026090800）
- SHA256：`84359852aa008d1416586ae5045508d9e177c5811816713cf234250e3b12eec3`
- 日期：2026-09-08，当前工作目录未提交构建。

## 本轮变化

本地中文基础库 130,860 条，加 347 条聊天、办公、互联网/编程中文补充；其中新增不重复词 304，合计 131,164 个文字词条。不新增英文词库，不复制搜狗专有资产。

安卓九宫格及标准全键拼音按原始编码学习，使用基础先验与十四天半衰期选词权重排序。常选“需求”后可超过默认“续期”，单次误选不会永久置顶。数字与字母编码独立统计，SQLite v1→v2 保留旧习惯与待传事件。密码及禁止个性化输入不参与新增学习/commit 上报。联网仍只用于既有双端补传，不参与逐键排序。

## 验证

- SDK 全量：80 类、473 tests、0 failures/errors/skipped，详见 `test-summary.json`。
- Gradle 测试和构建：BUILD SUCCESSFUL，6m27s，详见 `verification-build.log`。
- 纯 Kotlin 回归：16 项通过；Node 生成器：1 项通过。
- APK 签名验证通过（v1/v2）；META-INF 的 JAR 签名范围警告保留，v2 整包签名通过。
- APK 内两个词典资产与源码资产逐字节一致。
- 只读代码审查未发现确定的严重/重要缺陷。
- 兼容环境：仅禁用历史 Windows 路径污染的测试 KSP 任务，生产 KSP 正常开启；测试堆 1536m、单并发、每十类新 worker。迁移测试使用显式 close，避免 Android 28 的 SQLiteOpenHelper 不实现 AutoCloseable 引发测试夹具类型转换异常。

重跑命令（临时 init script 内容见实现计划）：

```bash
source ~/android-tools/env.sh
cd /home/ko/project/shurufa/android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug \
  -I /tmp/shurufa-test-verification.gradle --configure-on-demand --offline
```

## 待真机验收

ADB 当前没有设备，未声称实机候选/翻页或双端事件验收完成。本地与线上 /health 均返回正常，但不代表手机已经发出事件。

1. 覆盖安装同包名测试包，不卸载清数据；断网连续输入 `46898262`，检查“候选词”。
2. 安卓标准全键输入 `xuq`，若“续期”首选，选“需求”数次后重复输入并重启验证；九宫格 `987` 单独验证习惯。
3. 测试删除、锁拼音、部分选词、翻页、切换方案，点击词和上屏必须一致。
4. 本地通过 `adb reverse tcp:3000 tcp:3000` 或设置可达局域网地址，联网等待至少 30 秒；在看板选择真实手机用户，核对本地和线上同 id 事件。不要把手机 127.0.0.1 当作电脑地址。

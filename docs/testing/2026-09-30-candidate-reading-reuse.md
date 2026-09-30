# 候选读音重复计算优化验收

## 依据及范围

用户确认原则为打字好用；同意按采样热点优化，并明确授权测试/原签名校验通过后保留数据覆盖安装。
真机证据见 `2026-09-30-yiyang-candidate-profile.md`：未开启函数采样时，候选阶段输入230ms、退格245ms，原生阶段分别16ms/10ms；不把函数采样导致的471/533ms直接当作日常延迟。

## 改动

- PersonalWordReading、InputSpellingMatch、T9Spelling、OfflineT9Candidates 固定 Regex 表达式原样提为对象字段，避免每词重复编译。
- select 为每次刷新新建 CandidateReadingMemo，规范化按词文与原始读音双键缓存，包含null结果；最多512项，满额继续正常计算，不裁剪候选。空extraReadings直接返回null。
- memo 随本次 CandidateSelection 分页闭包保留，不跨刷新缓存个人排序或学习状态。读音判断、排序、原生索引、禁词和学习逻辑不变。
- 审查发现分页可能在后台执行，新增局部 @Synchronized。锁只覆盖本次memo与无IO的纯规范化，不锁全局词库/数据库。

## 测试与审查

- 初始3项红测试：透传规范化重复计算、容量行为、固定Regex未预编译，均出现预期断言失败。
- 初版 completion 全模块及个人词库/禁词/学习/退格关联回归 **289/289** 通过，31个测试类；原始XML保存在忽略目录 `.runtime/diagnostics/yiyang-green1/`。
- 并发回归先见红：8线程同键查询，无锁版本4项中1项失败；加同步后再执行最终专项回归及打包，结果待补。
- 两轮只读审查：初轮P2缓存线程安全问题已修复，复核无剩余重要问题。
- 构建使用本机独立ext4输出与Gradle缓存，不改变通用项目构建配置。编译有原有deprecated等提示，不称零告警。
- 保留其他任务的AGENTS.md、server/data/sticker-library.json等改动，不提交/推送。

## 待真机验证

关闭函数采样，只保留无文本内容的慢键阶段日志。安装后由用户手动九宫格输入yiyang再逐个退格；必须用实际手感及无采样延迟验证，不用单测通过冒充卡顿已全部解决。

## 最终验证与交付

- 并发修复后的专项 **25/25** 通过：CandidateReadingMemo4、InputCompletion8、InputSpellingMatch5、DictionaryCandidatePolicy8；与最终 `:app:assembleOfflineDebug` 同次命令成功。此前289项广泛回归包含初版3项memo测试，不与25重复累计宣称为独立总数。
- 唯一推荐APK：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.14-2026093014-debug-aa21adf8.apk`；versionName20260930.14、versionCode2026093014，包名com.yuyan.pinyin.offline.debug，非testOnly。
- 源包/交付包/安装后base.apk SHA256完全一致：`aa21adf8e85d310ba4fa07c1a3f7b7ef81a0913d7defb6aa77f36780d605260a`。
- API23/27/28/32/36原证书验证通过：`a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
- 用户授权后通过Windows ADB执行install -r，返回Success；手机版本从20260930.10升级为20260930.14，lastUpdateTime2026-09-30 14:43:57，默认输入法不变，进程19579。未卸载/清数据/操作聊天。
- 请用户再次操作九宫格yiyang输入/逐字退格；只临时启用ImeLatency日志，未开启函数采样；日志采集设120秒自动结束并恢复标签配置。真机结果仍待本次反馈，不以安装完成代替性能验收。

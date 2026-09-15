# “我不再彷徨”长组合排查（2026-09-14）

## 状态：修复已确认的前缀排序问题，整栏空白仍待现场复现

用户反馈继续输入后候选消失、删除后面的拼音才恢复。已询问是否中途选过“我不在/我不再”，本轮尚未获得答复。

- Windows ADB 能见在线设备；已安装 `.15` APK SHA-256 为 `fdd39c02e8d630cda5db1406e6a1654ce17d455382225c211ac315748cbf10b7`，不是上一批本机输出 APK 的整包哈希。已拉取核验：原生库、table、prism、公开索引与本轮资源相同，详见 package-verification.json。
- 所有设备测试都在新建 `/data/local/tmp/shurufa-long-*` 内独立 app_process 进行，使用 APK 类和已校验相同的原生库/资产。未读取用户 SQLite/Rime 历史、未操作聊天输入框、未安装/切换输入法；候选 APK 只作为隔离测试文件复制到该目录。

## 根因证据

全码 `9628924726448264`（wo bu zai pang huang），末字 h 为 `962892472644`。

原生数组首项为“我不再彷徨”，随后为“我不在、我不再、我不、我部”，再是单字。现有整词依据不含整句，整句被剔除；由于上述前缀在公开索引中而非旧小词库，排序把它们放到95个单字之后。“我不再”由原生索引2变成展示索引96（第97项）。

这不是原生不认识“彷徨”，也没有证据表明按键已经丢失。46步连续输入、退格、再输入的隔离原生/后处理数组均未完全变空，因此不能直接把用户的“整栏空白”全部归因于排序。

## 精确修复

仅修改 OfflineT9Candidates 的分段前缀顺序：两类可信原生前缀按原生索引合并，不再把公开前缀一律放到单字后面。完整候选和末字补全优先级、个人权重、拼音对齐、词库/.so不变，未新增任何句子白名单/黑名单。

`scope.patch` 只含本轮相对于已有工作区的改动，不包含之前未提交工作。

## 验证

- `T9LongCompositionTest` 使用16个已安装APK JNI录制数组。红测在962892472报“我不再”索引96；绿测覆盖正向及逆向快照、前8项、原生索引/读音、已知错误串、显示不含数字。快照倒序不是原生退格；真实交互另记如下。
- 17套件96项，0失败/错误/跳过。额外长句测试按T9CommitTracker分两段选择，累计3次后关闭并重开数据库，完整码和末h码均从个人词库整句首选；不冒充真实宿主提交/进程重启。
- 同一手机上旧APK/新APK各46步原生连续前进、退格、再输入：原生文字/读音数组逐项完全一致；新后处理在wo bu zai全码以后每一步保留“我不再”于前8项。两边都不是整个数组空白。
- 新APK两次独立冷库分段测试：两码“我不再”展示索引1→原生索引2；选中不提前commit，余码“彷徨”索引1；再次选择后原生commit精确为“我不再彷徨”。没有发给宿主/聊天，也没有修改实际输入历史。
- 305组旧录制语料重放与上一批公开索引接入的结果直接比较：已有正确首选退化0、召回丢失0；107组原先未命中首选的输入改变了首项（现在允许更靠前的可信原生前缀），不声称全部变化语义更好。旧反馈/已知乱串/个人学习断言继续通过。
- 只读复审未发现阻断问题，特别确认没有修改索引映射和完整候选优先级。

**失败/未覆盖：**`T9EngineTransitions` 尝试装配完整RimeEngine时进程被Killed，结果为空，engine.log.gz保留；未据此推断生产崩溃原因，也不算作通过。没有真实触摸/UI、用户当时的锁拼音状态或完整空栏现场证据。未知整句仍受原过滤约束，本补丁不让冷启动整句直接出现。

## APK

`android/YuyanIme/app/build/t9-display-audit/outputs/apk/offline/debug/yuyanIme_2026091416_debug.apk`

SHA-256 `fa3952df0fbf5008a20a607675a33a915fd2caeec3f6d040460287af218d3959`，129719299字节，签名v1/v2有效（原工具警告完整保存）。原生库/table/prism/公开索引哈希与已安装版本一致。只打包及隔离加载，**未安装、未提交、未公开发布**。

## 重跑

- Java探针编译：JDK17，Android36 android.jar 为classpath；d8必须包含匿名内部类。安装版使用动态 `adb shell pm path com.yuyan.pinyin.offline.debug`，不能复用过期APK路径。
- `T9LongComposition <新目录> <APK> cases.tsv <输出.jsonl>` 记录独立清空后16个输入。
- `T9LongTransitions <新目录> <APK> transitions.tsv <输出.jsonl>` 连续46步，按键ACTION_UP对应原路径mask1，退格mask0。
- `T9LongSelection <新目录> <APK> <只含一个完整/短码的TSV> <输出.jsonl>` 必须前缀前8项且原生commit匹配，否则抛断言。两个码分别用新目录/进程，避免隔离用户库学习污染。
- 调用方式参见 `../2026-09-14-t9-baseline/README.md`：CLASSPATH=dex:APK，app_process 的 java.library.path 指向独立原生库目录。绝不能指向真实输入法用户目录。
- Gradle复跑沿用 `/tmp/shurufa-rime-model-audit/display-build.gradle` 和独立 `.gradle/t9-display-audit`；不要clean共享build，先source `/home/ko/android-tools/env.sh`。最终测试日志、打包日志、红测XML与全部机器输出均在本目录。

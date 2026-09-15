# 推荐动态发送、AI合成与Emoji功能修复计划

> 使用 superpowers:systematic-debugging、test-driven-development、subagent-driven-development；每项实现→规格→质量依次验证。继续main，不创建worktree，保留用户倍率1.1/1.5未提交修改。

**目标：** 解决Emoji占位假预览不可点，AI准备阶段面板提前消失；获取推荐GIF经微信发送的真实格式证据，不能以缓存为GIF替代验收。

**已证实：** 本地2304组合仅内置31、URL均无；同版本catalog请求304不会注入URL。Emoji缺失路径不读缓存且URL空立即失败，显示基础Emoji却称组合预览。AI点击同步清查询后才启动准备，长编码只有短Toast；尚未证实渲染失败或被普通空候选回调取消。推荐预制GIF准备不叠字、发送用image/gif及FileProvider原文件，但接收端静态根因仍未知。

## 任务1：Emoji素材解析及真实状态（TDD）

涉及 Android yuyansdk/src/main/java/com/yuyan/imemodule 下 expression/ExpressionSync.kt、keyboard/InputView.kt、expression/ui/EmojiCombinationPicker.kt，必要时提取最小共享ExpressionAssetResolver；布局和strings按明确状态最小改。测试对应resolver/Sync/EmojiPicker/InputView。
- 失败测试：catalog URL空但合法fileName可用标准/uploads/expression/{fileName}下载；缓存命中不联网；内置命中；错误SHA拒绝；下载失败不伪装可发送预览；内置/下载组合点击交付正确组合；旧回调不覆盖新选择。
- cache→assets→显式URL或已存在标准相对URL，共用预览与发送解析，不重复独立下载。不一次内置42MB所有素材。
- 加载/失败/可重试状态清楚呈现，只有真实文件可准备发送；不显示基础Emoji当成合成成功。不改服务器协议/素材及全局布局。
- 先RED后最小实现GREEN，自审提交，双审查。

## 任务2：AI准备与发送可见状态（TDD）

涉及InputView、ExpressionFlowController/Panel必要最小状态及测试。先以阻塞prepare的真实InputView用例复现点击清空且无持续状态；保留原查询与输入清空的用户既有行为意图，但在完成前不让功能静默消失。忙碌阻止重复提交，失败可见/可重试，真实编辑/切目标/卸载仍取消，不发到错误目标。不将性能问题混成缓存/并发大重构。
- 成功路径必须验证原查询进入prepare、成品进入sender；慢准备时持久反馈，失败提示；清空失败不发送。
- 如需改变清空时点或状态生命周期，先定位并给最小设计再实现。
- 先RED/GREEN，规格→质量，独立提交。

## 任务3：推荐GIF真机证据

已询问能否操作微信文件传输助手，未答复前不操作手机。获准后先记录包版本/当前状态，限定测试图和文件传输助手。定位选中项→prepare原文件帧数/hash/MIME→commitContent返回值→接收端动态/保存文件；必要时仅debug诊断记录asset id、阶段、MIME/大小/hash/耗时，不记录聊天内容或敏感uri。只有根因证实才TDD修发送；不靠改后缀、任意接微信SDK或声称微信固有限制。

## 验证

Gradle单一所有者；备份/tmp/shurufa-send-debug/local.properties.original，source /home/ko/android-tools/env.sh，--project-cache-dir /tmp/shurufa-gradle-wsl-production；每任务定向验证，最后完整:yuyansdk:testOfflineDebugUnitTest与:app:assembleOfflineDebug。本轮服务端未改则不重生成素材。恢复SDK；若操作真机恢复搜狗和stayon false。记录未验证边界；用户两行倍率原样留未提交。

## 阶段交付

- [x] 任务1：Emoji解析、真预览、失败重试及取消恢复，9ae5dbf/1d69d03/944e0c6，双审通过。
- [x] 任务2：AI准备持续可见、失败重试/同词/生命周期取消，dc8e8b2，双审通过。
- [x] 构建与已有套件验证：430项通过，测试堆1536MB；排除另一暂停任务6个未完成测试，不能宣称全部工作区通过。512MB初跑OOM证据保留。
- [ ] 任务3：已获授权并在文件传输助手复现（20260907.17）：缓存16帧GIF，系统授权URI指向该GIF，微信保存结果实际单帧PNG；尚未修复。补充debug交付诊断与真实Provider回归测试后再判断修复策略。
- 详细证据及边界见 artifacts/expression-functional/README.md。用户字号及外部文件保留；SDK已恢复。

### 任务3追加：交付边界诊断（不是修复）

只修改 `expression/send/ExpressionContentSender.kt` 及对应测试，必要时增加同目录 internal helper。先测试要求诊断关闭不读文件、诊断失败不影响发送、URI交付仍是GIF/正确MIME，再运行RED/GREEN。Debug版本发送前在IO线程读取文件和Provider URI的大小/魔数/SHA，记录Clip MIME、getType、显示名、目标包；不记录查询/聊天内容，release不执行诊断。诊断不更换发送路径，不接微信SDK。规格、质量审查后构建诊断APK，限定文件传输助手再验一次，恢复输入法和常亮。

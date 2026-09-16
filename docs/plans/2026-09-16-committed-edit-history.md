# 已上屏文字编辑记录 实现计划

> **For Claude：** 使用 superpowers:executing-plans 按检查点执行。

**目标：** 仅针对已经进入聊天输入框的文字，整段展示编辑结果并完整保留中途输入、删除、替换记录。
**架构：** 原始事件先持久保存、批量上报；完整快照、输入框会话及可靠顺序支撑派生汇总。汇总不能替代原始记录，不能只保留最终文本。
**技术栈：** 现有 Android SQLite 事件队列、Express、PostgreSQL、Vue。

## 用户确认与边界
- 2026-09-16 用户确认：只指已经进入聊天输入框的文字，未上屏拼音/候选不纳入整句编辑记录。
- 同一人/设备/应用不足以证明同一编辑会话，不凭几秒间隔拼接。发送、清空、输入目标变化须有边界；中间插字、选中替换须有文本前后与选区证据。
- 已删字符应保留且可搜索。未知、截断、不完整快照须显式标识，不推测补齐。
- 沿用 `docs/android-integration.md` 的密码框和禁止个性化采集边界；不扩大采集范围、不覆盖用户已有修改。

## 历史阻塞（WSL 重启后已解除）
- `/home/ko/project/shurufa/android/YuyanIme` 在本轮与上一轮读取均报 I/O 错误，不能检查其子目录规则和当前工作文件，不修改或用 Git HEAD 覆盖它。
- 仅读取 Git HEAD 作为历史证据：DataCollector.recordEvent 未提供会话ID、完整编辑前后文本；删除记录在调用宿主删除前写入。此结论仅针对已提交快照，不冒称当前手机版本或不可读工作区状态。
- 因此当前不能实现/验证手机端完整采集与准确整句边界，也不能承诺中途删除全部不漏。已向用户询问迁移/挂载状态。

## 阶段1：先修复可读链路（本轮执行）
文件：`server/src/api/mobile.ts`、`server/src/api/dashboard.ts`、`client/src/api/index.ts`、`client/src/views/Activity.vue`。
1. 先写接口测试：批量输入保存text_before/text_after，包括空字符串；重试幂等；跨设备拒绝；commit/删除原始事件不相互覆盖。
2. 默认行为明细包含delete/external_delete，不包含key/compose；新增“删除”筛选。
3. 查询返回已有会话、编辑器、序号与前后文本；搜索覆盖已删内容和快照；UI可展开前后内容，区分空文本与未采集。
4. 运行相关服务端与前端测试、构建；不改数据库结构（现有表已有两列），不修改原始历史数据。

## 阶段2：Android目录恢复后再执行（未实施）
- 完整读取当前目标子目录规则和真实源码，核对采集开关、密码框/禁止个性化标记、成功上屏/删除/替换回调。
- 本地事件包含可靠编辑会话ID、递增操作序号、成功操作前后文本及必要选区信息；先落盘、可重试批量上报，不等待成句再保存。
- 不把无操作成功证据的删除尝试记为已删除；宿主不能提供完整文本时标记不完整，不能臆造。
- 以这些证据建立独立整段编辑汇总与完整展开明细；先匹配编辑组、再按组分页并返回完整成员，不能先把匹配/分页片段截断再拼句。
- 真机验证：连续短句、中间插字、选中替换、逐字删除/全删、断网/重启补传、切换聊天/输入框、发送清空、密码框不采集。无手机端验收不宣称“不漏”。

## 阶段 1 验证记录（2026-09-16）

- 服务端现在持久化协议已有的 `text_before/text_after`，空字符串与未采集 NULL 分开处理；不改表结构，不回填旧记录。
- 行为明细默认显示 `delete/external_delete`，新增删除筛选，搜索覆盖前后快照；返回编辑器与序号证据，但不据此伪造合并整句。
- 前端提供编辑前后展开查看，明确空输入框/未采集，以及快照不等于消息已发送。无快照的旧记录仍只显示原始片段。
- 新测试先验证快照丢失、删除被默认隐藏、快照无法搜索及 UI 缺少编辑详情，再完成修复。
- 服务端定向测试：committedEvents/deviceIsolation/ipLocation/mobileReports 共 18 项通过；前端 activity/content-library 共 14 项通过。
- 前后端构建通过；前端保留已有大包体积警告。相关跟踪文件 `git diff --check` 通过。
- pg-mem 对 ON CONFLICT 的 rowCount 与 PostgreSQL 行为不同，幂等测试验证重试后实际持久化行数与内容，不对模拟器 rowCount 作错误断言。
- 再次读取 `android/YuyanIme` 仍为 Input/output error。手机端采集、整句聚合、真实设备无遗漏验收尚未完成；未部署服务或发布 APK。

## 阶段 2 开始（WSL 重启后，2026-09-16）

挂载已恢复；Android 源码与阶段 1 历史判断一致。无子目录 AGENTS.md。使用 subagent-driven-development 分离后端派生查询与手机端采集，原有用户修改不覆盖。

### 协议与任务
1. Android 新增 `CommittedEditTracker.kt` 与测试：可靠完整快照（排除 composing span），成功操作才生成事件；会话 UUID + 顺序从 1 递增；清空、发送动作、输入目标重启、快照不连续与隐私过滤后重置。无法读取完整快照时只保留原始片段并标记不完整。
2. MobileEvent 增加已有服务端字段 `session_id/text_before/text_after/metadata`，metadata `edit_protocol:1,snapshot_complete:boolean`；会话仅编辑操作使用。DataCollector 保持原始事件 SQLite 双端队列与隐私开关，并过滤快照敏感文字。接入 ImeService 的 commit/delete/语音/编辑键/编辑菜单，保留现有学习与表情监听行为。
3. 后端 `/events?grouped=1`：仅协议 1 的明确会话按 user/device/package/editor/session 分组；旧事件各自独立。按组分页，匹配任何成员的搜索/类型/日期过滤后返回完整组。输出原 ActivityItem 加 `edit_count,edit_complete,edit_events`（原始操作数组，正序）、`text_after` 为最后快照；原始模式继续可用。缺失序号/快照/连续性则不完整，不重新拼字。
4. Vue 默认整段模式，支持原始模式；显示最新完整快照/清空状态，展开原始操作与前后快照；匹配被删文字仍返回整组，不因分页丢操作。
5. TDD：Android 纯逻辑及 Robolectric 集成、SQLite重开序列化测试；后端单元+真实本地 PostgreSQL 隔离事务验证聚合分页（如 pg-mem 不支持对应 SQL，不以 mock 代替）；前端真实 SFC 渲染测试。最后构建与独立审查。没有真机仍明确报告验证边界。

## 阶段 2 实现与验收记录

### 实现结果
- `CommittedEditTracker.kt` 保留会话、单调操作序号和连续快照证据，UTF-16 字符差异不从代理对中间截断。原始事件进入已有 SQLite 队列，不只保存最后结果。
- 两条 commit 入口、结束组合提交、语音、删除键/删除方法、粘贴/剪切菜单及宿主文本更新均接入；失败删除不提前入库；发送/编辑器动作及清空分开后续会话。DataCollector 继续对快照和片段执行同一隐私过滤。
- `groupedEdits.ts` 使用真实 PostgreSQL 按组匹配、计数、分页后读取完整组，只处理选中组，不把全部用户历史拉到 Node。新协议明确作用域才合并，旧记录单条保留；原始查询模式兼容。
- 行为页默认整段模式，展开完整操作和前后快照；支持原始模式；底层事件仅在原始模式显示。快速切换时旧请求不覆盖新模式。地址补全同时覆盖代表行和组内原始记录。
- `EventDelivery` 将完整 JSON UTF-8 字节控制在 1 MiB，最多 500 条；成功仅确认已发送前缀，失败/剩余事件仍持久保留，双端独立。

### 审查修正与明确放弃的方案
- 修复分组模式漏掉原有 IP 地址补全，真实 PostgreSQL 用例先失败后通过。
- 补充并修复：成功提交但快照滞后、完成英文组合漏记、表情直接清空宿主、纯组合删除被误记、语音提交/清理失败、延迟负组合回调、无组合且未知快照的空 finish。
- 尝试过根据宿主候选范围与文字相等剔除无样式 composing；独立审查指出延迟回调和正文重复字符不能证明范围属于本次组合。该推断已完全撤销，不作为现行方案。
- 无 composing 样式且无法确认时，提交前后快照均保守标未知，只保存明确提交原文；不会因为成功返回就把可能滞后的拼音快照当成正文。延迟负范围回调只有在读取到真实完整空输入框时才确认外部清空。
- 最终只读复审：此前指出的阻塞均已修正；不据此宣称全部宿主已真机验证。

### 已运行验证
- Android 原命令定向测试：CommittedEditTracker/CommittedEditPersistence/ImeServiceCommittedEdit/EventDelivery/LocalInputStore/ImeServicePersonalLearning/ImeServiceKeyEvent/VoiceInputPolicy，合计 **37 项，0 失败**。涵盖真实 SQLite 重开、MockWebServer UTF-8 分批与失败重试、隐私与组合时序回归。
- 服务端 **33 项通过**，其中真实本机 PostgreSQL 随机隔离 schema 测试 **5 项**（未修改真实用户表，结束清理 schema）；服务端构建通过。
- 前端 Activity/content-library **19 项通过**，构建通过（保留既有大包体积警告）。
- Playwright 桌面 UI 夹具验收通过：最终“晚上九点见”、展开被删“八”、全清空、真实结构旧记录、原始模式切换，console/pageerror 0。截图与报告位于已忽略的 `artifacts/diagnostics/committed-edit-ui/`；明确这是 UI mock 夹具，不冒称手机端到端。
- 390px 窄屏视觉可用性未通过：既有固定侧栏挤压正文、表格右侧截断；未扩大到全局响应式布局改造。
- 途中另一项个人词库开发的新测试曾因其模型尚未到位而编译失败；未删除或改写该任务文件，后续原命令正常编译并通过以上 37 项。
- 不改共享 Android `local.properties`，未部署服务、安装 APK 或提交用户其他改动；真机发送/切换实际聊天/系统回收后的端到端验收待连接手机。
- 最终 APK 构建 `:app:assembleOfflineDebug` 成功（59 tasks，3m9s），未安装或发布；保留原有 SDK/API 弃用、多 Kotlin daemon 等警告。最后 ADB 检查仍无连接设备。

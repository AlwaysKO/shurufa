# 个人词库添加与指定手机增量并集实现计划

> **For Claude：** 使用 superpowers:executing-plans / test-driven-development 执行；独立前端、后端、Android任务可按 dispatching-parallel-agents 并行，互不覆盖文件。

**目标：** 两个后台均可手动添加词语/拼音，将所选词增量下发到指定手机，改善实际候选；修复全选/取消全选、按钮与字段显示。
**架构：** 新增纯加法下发通道，与原始上报、主控管理快照完全分离。手机独立追加词表只做并集，不清本机/另一端已同步词，不伪造或叠加点击次数。按目标手机确认应用；不自动合并设备分组。
**技术栈：** Vue/TypeScript、Express/PostgreSQL、Kotlin/SQLite、Vitest/Robolectric。

## 本次明确规则
- 用户：本地20、线上100、手机200，两端都同步仍取去重并集；来源缺项不是删除。
- 授权增加本地和线上纯加法下发，不扩大本地备份端的停用/删除/绑定权限。
- 明确删除/停用仍走原主控独立决策，不由同步缺项触发；增量通道不携带删除决策，也不自动复活已明确停用词。
- 已有未提交T9和聊天采集改动保留。此次不打包、不安装、不发布、不改真实数据库；需要新增Android支持后方可手机实际生效，后台不得冒称旧手机已更新。
- 主验证例：三端重叠和不重叠、重试/顺序交换/小集合再同步，手机原有词和学习次数均保留；明确添加词可离线候选召回。

## 固定接口契约（所有任务共同使用）
现有前缀不变：后台 `/api/v1/dashboard/dictionary`，手机 `/api/v1/mobile/dictionary`。

1. 后台 `POST /words`：`{text,pinyin}`；新增到当前个人词库的后台词表，按汉字+规范拼音去重；真实拼音校验，不写假手机来源/点击次数。返回 `{ok:true,created:boolean}`。允许仅备份后台添加。
2. 后台 `POST /sync`：`{device_ids:string[],texts?:string[],all?:boolean,filter?:{device_id?:string,q?:string,status?:string}}`。texts与all二选一，all表示全部匹配结果（不限当前页）。只下发有可靠读音且启用的词，不复制原始次数/权重；按目标去重追加、不绑定分组。返回 `{ok:true,words:number,queued:number,skipped:number,devices:number}`，words为本次合法独立词+读音数、queued为新入队目标词对数量、skipped为无读音或不能下发的词语数。设备存在即能排队，旧端须显示待升级而不是已生效。
3. `GET /entries`继续现有响应，纳入后台手工词（`kind:'word',source:'dashboard',device_id:'',count:0,weight:0`）。默认UI按词合并查看，原始明细可切回；手工词归当前组，不伪造手机。可补 `total_words` 便于选择计数，兼容旧客户端。
4. `GET /devices`新增 `additions_supported:boolean, additions_pending:number, additions_applied_at:string|null`，原有restore_enabled/synced语义不变。
5. 手机 `POST /register`新增客户端字段 `additions_supported:true`；服务端返回 `additions_supported:true` 表示支持新协议。旧服务端不返回时客户端不调用新接口，旧手机仍可上报及主控快照，不被误标增量完成。
6. 手机 `GET /additions?after=<非负安全整数>`：`{entries:[{cursor:number,text:string,pinyin:string,preferred?:boolean}],cursor:number,has_more:boolean}`，最多500项，仅当前已鉴权手机；cursor为本批最后序号（空批等于after），按序号递增。后台入队同词同读音不重复生成序号；preferred默认false，仅手工词为true，以独立基础优先级改善候选而不伪造次数。false→true分配新序号重投，true不降级。严禁用后台缺项清手机数据。
7. 手机 `POST /additions/ack`：`{cursor:number}`，验证该设备对应已下发游标，单调确认，拒绝越界/未下发伪确认。重复确认幂等；返回 `{ok:true}`。确认只影响投递状态，不删除队列源词。
8. 服务端仅在下载游标超过已下发范围时返回409及 `code: dictionary_cursor_reset`；手机只针对该明确代码归零本目标游标重放，绝不清词，其他409照常失败。
9. 手机按目标URL分别保存增量游标；先事务写入独立追加词表，再确认，再保存游标；游标存同一SQLite数据库并按目标URL隔离，避免数据库重建而Preferences旧游标残留漏词。重试重复写去重。首次空集合不需要ack；最多每次20页，余页下个同步周期续传。下载格式/次序/游标/读音错误全批拒绝。

## 任务A：后端（独占server词库路由、对应测试、新增023迁移）
- 先写失败测试：手工重复添加/拼音不匹配拒绝；备份端纯加法允许但删除权限不变；跨组指定手机隔离；全筛选跨页；重复下发不重复计数；游标/确认/旧手机兼容。
- 新表保存后台词与按目标追加投递，原始dictionary_entry不伪造、不删除。
- 实现固定接口契约、CSRF/设备词库鉴权沿用，100000容量和500请求边界，出错事务回滚。
- `cd server && npx vitest run src/api/personalDictionary.test.ts ...`，server build；真实临时PostgreSQL可用时验事务。不迁移/发布真实库。

## 任务B：前端（独占PersonalDictionary.vue、api/personalDictionary.ts和对应测试）
- 先测试添加表单、选目标手机、当前页全选/全部筛选结果/全不选、跨页/筛选清理、错误不假成功。
- 使用既有library-button样式，拆分手机、来源、拼音、输入码、真实次数、权重字段；说明非点击记录与合并权重不能相加代表点击。
- 同步按钮不复用bind；目标手机清楚展示名称/编号、支持情况、待应用/已确认；仅备份端也可纯加法发送，管理决策权限保持原样。
- 选择按词语去重；筛选变更清选择，跨页显式全选范围不含隐藏旧筛选。
- Vitest真实Vue组件事件测试与client build；不改公共按钮系统或其他页面。

## 任务C：Android（主代理）
- 先测试两后台20/100与本机200的并集、重复同步/空批不删、次数不增加、主控快照不覆盖新增词、事务坏批回滚、v7→v8保留旧数据。
- 手机已有词包含旧换机恢复词：v8迁移及替换旧快照前将remote_word已知读音保留在追加层；choice快照仍替换不累加。策略缺项不代表启用，保留已有显式停用/删除，仅显式enabled恢复。
- 本地独立dictionary_added_word表按text+pinyin去重；候选查询纳入此表，不进入本机来源导出，避免恢复再次上报。
- 纯加法下载在两个目标都运行，仍不从备份目标应用主控停用/删除快照；新旧协议协商、游标隔离和失败重试。
- 指定词加入候选时依据真实读音和已有拼写边界；可提高明确添加词的基础优先级，不伪造历史权重；锁音/分段不注入违反约束的新候选。
- 定向Robolectric/T9/隐私回归；不assemble APK、不安装。

## 收尾
- 审查并测试三层协议一致性、只增不删及源记录去重。记录精确验证范围和旧端升级前无法应用的事实。
- 更新项目目标规则与Vault索引，注明本次用户并集要求取代备份端禁止任何下发的局部规则，但不改变独立删除决策边界。

## 实现与审查记录（2026-09-22）

- 已实现后端023迁移、手工词表、按设备增量队列；前端手动添加与目标手机选择、当前页/全部筛选结果/全不选、分列和按钮样式；Android v8追加表、独立URL游标、两端能力协商与重试。未部署真实服务、未执行真实数据库迁移、未打包/安装/提交。
- 先执行新增失败测试再实现。Android首轮夹具用了在Robolectric API28上不适用的SQLiteOpenHelper `use`，修正为既有`withStore`后，三项分别因未下载/未召回/错误成功返回而失败，之后转绿；不是把夹具错误当功能RED。
- 独立审查发现并修复两条旧路径：旧remote_word在较小主控快照下丢失、空策略列表使明确停用词复活。两者均新增行为测试先失败后修复。扩大回归时发现部分旧库夹具缺少remote表，v8升级先用IF NOT EXISTS建立依赖表，再保留已有恢复词，不改聊天数据。
- 真正SQLite触发器中途失败回滚、坏批全拒绝、ACK失败重放、只针对专用409复位、20页续传、两端重叠/交换顺序/preferred只升不降、v7原词与计数保留均有正式测试。320例为三组无重叠的200+100+20；有重叠则去重，不承诺固定320。
- 手工词只提高拼写匹配后的基础优先级，不伪造点击，不保证永远压过更强的真实近期学习证据。此次候选范围沿用用户九宫格入口（3–30数字码），不声称已扩展26键或1/2键单字召回；锁音/分段仍只重排原生已有候选。
- 真实Chromium、独立Vite、模拟API检查360/390/768/1440px。先发现768px查询按钮超出视口（right805>768），仅本页增加flex-wrap后四种尺寸非表格控件均无越界，表格独立横滚；按钮背景/圆角/高度实际生效，设备选择与模拟同步请求正确，无页面脚本错误。截图与脚本在本机 `/tmp/shurufa-dictionary-visual/`，不入Git、不作为真实手机同步证据。
- 协议剩余边界：同一URL换库、队列序号又恰好未倒退时，没有服务端epoch不能自动识别全部重放；本次只实现明确`dictionary_cursor_reset`时安全重放。手机已有词不会因此删除。未知读音记录不猜拼音、不自动下发；尚未成功学习的历史词不会凭空恢复。

### 验证命令与已确认结果

- `cd client && ../server/node_modules/.bin/vitest run tests`：18个文件、189项通过；浏览器局部样式修复后个人词库14项复测通过。`npm run build`通过，保留已有大chunk警告。
- `cd server && npx vitest run src/api/personalDictionary.test.ts src/lib/dashboardAuth.test.ts`：31项通过、2项PostgreSQL专属测试跳过；`npm run build`通过。
- 后端子任务在独立临时PostgreSQL `127.0.0.1:55439`、唯一随机schema执行24/24通过，包含容量超限整体回滚、501条分页/未投递ACK拒绝、迁移重放；stdout为子任务会话工具输出47b0ed，未另存Vitest日志。临时集群 `/tmp/shurufa-dictionary-pg.5XiI4F/data` 已关闭，未连接真实库。服务日志不是测试报告。
- Android：`source /home/ko/android-tools/env.sh && cd android/YuyanIme && ./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand --tests 'com.yuyan.imemodule.data.collect.*' --tests 'com.yuyan.imemodule.data.completion.*' --tests 'com.yuyan.inputmethod.data.*' --tests 'com.yuyan.inputmethod.util.*' --tests '*ImeServicePersonalLearningTest'`，最终44套件203项通过，0失败/错误/跳过，包含新增DictionaryAdditionsTest 10项。日志`/tmp/shurufa-additions-final2-android.log`，退出0；仅保留既有三处Kotlin arrayOf推断警告，未因此改动无关代码。
- `git diff --check`通过；最终只读复审无剩余阻塞问题。项目AGENTS个人词库核心目标/并集规则及Vault索引已核对，验收结果不等于部署或手机升级。

### 后续本地运行报错修复（2026-09-22）

用户反馈 `42703: dictionary_device.additions_supported does not exist`。只读核对确认本地 `localhost:5432/personal_ime` 尚未执行023，新代码已被开发服务加载，而启动入口仅创建连接池、不自动迁移；此前“未执行真实数据库迁移”的状态是当时事实。

本次仅在上述本地数据库中以事务执行 `023_dictionary_additions.sql`（5秒锁等待/30秒语句超时），未重跑其他迁移、未连接线上数据库。新增4个additions字段、两个词表、序列与索引均已核实；迁移前后设备1条、原始词库记录5103条、策略0条保持一致。在回滚事务中执行原报错形态的UPDATE成功，未改手机能力状态。此补丁不改候选算法、不打包安装，也不意味着线上数据库已经迁移。当前开发服务下一次请求即可使用新增结构。

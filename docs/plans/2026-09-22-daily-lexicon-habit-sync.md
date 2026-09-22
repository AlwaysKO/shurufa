# 日常词库与一键个人习惯同步实现计划

> **For Claude：** 使用 superpowers:executing-plans 或 subagent-driven-development，逐任务遵循 test-driven-development、requesting-code-review、verification-before-completion。当前分支开发，不建 worktree，不自动提交/安装/发布。

**目标：** 内置有明确来源的日常常用词；后台手工添加词语及拼音；将当前个人词库已保存的真实选词习惯和手工词，一键增量同步到用户选定手机，让候选实际改善。

**架构：** 公共词库为构建期生成、随 APK 离线加载的资产；不当作个人点击。复用后台手工词与现有增量投递通道，增加独立的有来源选词习惯投递，避免借“绑定整组”完成一次同步。Android 保留本机学习并按原始来源去重远端证据，真实应用后 ACK。

**技术栈：** Kotlin / SQLite / Rime、Node.js 资产生成、Vue / TypeScript、Express / PostgreSQL、Vitest / Robolectric。

## 已确认需求与本轮范围
- 用户已确认：同步包括真实次数、衰减权重、最近使用记录和后台手工词，不只是传词语列表。
- 不做 cuobez/286239 容错，不修改表情推荐、采集、字号及签名。
- 本地与线上均为纯加法，不因来源少词删除手机数据；不虚构点击、不把公共频率导入个人次数。
- 指定手机操作不自动绑定其他手机、不更换主控；排队与手机应用分别显示。
- 未明确授权部署线上、安装 APK、清理数据或提交 Git。

## 已查明的缺口（2026-09-22）
1. `tools/data/t9-lexicon-sources.md`：现有补充词库来自 jieba，130860 条，自动生成拼音；这是已有词库，不是本轮新增成果。
2. `server/src/api/personalDictionary.ts` `/sync` 目前仅取有效 pinyin 的词条，投递 text/pinyin/preferred，真实 choice 行（pinyin 为空）不随这个按钮下发。
3. 当前主控的管理 snapshot 可以恢复习惯，但会覆盖自己的 remote 表；不能拿它直接代替双端增量同步。
4. `LocalInputStore.effectiveChoices` 当前本机/远端 UNION ALL；增加习惯通道时必须跨通道、跨后台按原始来源去重，否则把同一次选择算两遍。
5. 雾凇/白霜根许可证已查为 GPL-3.0。仅发现可下载源不代表完成具体数据来源和分发义务核验；不得改贴 MIT 或直接混入未核实数据。

## 任务 1：固定日常词库来源与质量基线
**文件：** `android/YuyanIme/tools/data/t9-lexicon-sources.md`、`tools/generate_t9_lexicon.mjs`及其测试；新增有来源清单、生成器/资产按最终格式命名。
1. 对照雾凇/白霜的基础词与扩展词文件及逐文件声明，锁定提交、哈希、来源和适用许可；保留许可/改动标记及可复现生成材料。无法核实的部分排除，不用“网上公开”替代授权。
2. 先建立聊天、餐饮、购物、出行、家庭、办公的独立验收词样本；检查现有覆盖及默认前5候选位置，记录基线。
3. 编写生成测试：明确拼音/多音字、不合法条目拒绝、去重确定性、非负有限频率、来源追踪、词频尺度统一。先运行失败，再实现。
4. 不简单叠加多个不兼容词频；保留原库回退，采用已核验高质量读音和常用词优先规则。公共资产不写 learned_input，不使“新增词数”代替候选质量。
5. Node 生成测试及 Android 离线资产/候选测试通过；比较覆盖、候选位置、包体和查询耗时。原生索引、966最近选择、长词过滤仍需通过。

## 任务 2：后端真实习惯的增量投递
**文件：** `server/src/api/personalDictionary.ts`、对应测试、下一空闲序号数据库迁移。
1. 先写失败测试：当前个人词库全量一键同步包含手工词和 choice；仅指定手机可取；旧手机不伪报已支持；未知读音的有效同码证据仍可传但不得猜拼音。
2. 为 habit 投递增加原始 device_id、code、text、真实 count/weight/last_used、来源版本和服务端游标；协议支持能力独立声明，旧词条协议继续工作。
3. 同来源记录按版本幂等更新，不累加快照；相同快照重按按钮不制造新次数。分页、容量上限、事务回滚、游标重放及严格 ACK 门禁复用已有模式。
4. 保留本机来源标识，重复传回来源手机不能增加本机证据；停用/删除词不被增量通道复活。
5. 用独立 PostgreSQL schema 跑实际 SQL 回归，包含两个后台同源记录、不修改真实手机状态。迁移是否在本地/线上执行分别报告。

## 任务 3：Android 幂等合并并改善实际候选
**文件：** `PersonalDictionaryModels.kt`、`PersonalDictionarySync.kt`、`LocalInputStore.kt`，以及 collect/completion 相应测试。
1. 先写失败测试：本机200词 + 两端20/100词仍为并集；相同 source-device/code/text 经两个后台和旧 snapshot 到达只计一次；更新同源证据替换、不重复相加；来源缺项不删除。
2. 新增独立增量习惯存储，防止旧管理 snapshot 清空新增来源；有效证据合并时以原始来源唯一标识去重。不同来源设备的真实记录才允许合并权重。
3. 不重上传远端恢复的次数为本机点击；selfDeviceId 的远端副本不与本机证据叠加。保留本机更新优先与旧数据迁移边界。
4. 收到一页数据并事务落盘后才能 ACK，异常/断网不确认；恢复可重放；来源时间不改成下载时间以免误造“刚选过”。
5. 验证同步后候选位置改变、重启保留、不产生新点击；锁音、分段、全键原行为、隐私门禁、三键近期规则回归。

## 任务 4：后台一键入口与实际状态
**文件：** `client/src/views/PersonalDictionary.vue`、`client/src/api/personalDictionary.ts`、`client/tests/personal-dictionary.test.ts`。
1. 添加“同步全部习惯和手动词”入口，只要求选定目标手机；不依赖当前分页/选中词，不被搜索筛选意外缩小范围。原“同步所选词”保留。
2. 操作前清楚显示目标；不自动绑定组。真实次数与手工词分开显示，手工词为无次数。
3. 展示待接收、需升级、已应用时间；词和习惯两个通道均已应用才显示整体完成。失败可重试，不宣称即时强推成功。
4. Vitest 测入口请求、分页/筛选、重复操作、离线/旧版本；浏览器验证按钮和状态、窄屏布局。

## 任务 5：验收与交付
- `node --test android/YuyanIme/tools/generate_t9_lexicon.test.mjs`，新增生成器测试按实际路径补充。
- `cd server && npx vitest run src/api/personalDictionary.test.ts && npm run build`，另执行独立 PostgreSQL 集成验证，不以 mock 代替 SQL。
- `cd client && ../server/node_modules/.bin/vitest run tests/personal-dictionary.test.ts && npm run build`。
- Java17 环境，定向 Gradle 测试所有修改的 collect/completion/inputmethod 类；需要时以临时 init script 分批控制内存。
- 审查后 `:app:assembleOfflineDebug`；按原签名交付 E:\Projects\shurufa-android\apk，核对版本、源/目标 SHA256 与非 testOnly。
- 更新 `docs/guides/personal-dictionary-sync.md` 中仍写“备份端完全不下发”的旧说明，与新增纯加法规则区分。
- 真机测试由用户操作，不自动发送、安装或清数据；未实际验收的部分明确列出。

## 状态
- 代码、词库与本地迁移已完成；线上未部署、手机未安装，本轮交付验证见下。


## 实施与验收记录（2026-09-22）

### 公共词库
- 最终采用 AOSP PinyinIME 固定提交 `49aebad1c1cfbbcaa9288ffed5161e79e57c3679`，保留 Apache-2.0 原 NOTICE；没有导入来源义务未核实的雾凇/白霜数据。原始压缩数据、生成器及来源清单均保留，可离线复现。
- 按源频率阈值、字数、有效音节筛选 30,242 条读音 / 30,168 个词，较原 jieba 资产新增 8,093 个词；共有词使用源明确读音，公共频率按重合词中位比例校准，不作为个人点击。
- 新资产 139,027 条读音，压缩后 1,386,415 字节（增加 76,693 字节）。SHA256：`62d26308b7b6f321a0138ba9416997cb84854f2e905abca147f3b165364238f0`；独立重建与实际资产哈希一致。
- 42 个独立日常样本覆盖从 28 提升到 33，补齐买菜、洗碗、多少钱、收货、到家；仍缺部分口语短句，不能将所有新增源高频词宣称为纯日常词。
- 实际候选测试发现洗碗在排序前被默认 8 条频率召回截断；数字通道扩大至 32，拼音通道不变、学习词仍可绕过普通预算。测试验证 11 个日常词在第一页，其中洗碗不承诺前五。
- 新库中“用的是”频率高于“用得上”，同步更新原有对应首位断言，同时保留“用得上”可选、错误拼接过滤、原生索引与翻页断言。

### 习惯同步与后台
- 新增独立 habit 投递及 ACK 通道和 SQLite v9；以原始设备、code、text 和来源版本幂等合并，不把下载时间当作使用时间、不回传远端次数为本机点击。
- 新增一键同步全部习惯和词语，明确选定手机，不受当前搜索/分页影响、不自动绑定；词和习惯分别显示能力、待处理数与应用确认。
- 本地 PostgreSQL 已执行 `025_dictionary_habits.sql`，迁移前后原词条 5,204 / 设备 1 均不变。真实运行接口设备 GET 返回 200 和新能力字段；未选择设备的 sync-all 返回 400，没有向用户手机排队测试数据。
- 线上数据库/API/后台没有部署。旧手机须升级并启用个人词库同步；后台排队不是手机已应用，最终输入体验尚待用户真机验收。

### 已核对的验证与审查
- Node 生成与资产测试 4 项通过；红测确认缺词、修复召回前洗碗不可选，随后实际候选转绿。
- 后台前端 18 文件 / 197 项通过，构建通过（已有大 chunk 提示）；服务端定向 33 项通过 / 3 项需独立 PostgreSQL 的测试跳过，TypeScript 构建通过。另由实现代理使用临时 PostgreSQL 跑完整个人词库 SQL 测试 27 项通过。
- 浏览器实际组件配合隔离 mock API，在 1440/768/390/360 宽度验证目标请求、筛选不缩小同步范围、待确认提示与控制区布局；人工查看 390 宽截图，无脚本异常。此项不是实际手机端到端验证。
- 需求审查与质量审查通过；最后数字召回 32 的增量审查也无阻塞。超大词库/多目标 sync-all 的逐条 SQL 性能仍有优化空间；尚未做真机冷启动、耗电和输入延迟量化对比。
- 保留其他并行会话的采集/素材改动，未提交或推送 Git，未安装或清理手机数据。

- 最终 Android 定向回归：28 个测试类 / 153 项，0 失败、0 错误、0 跳过。覆盖 completion、T9、输入状态、服务学习与 Dictionary 同步；SQLite 迁移测试断言已随 v9 更新，原数据保留验证不变。日志 `/tmp/shurufa-daily-android-green.log`，本次 XML 副本 `/tmp/shurufa-daily-final-results`。

### 本轮 APK 交付
- `:app:assembleOfflineDebug` 成功，版本从 APK 核对为 `20260922.17` / `2026092217`，包名 `com.yuyan.pinyin.offline.debug`。
- 唯一推荐文件：`E:\Projects\shurufa-android\apk\shurufa-2026-09-22-v20260922.17-2026092217-debug-cc1123bc.apk`。
- 构建源与 E 盘文件 SHA256 一致：`cc1123bc71d918d555e87cfc19cdc175a315f219dd4f0a7e99bde255840afc24`。
- API 23/27/28/32/36 原签名均验证通过，非 testOnly；APK 内新词库 SHA 与上述源资产一致，完整 AOSP NOTICE 已入包。
- 未安装到手机。未进行线上部署和实际手机跨后台同步验收；仅交付本地验证通过的分享安装包，不代表全部输入问题或所有日常短句已解决。

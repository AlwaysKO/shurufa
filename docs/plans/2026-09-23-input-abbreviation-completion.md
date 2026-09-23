# 三字简拼与固定长句前缀补全实施计划

> 按 Superpowers executing-plans、test-driven-development、requesting-code-review 和 verification-before-completion 执行。用户已于本轮“继续”批准上一轮调查中的推荐方案。沿用当前分支，不提交或推送。

**目标：** 离线 `559` 可选择“就可以、良口镇”，`feiliuzhix` 及对应九宫格码可选择“飞流直下三千尺”，保留原有防乱词、原生索引、分段与个人学习保护。

**架构：** 在现有候选链中增加按真实读音建立的三字简拼索引和固定表达前缀索引；匹配证据区分原有拼写、三字纯简拼及长句前缀补全，供显示、分页和选择使用。锁音/分段继续尊重已有输入约束。

**技术栈：** Kotlin、Android/Rime、SQLite、Python 构建期索引、JUnit/Robolectric、现有 Gradle 签名交付。

## 1. 索引与匹配测试先行

- 新增 `yuyansdk/src/test/java/com/yuyan/imemodule/data/completion/InputCompletionTest.kt`，先用现有接口证明 `559` 和 `feiliuzhix` 失败，并保留 `649439`、`284269` 反例。
- 新增 `tools/test_input_completion_index.py` 与 `tools/build_input_completion_index.py`。锁定公共原始表校验和，输出可按码查询的资产及来源统计；原始大表下载放临时目录。
- 索引选取按通用来源/读音/长度规则，禁止生产代码硬编码三个目标。记录同码排序证据、资产体积及冷加载开销。
- 验证：Python 测试先红后绿；资产包含目标及留出词，拒绝坏读音/未锁定源。

## 2. 三字简拼与长句匹配

- 新增 `data/completion/InputCompletionIndex.kt` 与 `InputSpellingMatch.kt`（最终命名可随最小实现调整）。
- 三字纯简拼需三个音节首字母恰好覆盖三码；不放开任意内部混拼，不修改原有四字以上纯简拼保护。
- 长句前缀至少三个完整音节并开始第四音节，严格对齐已输入字母/数字，限制补全数量。保留真实全读音及已输入拼音显示，不伪造未输入按键。
- 验证：全键/数字前缀、追加错误键撤回、退格恢复、短前缀抑制、多音词及不符合边界反例。

## 3. 候选入口、分页、提交和个人学习

- 修改 `OfflineT9Candidates.kt`、`PersonalCandidateRanker.kt`、`RimeEngine.kt`，让新候选独立召回，统一首屏/后页资格和拼音行，保留原生索引。
- 按需要修改 `PersonalWordReading.kt`、`LocalInputStore.kt` 与 `T9CommitTracker.kt`：真实短码选择持久化；个人词能够按新输入形式召回；只在成功上屏后学习，不复制计数到相关码。
- 验证：原生目标缺失仍可召回；首屏/后页与选择索引；锁音不注入；相同输入学习后重开数据库仍有目标，失败提交不学习，长句前缀提交不丢后续输入。

## 4. 回归和独立审查

- 运行 completion/collect/拼写/键栈/原生候选相关测试及现有 305 条公开候选、16 条长句快照回放，分析排序变化。
- 使用 requesting-code-review 的独立审查检查边界、来源、索引、性能和学习；修复重要问题后重跑对应检查。
- 验证：新需求达到目标；既有保护通过；逐键查询和内存开销可解释。应用层回放不冒充原生/真机验收。

## 5. 交付

- `source .runtime/macos/android-env.sh` 后运行 `:app:assembleOfflineDebug --offline --console=plain`。
- 核对 APK 真实版本、包名、testOnly、固定证书 SHA256 和交付文件 SHA256；交付本机 `apk/` 路径。
- 本机不能访问 Windows E 盘时明确说明缺口，不自动安装、不改用户词库。回写执行结果及未验收项。

## 执行记录（2026-09-23）

- 已实现带原始读音的三字简拼索引、固定长句前缀召回、实际输入拼音显示、分页资格及个人词召回。生产逻辑没有逐条硬编码用户示例。
- 索引 5,911,794 字节，160456 条三字读音、2820 条固定表达。来源、自动注音边界及生成步骤见 `android/YuyanIme/tools/INPUT_COMPLETION.md`。
- 用户示例先在原实现上复现失败，再通过新实现；另补逐键到完整码的回归，修复最后一键导致补全消失的问题。
- 独立审查发现的个人第三长句被提前裁剪、全键盘未输入尾音显示问题已修复；最终整词衔接复审无阻断发现。
- 198 项相关 JVM/Robolectric 回归通过（0 失败/跳过）；随后新增一项全键短前缀学习测试，包含它的 8 项集成测试全部通过。共 199 项不同测试。索引生成器 4 项测试通过。
- 同时保留旧反例、无注释调用的单字顺序、全键原生注释保护、原生索引、锁音分段、真实学习计数及数据库重开验证。
- 回放与桌面耗时验证只覆盖应用层及既有原生快照，不代表手机键盘端到端耗时或真机验收。未自动安装、清数据、提交或推送。
- 最终 321 条既有原生快照回放：0 首选变化、0 目标排名退化；仅 `962` 一条中间态增加低位简拼候选，前八项未变。
- 最终桌面 JVM（25 次热查询）：`559` P50/P95 为 7.76/8.28 ms，最大简拼桶 `999` 为 23.07/23.97 ms；数字长句前缀 1.54/1.81 ms，全键前缀 0.63/0.93 ms。索引读入与构造 6.95 ms；无真实个人数据库和原生输入线程，因此不能外推为手机延迟。
- 实际打包使用 `:app:packageOfflineDebug --offline`，避免 Mac 上 assemble 的 Windows 复制任务；本次临时 Gradle 参数将本机 localhost 调试地址恢复为仓库默认 `https://my.dog8ball.com`，未改全局配置。
- APK：`/Users/pj/project/shurufa/apk/shurufa-2026-09-23-v20260923.13-2026092313-debug-b60a0c56.apk`；包名 `com.yuyan.pinyin.offline.debug`；版本 `20260923.13` / `2026092313`；大小 139915104 字节。
- APK SHA256：`b60a0c5616f247a3975b7794ae5cfb8815a9632c0e42f0527b61c3d8875c6c01`。交付副本与构建产物一致；包内补全索引校验和与源资产一致且未压缩，可用于 mmap。
- API 23/27/28/32/36 签名核验全部通过，证书仍为 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`；非 testOnly。
- Windows `E:\Projects\shurufa-android\apk` 在本机不可访问，未完成该目录复制。尚未在用户手机安装或验收。

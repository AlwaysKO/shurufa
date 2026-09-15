# 九宫格末音节补全：根因与隔离实验

2026-09-14。**实验有改善、有回归，发布门禁失败；未修改正式 Android 代码、assets、.so，未安装 APK 或提交。**

## 根因

使用上一批固定公开词库和原版 [Rime 1.11.2 syllabifier.cc](https://github.com/rime/librime/blob/1.11.2/src/rime/algo/syllabifier.cc)。原版仅在 `enable_completion_ && farthest < input.length()` 补全；九宫格碰巧可被其他完整音节拆完时，不再补全末音节。

`graph_probe.cc` 直接调用未改源码库，分别开启/关闭补全：
- 5个末字短码目标读音路径均不存在，开关前后图完全一致。
- 7个完整码目标路径均存在。
- 小写 `zenm` 控制：关闭补全无 `zen me` 路径、开启后有；证明补全功能本身确实生效，不是配置遗漏。

`9366 → WDMM` 的原图有 `ye'mo`、`zen'o`，没有 `zen'me`。因此仅调词频/学词，无法把不在搜索图中的目标排到第一。这是开源隔离引擎的证据，不能断言旧闭源 JNI 实现完全相同。

## 单变量试验

同公开 table/prism、无语言模型、空实验用户库。只在上游 `syllabifier.cc` 的临时副本补充末尾 T9 音节路径，保留原 completion penalty，不调字典/词频，不加白名单。构建为 `/tmp/shurufa-prefix-root-cause/terminal-prefix-experiment.so`，只对指定主机进程设置 LD_PRELOAD，**不是修改或破解手机的闭源库**；原源码和基线库哈希见 manifest。

- V1：剪枝后补边，4个短码恢复；“用得上”的 `de` 已被剪去，仍失败。保留 `terminal-prefix-v1.patch` 与失败记录。
- V2：剪枝前补边，末端 completion 可保留，12目标路径全部存在；`terminal-prefix-experiment.patch` 是实际运行版本。
- 原有图边及权重在12反馈+1控制中保持；补全关闭、小写控制不变。新增的非末端边是剪枝时恢复的正常音节边，不是内部缩写。**只验证这些图，不声称所有输入的图不变。**

## 候选与学习结果

| 指标 | 原版无模型 | V2无模型 |
|---|---:|---:|
| 12反馈码冷启动第一 | 6 | 9 |
| 12反馈码前5/前100召回 | 6 / 6 | 10 / 10 |
| 原始7码冷启动第一 | 3 | 5 |
| 287正向第一 | 128 | 212 |
| 287正向前5 | 135 | 228 |
| 287正向前100召回 | 137 | 231 |
| 119完整码第一 | 99 | 98 |
| 168末字前缀第一 | 29 | 114 |
| 学习3次重启后12码第一 | 7 | 12 |

冷启动具体表现：充电宝（包括两个短码）、怎么（9366/93663）、真的吗、玩漂流、我饿了均第一；用得上短码第二、完整码第一；你发货吧两个码均前100未找到。

学习使用7个独立用户库，每目标完整码真实选择3次，初次不在候选时逐段选择并核对实际提交；不直接写数据库。每个目标随后另起进程：**12/12输入目标第一**，5个原先缺失的短码也包括在内。此结论仅适用于实验语料及原生进程，并非迁移/Android学习验收。

### 必须保留的回归

正向新增85项首选、退化1项：完整码 `6464842678727426`“明天去爬山”原来第一，现在前100未找到，第一变成“明天去爬上”。4个不期望项单独统计，没有把“夜魔”“总额而”掉出第一当作正向退化；也不能据此认为其他怪词已消失。

该目标读音图仍存在，原边未删除，见 `regression-graph/`。上游 [script_translator.cc](https://github.com/rime/librime/blob/1.11.2/src/rime/gear/script_translator.cc) 仅保留一个 `sentence_`，取出后清空。**推断**：补全扩展增加歧义后，后续单句搜索/排序可替换旧整句；保留图不等于保留原候选。下一步需分别保留完整候选与补全候选，或评估多结果整句搜索，而非补词/按词性放行。

## 操作与验证边界

- 4680个1–4位码输出完整，无全部语义标注。
- 291/291追加再退格恢复；164组第二页首项和index100一致，127组NA。
- 锁拼音仍是 C API 构造输入；分页不含后页选中提交；主机时间不等真机性能。
- 原始输出 `raw/*.gz`，不含真实个人记录或实验userdb；`summary.json` 严格校验案例/计数/布尔/训练和重启名次，不按exit0判通过。

## 复核

```bash
D=artifacts/diagnostics/2026-09-14-t9-prefix-root-cause
python3 "$D/summarize.py"
python3 -m unittest discover -s "$D" -p test_graph_invariants.py
# 当前必须失败：正式候选门禁，而不是将已知回归跳过
python3 -m unittest discover -s "$D" -p test_candidate_gate.py
```

原生路径回归：`test_terminal_paths.py` 在原版上5个子案例失败（red.log），V2通过（green.log）。运行时须提供 PROBE、BUILD、LD_LIBRARY_PATH，实验臂另加 LD_PRELOAD；不能把环境漏掉后的失败当源码结果。图存档由 `trace.py` 生成。

临时构建（使用前批已编译原版，先将原 `src/rime/algo/syllabifier.cc` 复制到临时副本并应用归档patch，不改原目录）：

```bash
R=/tmp/shurufa-rime-model-audit
c++ -std=c++17 -O2 -fPIC -shared \
 -I"$R/source-build/src" -I"$R/librime-1.11.2/include" \
 -I"$R/librime-1.11.2/src" -iquote "$R/librime-1.11.2/src/rime/algo" \
 -I"$R/host/usr/include" /tmp/shurufa-prefix-root-cause/syllabifier.cc \
 -L"$R/source-build/lib" -L"$R/host/usr/lib/x86_64-linux-gnu" \
 -Wl,-rpath-link,"$R/host/usr/lib/x86_64-linux-gnu" -lrime -lglog \
 -o /tmp/shurufa-prefix-root-cause/terminal-prefix-experiment.so
```

使用 `-iquote` 是为了避免上游 `algo/strings.h` 遮蔽系统头；没有更改源算法来绕过编译错误。探针沿用前批源码：候选输入每行 `数字码<TAB>标签`，必须严格确认 DONE 和案例数量。学习脚本 `run-learning.py` 仅适用于上述本机隔离目录，目录须全新，不复用已有用户库冒充冷启动。

## 独立复审补充：不能忽略新增乱串

复审从4680短码中发现新的无意义首选（原版也有乱串，并非原版已正确）：

| 数字码 | 原版首选 | 实验首选 |
|---|---|---|
| 3522 | 饿啦啊 | 饿喇叭 |
| 3922 | 饿呀啊 | 饿牙齿 |
| 3923 | 饿哇饿 | 饿雅典 |
| 3962 | 饿我啊 | 饿我不 |

细节见 `short-risk-review.json`。这是结果后的人工复查证据，不加入原287正向分母，不当作事前留出，也不据此生成生产黑名单。**补全同时扩大无意义组合空间，是另一项阻止发布的原因。**

主机单次语料耗时P95约1.88→5.01ms、短码P95约0.237→0.565ms（输入整串并取得页面，不包含用户操作）；无重复性能实验、无手机测试，不能宣称性能达标。

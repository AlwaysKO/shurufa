# 九宫格有限语料全集与状态预演

日期：2026-09-14。诊断测试，不是应用实现或最终批准的输入规则。

## 范围与不可混用的指标

- 从已安装 `20260914.08` APK 的 `t9_lexicon.tsv.gzip`、`chinese_domains.tsv` 枚举本地补充词库，不是 Rime 二进制词库的全部条目，更不是所有可能中文短语。
- 同码多个目标按一次查询分别记录名次，不要求所有同码词都第一。原生保留前 100 候选，后处理统计完整 firstPage；名次 -1 只代表这个窗口未见，不代表后续所有页没有。
- `phrases.json` 为预先固定的 98 个正常表达及人工给定读音；`phrase-cases.tsv` 为完整码和合法末音节前缀共 266 组。词组合理不等于相同按键只能对应这一种合理表达。
- 没有用这些短句去补应用词库，也没有修改评分或过滤参数。
- 空个人历史原始/后处理基线与模拟学习结果分开。不能用词库内高召回率掩盖词库外短句误删。

## 文件

- `corpus.json`、`lexicon-cases.tsv`：有限全集的计数与同码目标。
- `T9Corpus.java`：设备端原生/后处理对照。每行输出候选前五、目标名次与首项删除标志；日志有 DONE 完成标志。
- `T9Transitions.java`：266 组末尾追加 2 再退格的原生候选/组合恢复，及可到达第二页时 CandidateSelection 原生索引一致性。
- `T9Learning.java`：独立 SQLite probe.db，逐例重置，向完整码注入 3 次模拟明确选择，再关闭/重新打开数据库查询完整/短码。查询不应增加次数。不是实际点击候选、InputConnection 上屏或上传验收。
- `T9Segments.java`：用实际 KeyRecordStack 按键记录和原生候选接口，观察两段选择时的原始码与提交值；不向真实宿主提交。最多查找 20 个原生页。
- `analyze.py`：检查输出与输入逐码/逐目标一致，缺少或重复时拒绝报告完成；汇总窗口召回，不作自动语义判官。
- `test_analyze.py`：汇总器的缺项、重复、目标集及同码统计保护测试。

## 运行

复用 ../2026-09-14-t9-baseline/README.md 的 app_process 环境，每个进程使用独立目录，禁止指定真实用户 Rime 或 SQLite 目录。Java 编译需同目录的匿名内部类一并交给 d8。

```text
CLASSPATH=<dex>:<installed.apk> app_process -Djava.library.path=<isolated-dir> /system/bin T9Corpus <isolated-dir> <installed.apk> <cases.tsv> <results.jsonl>
```

T9Transitions、T9Learning 参数相同，学习 TSV 第三列为完整学习码；T9Segments 仅需前两个路径参数。诊断类反射依赖该调试版本接口，不保证任意发布版通用。不得复制其他版本的二进制后沿用本次 APK 哈希。

```bash
python3 -m unittest discover -s artifacts/diagnostics/2026-09-14-t9-rehearsal -p test_analyze.py
python3 artifacts/diagnostics/2026-09-14-t9-rehearsal/analyze.py <cases.tsv> <results.jsonl或jsonl.gz> <summary.json>
```

## 未覆盖，不能宣称已验收

全体自然语言语义、所有生僻/多音读音、所有内部简拼、锁拼音后的所有路径、真实个人历史、其他设备/OEM、实际编辑器提交失败与撤销、繁体/英文全路径。既有 119 项 JVM 回归只提供其既有范围的证据。

## 本轮最终状态

有限词库全集已完成：72,800 条取回记录 + 18,020 条续跑记录，合并后与 90,820 码/131,169 目标逐项核验一致。JSONL 的 gzip 完整证据见 lexicon-results.jsonl.gz，摘要见 lexicon-summary.json，哈希与中断历史见 run-status.json。中断检查点已合并，不再保留重复副本。

测试途中设备断开，随后手机应用升级为 20260914.09；续跑使用保存在测试目录的 08 版 APK 快照，设备端 SHA-256 与初次快照一致。结果全部归属于 08 基线，不混入 09，更不声称已验收 09。

短句、退格/翻页、模拟学习和短码有限全集已分别完成，不能加总为互不重叠的“所有词组测试数”。临时原生库、APK 与真实个人库未加入仓库；证据只有本轮人工/公共词库测试输入。详见 docs/plans/2026-09-14-t9-rule-contract.md。

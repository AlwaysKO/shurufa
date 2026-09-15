# Rime 原生引擎与语言模型隔离调查

日期：2026-09-14。这里不是可安装修复包，也不是语义效果验收通过。

## 当前 Android 事实

- 原生库及词典哈希见 manifest.json；ARM64 库包含 `1.11.2` 字符串（版本线索，尚未通过其运行时 API 读取）。
- 九宫格方案以 YAML 文本内嵌在 libyuyanime.so，提取快照为 t9-embedded.schema.yaml；使用 script_translator、pinyin 字典、t9_pinyin prism。
- 内嵌方案未配置 grammar/language 或 contextual_suggestions；随包 assets 未见 .gram 模型。发现 Grammar/Poet 通用符号不等于模型已经注册/加载。
- 字典头为 `Rime::Table/4.0`，九宫格索引为 `Rime::Prism/3.0`。
- 上游 yuyansdk 当前仓库不含原生 C/C++ 实现；README 区分公开框架与预编译闭源库条款。今天上游说明不自动追溯适用于仓库旧包，实际授权仍需按取得版本核实，不绕过保护。

## 实际运行 1：旧开源引擎直接读词库

本机 Ubuntu 可取得的 librime 1.7.3（包版本 1.7.3+dfsg3-2build2），只下载解包到 /tmp/shurufa-rime-model-audit/host，不安装系统包、不换手机库。

host_probe.c 使用 C API、复制同一 table/prism、独立空 userdir、page_size=100，双方均移除无关 OpenCC 过滤器。尚未开始候选比较：创建会话时 MARISA_NULL_ERROR，详见 host-without-model.log。因此没有生成 291 组结果，不声称这是模型效果不好；这是此版本/词典组合不兼容的证据，不能直接替换。

## 实际运行 2：单独调用 Octagram 模型接口

grammar_probe.cc 调用发行包中真实的 Grammar::Query，不重写算法、不修改应用。模型来自 lotem/rime-octagram-data 的 hans 固定提交，下载体积 40,925,228 字节，Git blob SHA-1 和 SHA-256 均校验，见 manifest.json。模型仅留本机 /tmp，未加入 APK。

- pairs.tsv：事先固定的 24 个上下文/词语组合，包含用户目标和已知坏例。
- model-pairs.tsv：启用实际模型的原始分数与单次耗时；日志确认成功加载 double-array image。
- missing-model-pairs.tsv：同插件但缺模型的负控制，24 项均回退 -12；错误日志明确记录加载失败。
- 已启用模型时，“真的 + 吗”“玩 + 漂流”得到非回退分数；但“本 + 饿”“有点 + 累计”等也得到较高分，“我 + 饿了”仍为 -12。

**不能据此设置语义放行阈值。** Query 是组句过程中的一个搭配增量，含短后缀、句尾等证据，不是整句话“正常概率”。这里人工给定切分，不是解码器实际词边界；没有加入词频及完整候选搜索，不能用这些数值直接排名整句，也不能据此判定完整 Rime 模型一定选错。

耗时是桌面冷调用观测，不是 Android 按键延迟或稳定 p95；此轮未测手机模型运行内存。

## 源码与授权来源

- Rime 组句：https://github.com/rime/librime/blob/1.11.2/src/rime/gear/poet.cc
- 插件：https://github.com/lotem/librime-octagram/tree/57d18b9f58e5284bd891d559f6bdd16cf60341e9
- 数据：https://github.com/lotem/rime-octagram-data/tree/f8ce3b534733e489a8470a7c2adf5a154e8ea069
- 模型配置用法见 data-README.md、grammar.yaml，数据授权见 model-LICENSE（LGPL v3 文本）。不把插件 BSD 与数据许可混为一谈。
- 上层 SDK：https://github.com/gurecn/yuyansdk

## 发布边界

前轮词性试验已移出生产目录，归档在 ../2026-09-14-t9-sentence-rejected/。撤回不等于“我饿了”修好。现存中间 .11 APK 仍可能含失败试验，不交付它；本轮没有安装、提交或替换手机词库。

## 后续实际运行 3：公开源码 1.11.2

已在本机隔离目录编译 librime 标签 1.11.2 + 固定提交 Octagram，`[100%] Built target rime`，源码/编译库哈希加入 manifest。构建使用 /tmp 中解包的系统依赖，不替换 Android .so。

初次编译误包含了同隔离目录中旧 1.7.3 头文件，发生 path/file_path 编译错误；移开旧头文件后完整编译成功，没有为此修改引擎算法。第一次失败保留在 source-build.log，成功日志 source-build-current-headers.log。

- 新源码引擎版本 API 实际返回 1.11.2，Grammar 模块已注册。
- 同一 24 组 Query 再次跑完，source-model-pairs.tsv 分数与旧插件一致；这是评分接口验证，不是整句解码验收。
- 创建九宫格会话读取现有 pinyin.table.bin 时仍失败，这次在 StringTable 加载发生 SIGSEGV；调用栈见 source-no-model-trace.log。未生成 291 组输出，未运行有模型分支，不能报告模型前后收益。
- table_layout_probe.cc 使用 **本次源码真实头文件** 核实其 string_table 偏移 60、size 偏移 64；当前文件这两处值为 **0、61,111,700**。说明即便都标 Table/4.0，也不能假设当前定制数据与原版布局兼容。没有手改二进制偏移“强行修好”。

下一步必须用可追溯的公开词典源重建配套 table/prism，再在同一新数据、同一原生引擎上做有无模型的控制实验。重建词库需要评估覆盖、九宫格拼写、大小和授权，不将小型演示词典当作可用输入法。

## 回退代码验证

- 30 套件，138 项，其中 **137 通过、1 明确跳过、0 失败/错误**，见 rollback-test-summary.json。
- 跳过项就是“我饿了/真的吗/玩漂流”正常短句缺陷，保留为待解决，不计修复成功。
- 本饿反例已先红再绿；个人学习、迁移与 T9 相关选定测试保持通过；只读复审确认撤离未误撤个人链路。
- 首次回退测试遇到 classes.jar 的 zip END header 错误；另观察到并行会话在共享 build/unix 中 clean，因此不把共享产物用于最终证据。没有清理别人进程，改用临时 init script + 独立 build/t9-model-audit 与项目缓存验证。初始测试故障不归为算法失败，成功以隔离日志为准。
- 存在既有弃用/Kotlin 警告，没有为它们做无关修改。本轮未构建交付 APK，旧中间 APK 仍不能用作本轮结果。

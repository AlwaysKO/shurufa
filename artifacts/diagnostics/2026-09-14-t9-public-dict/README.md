# 可追溯公开词库重建与九宫格回归

2026-09-14；**隔离原生实验，不是 Android 已修复或可发布 APK**。本批次未替换正式 assets、原生库、过滤规则，也未安装手机。

## 来源与重建

- 词库：[rime-ice 固定提交](https://github.com/iDvel/rime-ice/tree/859e3b5300e0ea01334a627b15db101e94312a75)，完整默认中文表集 `8105/base/ext/tencent/others`。`source-lock.json` 保存文件 URL、大小、Git blob SHA-1 和 SHA-256；`prepare.py` 逐一验证后原样复制，不调频、不补测试词、不改原表顺序。
- 独立中文聚合入口不引入上游根词典额外的数字/ASCII 单字符造词示例；**不等于删除所有带数字的词**。保留原表，部分无注音条目由 Rime 官方自动注音。此差异不隐藏为“完整复刻雾凇方案”。
- 引擎：[librime 1.11.2](https://github.com/rime/librime/tree/1.11.2)；[Octagram 插件](https://github.com/lotem/librime-octagram/tree/57d18b9f58e5284bd891d559f6bdd16cf60341e9)。源码构建证据见相邻 `../2026-09-14-t9-rime-model/`。
- 模型：[hans 固定提交](https://github.com/lotem/rime-octagram-data/tree/f8ce3b534733e489a8470a7c2adf5a154e8ea069) 的 `zh-hans-t-essay-bgw.gram`，SHA-256 `d3cb2438c1fdcd6a855dd6ca8f5c1060a29273c6b64c2c2c69af67cd71b6aa7e`。
- 实际重建 **1,885,250 条、414 音节**，table 60,638,832 字节、prism 24,792 字节；各输出哈希见 `build-manifest.json`，官方部署日志在 `raw/rime.tools.*.gz`。不手改旧定制二进制布局。
- 词库仓库 GPLv3、模型 LGPLv3、插件 BSD；词库原表还包含多源致谢。正式分发前须核对这些来源、具体授权义务及 App 集成方式，**此隔离评估不构成分发授权结论**。
- 大型源表、模型、编译输出仅在 `/tmp/shurufa-public-dict/` 和 `/tmp/shurufa-rime-model-audit/`，没有加入 APK。归档不含真实用户记录，也不包含实验 userdb。

## 实验边界

两臂使用同一份重建 table/prism、同一源码引擎，均注册 grammar 模块；只有模型臂添加 `grammar/language` 并提供 `.gram`。无模型臂是同插件无模型的默认搭配惩罚，**不是旧手机闭源引擎基线**。

方案保留 ADGJMPTW 九宫格与小写完整拼音通路，不加内部简拼/模糊音/特定短句白名单。冷启动候选探针检查前 100 项；学习探针查前 5000 项。`rank0=-1` 仅指该窗口未找到。预编辑中的大写键编码是原生方案表示，不是 Android 显示结果。

## 用户反馈逐项结果

两臂冷启动目标名次相同：

| 目标 | 输入码 | 冷启动 | 完整码真实选择 3 次、独立进程重启后 |
|---|---|---|---|
| 充电宝 | 2466434262 | 前100无目标 | 前5000无目标 |
| 充电宝 | 24664342622 | 前100无目标 | 前5000无目标 |
| 充电宝 | 246643426226 | 第一 | 第一 |
| 怎么 | 9366 | 前100无目标，夜魔第一 | 前5000无目标 |
| 怎么 | 93663 | 第一 | 第一 |
| 你发货吧 | 64324862 | 前100无目标 | 前5000无目标 |
| 你发货吧 | 643248622 | 前100无目标 | 第一 |
| 用得上 | 9664337 | 前100无目标 | 前5000无目标 |
| 用得上 | 96643374264 | 第一 | 第一 |
| 真的吗 | 94363362 | 第一 | 第一 |
| 玩漂流 | 9267426548 | 第一 | 第一 |
| 我饿了 | 96353 | 第一，读音 wo'e'le | 第一 |

合计冷启动 **6/12** 第一，学习重启后 **7/12** 第一。若只算用户最初的 7 个编码，则两阶段都是 **3/7**。不能用完整码通过替代末字短码失败。

每臂 7 个目标各真实提交 3 次（21/21），每目标独立用户库；目标不在首轮候选时逐段选择目标文字前缀，只有实际 commit 等于目标才算成功。“你发货吧”由真实分段提交学入，非直接写库。两个独立进程验证持久化。**完整码学习有效，不意味着已学会短码召回。**

旧 Android 原生层本来也能给出“我饿了”等目标，App 过滤误删仍需单独修复；上述三项第一不能算本轮 Android 修复收益。

## 扩展语料与操作

`corpus-cases.json` 291 行：287 正向、4 不期望项（总额而、冲屌啊、灭除妈啊、夜魔）。合法但非当前意图的词也可能在不期望项中；不将它们做生产黑名单。按行比较，不按重复数字码去重。

| 指标 | 无模型 | 有模型 |
|---|---:|---:|
| 正向第一 | 128/287 | 126/287 |
| 正向前5 | 135/287 | 133/287 |
| 正向前100召回 | 137/287 | 135/287 |
| 完整码第一 | 99/119 | 100/119 |
| 完整码前100召回 | 102/119 | 103/119 |
| 末字前缀第一 | 29/168 | 26/168 |
| 末字前缀前100召回 | 35/168 | 32/168 |
| 20项留出第一 | 16/20 | 16/20 |
| 不期望项第一（越少越好） | 2/4 | 1/4 |

模型新增 6 行首选、退化 8 行；例如“孩子玩漂流”退为“还一眼前就”。“总额而”变成“总额儿”也不能算语义问题修复。**当前模型组合不达标，不接入正式版本。**

- 全部 1–4 位码：每臂 4680/4680 条输出完整；没有逐项语义标注，不能称全部词组正确。例如 2363 从“本饿”变“本恶”，问题并未消失。
- 追加一键再退格：每臂 291/291 恢复原候选文本、读音和预编辑。
- 第二页：每臂 161 项核对第二页首项与全局 index 100 文本一致；130 项没有第二页，记 NA。**不是整页比较，也未验证后页选中提交或 Android JNI 索引。**
- 锁拼音：记录构造 `首音节全拼 + 分隔符 + 剩余九宫格码` 的原生结果；不是 App 侧栏操作。summary 中 lock_target_top1 是全部案例的观察值（含不期望项），不作为正向通过率。
- 未完成：Android 实际触摸、过滤/读音映射、完整分页上屏、真机延迟和内存验收。不能以主机单次耗时代替手机性能。

## 复核与复现

```bash
# 仓库根执行；从归档原始输出严格重算，不依赖 /tmp 或引擎
python3 -m unittest discover -s artifacts/diagnostics/2026-09-14-t9-public-dict -p 'test_*.py'
python3 artifacts/diagnostics/2026-09-14-t9-public-dict/summarize.py
```

11 项工具测试先红后绿。`summarize.py` 校验案例数量、顺序、正反角色、候选完整性、全部短码集合，以及每个目标 TRAIN 1..3 和重启真实名次。**原始 C++ 探针 exit 0 只表明程序结束，操作结果必须读 TSV 布尔值；空输入也不能靠 exit 0 判通过。** 测试日志和候选、学习、操作原文在 `raw/*.gz`，统计在 `summary.json`。

重建步骤（先按 source-lock 下载所有 URL 到新目录，保持相对路径）：

```bash
D=artifacts/diagnostics/2026-09-14-t9-public-dict
R=/tmp/shurufa-rime-model-audit
export LD_LIBRARY_PATH="$R/source-build/lib:$R/host/usr/lib/x86_64-linux-gnu"
python3 "$D/prepare.py" --source /tmp/shurufa-public-dict/upstream \
  --lock "$D/source-lock.json" --output /tmp/shurufa-public-dict/rebuilt-new
"$R/source-build/bin/rime_deployer" --build \
  /tmp/shurufa-public-dict/rebuilt-new /tmp/shurufa-public-dict/rebuilt-new
```

output 必须新建，不能复用已学习目录冒充冷启动。`candidate_probe.c`、`interaction_probe.cc` 和 `bulk_interaction_probe.cc` 保留了实际测试源码；链接上述源码版 librime，并使用其 `src/rime_api.h`，不要混入系统 1.7 头文件。候选探针参数 `data-root cases.tsv output.tsv builtin`；cases 每行 `数字码<TAB>标签`（标签不参与解码），依次使用 feedback-cases 中 code、corpus-cases 中 code、或按长度遍历 2..9 的所有 1–4 位码。学习探针参数 `data-root full-code target 'reading' 3`，stdin 为该目标查询码；退出后用同目录另进程 `train-count=0`。每目标每臂必须独立用户库。

## 下一检查点

先独立验证**末音节前缀召回**和个人完整码学习向短码召回的衔接，再核对 App 过滤链路。新方案必须同时保留以上失败码及扩展语料，不再靠词性/字数放行补丁，不为了目标案例补词蒙混过关。正式替换须另过 JNI/UI、授权、性能和回退门禁。

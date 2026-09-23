# 三字简拼与固定表达补全

`build_input_completion_index.py` 生成随 APK 打包的 `completion/input_completion.t9idx`。三字简拼以每个音节的首字母映射九宫格码，不是按键帽的第一个字母逐个拼接。长句只补充已筛选的固定表达；普通原生整句仍沿用原有资格和排序。

## 来源和生成

- 使用 `completion/public-phrase-sources/source-lock.json` 锁定的 Rime-ice `base.dict.yaml` 与 `tencent.dict.yaml`。原始读音/词频优先来自 base。
- 全部三字 base 词条建立简拼索引；另外从 tencent 补充缺失的三字行政地名（镇、县、市、区结尾），通过 `server/package-lock.json` 锁定的 pinyin-pro 注音，权重为 0。多音地名仍可能需要个人词库修正。
- 五至二十字长句须同时存在于 base 和固定表达语料中。语料包括 `input-completion-sources.json` 锁定的诗词选集、李白作品，以及现有 `tools/data/common_association_phrases.txt`。不按测试目标逐条添加例外。
- 沿用 `completion/NOTICE.txt` 的诗词 MIT 声明和 `public-phrase-sources/` 中 Rime-ice 的原始声明、许可证。源文件校验失败则中止生成。

将 Rime 文件按 `cn_dicts/` 路径置于源目录，诗词文件按锁文件的文件名置于诗词目录；安装 server 锁定依赖与现有 Simplifier 所需依赖后运行：

```sh
python3 tools/build_input_completion_index.py --source /path/to/rime-source \
  --poetry /path/to/poetry --output yuyansdk/src/main/assets/completion/input_completion.t9idx
python3 -m unittest discover -s tools -p test_input_completion_index.py
```

产物清单 `.t9idx.json` 记录提交、条数、大小和 SHA256。当前 160456 条三字读音（其中 3400 条自动注音地名）、2820 条固定表达，索引 5911794 字节。

## 运行边界

索引通过 mmap 映射并按 key 二分定位；最多扫描 4096 行，不在按键时扫描整个公共词表。简拼只放行恰好三字三个音节；长句需要三个完整音节并开始第四音节。已覆盖全部音节时转成整词匹配，防止完整输入的最后一键使候选消失。

保留已有完整单音节、整词/末字补全的优先级。长句预测在个人历史排序后最多保留两条；个人明确选择允许改变顺序。拼音栏只显示实际输入对应的片段，完整读音单独用于学习。首屏与后页使用相同资格，原生候选保留原生索引。仅实际短码计数，不向完整码伪造点击。

`559` 首项“就可以”，其显示为 `j'k'y`；“良口镇”以低先验可召回，选过后可由个人学习前移。`649` 同时有“你要”等既有末字补全，“没关系”可召回但不保证首屏。本轮没有实现任意长度简拼或自由长句预测。

# 离线九宫格词典

- 来源：https://github.com/fxsjy/jieba
- 固定提交：`67fa2e36e72f69d9134b8a1037b83fbb070b9775`
- 源文件：`jieba/dict.txt`（MIT，许可证随 APK 位于 `completion/jieba-LICENSE.txt`）
- 选取规则：2–6 个汉字，词频 ≥ 10；或词频 ≥ 3 的固定表达、成语、简称（l/i/j）。不是按具体输入编码添加例外。
- 拼音：项目 server/package-lock.json 锁定的 pinyin-pro，构建期转换，保留音节边界。其多音字转换仍可能有误，不能替代专业语言模型。
- 产物：130,860 条，`completion/t9_lexicon.tsv.gzip`，运行时不下载词库、不调用拼音服务。
- 搜狗 APK 仅用于观察引擎接口职责，不使用其词库或原生库生成此资产。

重新生成（先在 server 执行 npm ci）：

```bash
curl -fL https://raw.githubusercontent.com/fxsjy/jieba/67fa2e36e72f69d9134b8a1037b83fbb070b9775/jieba/dict.txt -o /tmp/jieba-dict.txt
node android/YuyanIme/tools/generate_t9_lexicon.mjs /tmp/jieba-dict.txt android/YuyanIme/yuyansdk/src/main/assets/completion/t9_lexicon.tsv.gzip
node --test android/YuyanIme/tools/generate_t9_lexicon.test.mjs
```

## 2026-09-08 中文领域补充

新增自维护清单 `tools/data/chinese-domain-words.txt`，共 347 条聊天、办公协作、互联网/编程中文表达（与基础库存在重叠，运行时按文字去重，并非新增 347 个不重复词）。不含英文词库，不来自搜狗资产。构建期使用相同的 `buildLexicon` 转换、固定补充频率 100，资产为 `completion/chinese_domains.tsv`。标准全键拼音以 Rime 原生基础排序为先，个人选择权重参与混排。

重新生成（从 `android/YuyanIme` 执行）：

```bash
node --input-type=module <<'JS'
import {readFileSync,writeFileSync} from 'node:fs';
import {buildLexicon} from './tools/generate_t9_lexicon.mjs';
const words=readFileSync('tools/data/chinese-domain-words.txt','utf8')
  .split('\n').filter(x=>!x.startsWith('#')).join(' ').split(/\s+/).filter(Boolean);
writeFileSync('yuyansdk/src/main/assets/completion/chinese_domains.tsv',
  buildLexicon(words.map(x=>x+' 100 n').join('\n')));
JS
```

## 2026-09-22 AOSP 日常输入补充（当前生成方式）

- 新增来源：[AOSP PinyinIME](https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/+/49aebad1c1cfbbcaa9288ffed5161e79e57c3679/jni/data/rawdict_utf16_65105_freq.txt)，固定提交 `49aebad1c1cfbbcaa9288ffed5161e79e57c3679`。
- 原始 UTF-16 文件 SHA256：`408700f28a56091fa07f3b849a0f134fbfc71e6b2ae9b3f52973a5b076f599ff`。原文压缩存于 `tools/data/aosp/rawdict_utf16_65105_freq.txt.gz`，解压后哈希必须一致。
- 原始 NOTICE 声明 Copyright 2009 Android Open Source Project、Apache-2.0；完整文件保存 `tools/data/aosp/NOTICE`，随 APK 为 `completion/aosp-dictionary-NOTICE.txt`。这里依据上游许可声明，不宣称逐词独立授权审计。
- 原格式为词语、浮点公共词频、GBK标记、逐字拼音。第三列不是次数；按同提交 `jni/share/dictbuilder.cpp` 的默认规则仅取标记0。
- 本项目于2026-09-22修改：筛选2–6汉字、音节数一致及源频率≥200，控制低频词补充范围（阈值不等同日常词分类）；去除“曝光/pu guang”“补给/bu gei”已知错误读音，其余合理多音词按词语+拼音保留，不猜测所有单字组合。
- 两套词频不相加。按22,075个重叠词的最大读音频率比例中位数0.5041338885424145校准AOSP公共频率；同词优先保留AOSP提供的整词拼音，原库未被收录的词保留。
- 采用30,168个AOSP词、30,242个读音，新增8,093个不同词。合并产物139,027行，仍为 `completion/t9_lexicon.tsv.gzip`。这些不是个人词库记录，不上报、不计点击。
- 词库较旧，不承诺覆盖网络新词或全部聊天短句；42项日常样本覆盖由28提升到33。没有引入白霜/雾凇混源数据，也未开启拼音纠错。后台手动录词继续用于个人表达。

完整重新生成：

```bash
# 先按上方固定jieba源生成独立基线，不能把已合并产物再次当基线。
node android/YuyanIme/tools/generate_t9_lexicon.mjs /tmp/jieba-dict.txt /tmp/jieba-baseline.tsv.gzip
node android/YuyanIme/tools/merge_daily_lexicon.mjs /tmp/jieba-baseline.tsv.gzip \
  android/YuyanIme/tools/data/aosp/rawdict_utf16_65105_freq.txt.gz \
  android/YuyanIme/yuyansdk/src/main/assets/completion/t9_lexicon.tsv.gzip
node --test android/YuyanIme/tools/generate_t9_lexicon.test.mjs android/YuyanIme/tools/daily_lexicon.test.mjs
```

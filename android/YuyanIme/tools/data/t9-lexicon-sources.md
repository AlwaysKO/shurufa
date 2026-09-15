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

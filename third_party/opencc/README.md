# 会话标题繁转简词典

来源：OpenCC ver.1.1.9，commit `556ed22496d650bd0b13b6c163be9814637970ae`。
原文：https://github.com/BYVoid/OpenCC/tree/ver.1.1.9/data/dictionary
许可证：Apache-2.0，见LICENSE（原文未改）。TSCharacters与TSPhrases保留原始数据。

用途仅为会话标题的确定性繁转简；最长词优先、首个标准输出，不做近似姓名纠错。
通过 `python3 scripts/generate-chat-title-dictionary.py` 生成Android词表及PostgreSQL 042转换函数。
用 `--check` 校验生成物一致性。不改原始消息/图片，不从这些数据推断联系人身份。
后续变更已部署的函数须新增迁移，不能只重写042。

## 上游文件校验

- `TSCharacters.txt` SHA256：`6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`
- `TSPhrases.txt` SHA256：`504169029c43f7f234b8e2ae470720af3657675c5574ff8aa0feb257e1dc5ce2`
- `LICENSE` SHA256：`b534e465949558eec2597b04f5092b5e161236a68dfbfd04d547592ac3964308`

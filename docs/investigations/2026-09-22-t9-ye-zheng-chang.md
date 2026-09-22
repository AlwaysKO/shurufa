# 九宫格“也正常”候选调查

日期：2026-09-22。用户要求检查原因，本轮不修改正式候选逻辑或词库。

## 反馈与按键

- `93943642`：用户希望末字补全为“也正常”，实际看到“也正啊”。`ye=93`、`zheng=94364`，剩余 `2` 既能是 `a`，也能是 `chang=24264` 的首键。
- `939436424264`：完整 `ye zheng chang`，用户看到“也正厂”。“常”和“厂”同音，单靠按键合法性无法区分。

## 源码与资产证据

1. 当前 APK 公共补充词库有 `正常 / zheng chang / 10308` 和 `也正 / ye zheng / 202`，没有“也正常”“也正啊”“也正厂”。公开原词条索引同样有“正常”“也正”，没有这三个三字串。因此坏候选不属于本次公共补充词库直接收录的整词。
2. `T9Lexicon.queryEntries` 按一条词语的全部拼音匹配当前输入，不对输入分段再组合多个词。“正常”有词频，不代表“也＋正常”的整句能被该词库召回和评分。
3. `OfflineT9Candidates.select` 在没有可信整词覆盖输入时启用 `allowNativeWhole`，将读音匹配、全汉字、字数与音节数一致的原生候选放进 `nativeSentences`。这是一条整句回退路径，没有评估词语搭配是否自然。
4. `nativeSentences` 沿用原生输入顺序，并排在分段前缀之前。`PersonalCandidateRanker` 无个人学习时保留基础顺序，不会使用“正常”的词频给“也正常”重新评分。
5. `RimeEngine.updateCandidatesOrCommitText` 把原生候选及读音交给该应用层；本次新增的 AOSP 数据合并到 `completion/t9_lexicon.tsv.gzip`，不是重建 Rime 解码词库或接入整句语言模型。因此新增整词覆盖不能等同于整句解码质量改善。

关键源码：
- `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/T9Lexicon.kt`，`queryEntries`。
- `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/OfflineT9Candidates.kt`，`hasDictionaryWhole`、`nativeWhole`、`nativeSentences`、`base`。
- `android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/completion/PersonalCandidateRanker.kt`，`rank`。

## 版本与验证边界

本地交付包 `apk/shurufa-2026-09-22-v20260922.19-2026092219-debug-b69f7e43.apk` 内公共补充词库与当前源码资产字节一致，SHA256 为 `62d26308b7b6f321a0138ba9416997cb84854f2e905abca147f3b165364238f0`。

本机只有模拟器设备，不是用户反馈问题的手机。没有取得该手机原生候选完整列表或个人学习数据库，不能把应用层的受控复现说成真机原生解码实测，也不能断言手机个人历史完全没有影响。

## 受控复现结果

按 verification-before-completion 核对实际执行结果：1 项 JVM 调查测试通过，覆盖两组输入、目标存在/缺失，以及单独输入“正常”的对照。通过表示确认现有问题机制，不表示问题已修复。

直接加载当前真实主词库、领域词库和公开原词条索引，调用正式 `OfflineT9Candidates.select`；个人存储设为 null，明确排除个人学习干扰。原生候选及读音为受控输入，不启动手机 Rime。

| 编码 | 补充词库整词查询 | 受控原生输入前两项 | 应用层实际输出前两项 |
| --- | --- | --- | --- |
| 93943642 | 空 | 也正啊、也正常 | 也正啊、也正常 |
| 939436424264 | 空 | 也正厂、也正常 | 也正厂、也正常 |

两个坏候选和“也正常”均通过拼写/整句覆盖检查；原生输入不提供“也正常”时，应用层不会额外生成它。单独查询 `9436424264` 则正常返回“正常（10308）、整场（62）”。因此这条应用层缺口无需个人历史即可出现。

本机复现材料（临时诊断，不提交）：`/tmp/shurufa-yezhengchang-probe/YeZhengChangProbeTest.kt`、`init.gradle`、`test-jvm.log`。命令：载入 `.runtime/macos/android-env.sh` 后，执行 `android/YuyanIme/gradlew -p android/YuyanIme -I /tmp/shurufa-yezhengchang-probe/init.gradle :yuyansdk:testOfflineDebugUnitTest --tests '*YeZhengChangProbeTest' --offline --console=plain`。

环境记录：最初使用 Robolectric 时，运行库下载分别遇到 TLS 和 POM SHA512 不一致，均未进入业务断言。最终改用不需要 Android 环境的真实候选算法 JVM 测试，不伪造数据库结果，也未绕过依赖完整性校验。

## 后续修复方向

应验证整句召回和基于词组的评分，让“也＋正常”能利用“正常”的词语证据参与竞争；先区分目标根本未召回与已召回但排名靠后。只给已召回候选重排不能解决前者。补入“也正常”只能覆盖这个个例，直接关闭整句回退则会损失其他未收录的正常短句，不适合作为通用修复。

回归至少包含本次末字首键和全码、逐键追加/退格、原生无目标/有目标、个人选择、分段与后页索引，并保留既有合法整句与乱串反例。

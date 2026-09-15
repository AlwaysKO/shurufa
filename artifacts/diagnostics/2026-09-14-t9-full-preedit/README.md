# 完整拼音显示修复验收（2026-09-14）

## 用户纠正与根因

用户两张截图明确：不是后半按键丢失，也不是候选数组为空；选词前只显示 `wo'bu'zai`，选中“我不在”之后后半拼音才出现。此前前缀排序修复不能解决这次显示问题。

首个可信候选只覆盖前半时，内部组合仍保留尾部数字，但旧显示函数删除数字，导致用户看不到已经输入的后半段。

## 本轮范围

- 独立只读显示路径优先保留完整首选读音，否则使用原生元数据中按键对齐且覆盖更长的拼音；不把被过滤的汉字重新放进候选。
- 真正没有对齐读音的按键用键帽字母组保留，不删除、不显示数字、不恢复“待选拼音”提示。29组设备快照均有对齐读音，字母组兜底由单测覆盖。
- 普通候选栏拼音可横向滚动，文本变化时露出尾部，保留字号与行高；浮动候选栏未新增滚动。
- 原始组合、回车保护、候选过滤/排序、提交、学习和词库不变。本次没有放开未知整句汉字候选；不能据此声称首次候选已经可以一次选择整句。
- 本轮5个生产文件的精确增量见 `scope.patch`，与仓库既有未提交改动区分。

## 验证结果

- 21套件、121项测试，0失败/错误/跳过；包含实际 `CandidatesBar.initialize/showCandidates` 绑定、长拼音滚动、末字首字母、个人读音优先、原提交缓存和门禁保持不变。
- 305组旧候选回放所有字段与上一轮 `2026-09-14-t9-long-composition/replay.json` 相同。
- 手机隔离目录录制29组快照：16个“我不再彷徨”逐键前缀以及13个历史反馈码。新旧原生候选、过滤结果、内部组合及提交门禁逐项相同，仅显示修复。

| 输入码 | 修复前显示 | 修复后显示 |
|---|---|---|
| 962892472644 | wo'bu'zai | wo'bu'zai'pang'h |
| 9628924726448264 | wo'bu'zai | wo'bu'zai'pang'huang |
| 6464842678727 | ming'tian'qu | ming'tian'qu'pa's |
| 96353 | wo'e'le | wo'e'le |

### 证据层级与限制

`T9DisplaySnapshot.java` 在设备独立临时目录使用真实 JNI 和 APK 数据，录制原生结果，构造生产 KeyRecordStack/元数据状态，再调用实际 DecodingInfo→Kernel→RimeEngine 显示 getter。它不是完整 onNormalKey 事件链，也不是实际触摸或微信 UI 验收。没有读取用户历史、操作聊天、安装或切换输入法。UI 绑定与滚动验证为自动测试，真实手势体验仍需安装后确认。

### 红绿测试过程

首次 fixture 用 Robolectric 大写 KeyEvent 没有正确构造手机 T9 栈，属于测试夹具错误，保留 `red-fixture-error.log.gz`。改用生产栈记录后得到5个正确失败，见 `red.log.gz` / `red-xml/`。首次修复后2个旧 UI 测试仍假定 TextView 直接父级是组合行；增加滚动容器后适配为实际 viewport 与操作按钮同父坐标检查，并保留不重叠、裁切、完整文本与滚动断言。最终121项全部通过，不将早期失败算作通过。

## APK

- 路径：`android/YuyanIme/app/build/t9-display-audit/outputs/apk/offline/debug/yuyanIme_2026091417_debug.apk`
- 大小：129722960 字节。
- SHA256：`c530f3a532303c4b578c1d96e044c0481eb8bea8446a95afd43d0f6d44ccc23b`。
- v1/v2签名有效；与手机已安装 `.16` 的 JNI、table、prism、公开索引及压缩方式一致，详见 `package-verification.json`。
- 测试包仅复制到设备隔离目录用于验证，**未安装，未提交代码**。最后构建增量核验成功见 `delivery-verification.log.gz`。

详细前后结果见 `before.jsonl`、`after.jsonl`、`display-comparison.json`；测试数量见 `test-summary.json`。

# 九宫格 yiyang 候选刷新真机采样

## 现场

- 手机 AQUL024807002303，offline.debug 20260930.10 / 2026093010，PID15130；用户确认九宫格，手工输入后逐个退格。助手未操作输入、发送、安装或清数据。
- 原有 ImeLatency 调试标签未输出，临时启用该标签后，非函数采样期间捕获输入 native16ms/candidates230ms、退格 native10ms/candidates245ms；其余已记录候选刷新34–39ms。日志无按键内容，不强行把某一行对应到某一字母。
- 第一轮90秒函数采样未包含按键，不作为根因证据。第二轮用户回复开始后启用1ms间隔采样，用户完成后停止，取得有效主线程按键调用链。
- 第二轮慢键日志候选471ms/533ms。函数采样会扰动耗时，不将此值当作无采样日常延迟，也不与第一轮直接作优化对比。

## 方法与发现

- ART trace v3/dual、14字节记录，未溢出。离线按主线程 enter/exit 重建栈，栈不匹配数0；限定 RimeEngine.onNormalKey/onDeleteKey 调用期间统计，避免将主线程空闲归到按键。
- 该机 trace 时间字段的单位与 usec 标签不一致，未经校准不将原始计数转成毫秒；阶段绝对耗时以 ImeLatency 为准。
- 按键调用期间候选 updateCandidatesOrCommitText 占绝大部分，内部 OfflineT9Candidates.select 为主要路径；其子热点包括 InputCompletionIndex.query、PersonalWordReading.normalize、InputSpellingMatch.match、select.extraMatch、T9Lexicon.queryEntries。
- 正则 native compile 为显著独占热点；源码核实 PersonalWordReading.normalize 每次创建两个 Regex，InputSpellingMatch.match 每次创建匹配/分隔 Regex，T9Spelling 反复创建分隔 Regex。补全桶每次查询重新解码/规范化，extraMatch 在多个候选处理步骤重复规范化相同读音。
- LocalInputStore.personalWords/SQLite 也有消耗，但本轮不是主要热点；不能继续只改数据库锁或把这次归因截图/上传。也不能据一次样本排除其他场景的后台竞争。

## 最小修复方向（尚未实施）

1. 固定正则预编译复用，保持完全一致的读音校验和分隔规则。
2. 单次候选计算复用规范化/匹配结果，避免在同一键的筛选与排序中重复处理相同词条；若缓存跨按键，仅缓存不可变词库数据并设上限，不能缓存动态个人排序导致学习/禁词失效。
3. 用现有拼写/词库/学习/屏蔽测试锁定输出等价，再以同机同输入序列、关闭函数采样后的 ImeLatency 对比。不得靠删候选、改排序或跳过正确性校验冒充提速。

原始 trace/日志及解析脚本仅保留 .runtime/diagnostics，不入 Git。本轮未修改生产代码，不宣称修复已完成。

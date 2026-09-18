# 审查结论
只读后端审查发现：
1. A人工合并到pending P后，P自动确认到C，只更新P会留下A→P→C。
2. C人工合并到pending P后，C迟到确认携带previous=P会让P指向自己。

两项均在隔离PG复现（review-interleaving-red.log：2失败/10通过），加入目标ID自环保护以及来源集合展平后，review-interleaving-green.log的12项测试通过。原审查者只读复核修复闭合，无新增直接重要风险。主线程重新执行194项Web/pg-mem回归及后端构建通过。

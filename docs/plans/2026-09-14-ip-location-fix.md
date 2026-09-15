# 后台 IP 地址解析修复计划

目标：修复永久解析中，同 IP 复用成功结果及在途请求。
架构：沿用 ip-api 与 input_event 持久结果；内存成功缓存、失败一分钟冷却、Promise 合并并发。events 等待并行解析并填充当前响应，不新增数据库结构。
技术栈：TypeScript、Express、Vue、Vitest、pg-mem。

1. 在 server/src/lib/ipgeo.test.ts 编写回归：重复/并发去重、新记录补写、重启复用持久结果、失败可重试、返回当前结果。运行 npx vitest run src/lib/ipgeo.test.ts 确认旧实现失败。
2. 修改 server/src/lib/ipgeo.ts，仅修正上述解析与缓存逻辑；修改 server/src/api/dashboard.ts 等待结果并填充；client/src/views/Activity.vue 改用真实失败占位。
3. 运行新增测试、服务端全量测试及前后端构建，检查 git diff。仅报告实际验证结果，不自动部署或提交，不改素材文件。

调查证据：原实现 cache.has 会跳过回写且永久缓存 null；后台 fire-and-forget，页面无轮询；本机对截图 IP 实测 HTTP 200、返回中国 广东 广州市（2026-09-14）。

## 验证结果
- 6 项新增回归先在旧实现失败，修复后全部通过。
- 服务端与客户端 build 通过；客户端保留 bundle 大小警告。
- 服务端全量 449 项：448 通过，1 项 GIF 渲染测试触发 5000ms 超时（prototypeRenderer.test.ts 的三个关键词与八种 style 测试）；不修改无关渲染代码。
- git diff --check 通过。未部署、未重启、未提交；首次未缓存解析沿用 5 秒外部请求超时，同页不同 IP 并行解析，同 IP 合并请求。
- 上述 GIF 超时用例单独重跑通过（3.50 秒）；全量运行仍如实保留一次超时记录。

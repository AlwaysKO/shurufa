# 关键词图库统一目录与7天缓存设计/后续实现计划

> 使用superpowers:subagent-driven-development，逐项TDD→规格审查→独立质量审查→最终验证。main原目录，无worktree；只改相关代码，ko:ko。

## 已明确范围

用户指定server/images按关键词存储推荐图，已有与未来成品统一归档。先300核心词+别名句式草案，分批生产每词4内置、其余接口。来源未知的搜狗图片不直接发布；搜狗批量搜图尚需用户提供有权限使用的接口文档，不使用旧第三方代理冒充搜狗官方接口，不绕过接口访问控制。

## 当前已做的数据工作

server/images归档140张本项目原创GIF和140首帧WebP，20关键词；index记录52published、88review-only及来源、SHA。按来源报告逐字节核对，仅复制，不覆盖旧工程。当前路由仍读.runtime/expression-assets，没有擅自公开评审图或参考图。

## 实现方案比较

1. 直接静态挂载全部server/images最简单，但会暴露未验收图/参考图/索引工程路径，拒绝采用。
2. 一次替换全部旧runtime会牵连60模板、48Emoji基底、2304合成图，不属于最小范围。
3. 推荐：新增受发布索引约束的原创图库读取与下载路由，旧模板/Emoji链保留；推荐合并去重，预制素材URL指向白名单下载。通过发布命令将批准项纳入正式catalog，统一校验SHA/许可证/4bundled规则。

## 任务1：可重复归档与发布（TDD）

新建server/src/expression/imageLibrary.ts和.test.ts、server/scripts/sync-expression-image-library.ts；在server/package.json新增显式命令。
- RED覆盖多批归档、每词目录、ID重复、路径逃逸、缺件/坏SHA拒绝、review-only不发布、复制不转码、幂等、失败保留已有目录。
- GREEN最小实现读取明确的源manifest/report，原子写索引与文件。现有归档是快照，需由命令复核接管而非默认为真。
- 接入现有batch生成完成后的显式归档步骤，禁止partial渲染删除整库；不移动masters/poses原始工程。

## 任务2：仅发布文件的接口（TDD）

修改server/src/api/expressions.ts、server/src/app.ts及对应测试，保持现有设备身份校验。
- RED：命中已发布词返回对应预制GIF和首帧WebP；用户未批准项、参考目录、任意文件路径、跨目录请求不可访问。
- 返回公开字段仅id/format/version/SHA/keywords/embeddedText/sourceType/url等，不泄漏源工程路径；真实image/gif MIME，绝不现场改字或静态化。
- APK每词4和远端额外项从同一发布清单生成，现有模板/Emoji URL不回归。新图批准状态与缓存失效版本需一致。

## 任务3：7天本地优先（TDD）

主要ExpressionSync/ExpressionQueryCache及测试；已有TTL=7天，不能重复造缓存层。
- 新鲜查询索引且原件可用：零recommend网络；反复读取不延长fetchedAt。
- 部分缺件：先显示可用图，只修复缺件；仍不重复请求新鲜recommend。
- 达到7天：先显示旧图，后台请求一次更新；成功才刷新时间，离线失败保留旧图。
- 相同查询并发去重，删除/切换输入后迟到仍缓存、不更新旧UI；owner销毁取消的边界维持。
- 词库别名草案先不全量自动上线；精确规范化查询索引与核心关键词映射是两层。现有规则覆盖的别名可复用文件SHA，但完整跨别名查询索引复用需单独验证，避免错误语义合并。
- APK内置原件不是“已成功取得的网络查询缓存”：首次查询可检查目录更新；其后7天命中不再请求。不得将安装包素材永久视为新鲜网络结果而无法更新。

## 任务4：验证

server npm test/build/expression:prototype/expression:generate、素材脚本；Android全部Expression相关测试与assembleOfflineDebug。新增目录下载字节与正式清单/SHA一致；原GIF16帧等质量门保留。无手机不宣称微信真实发送成功。源码、产物、目录ko:ko；保留client无关改动。

## 待确认/外部依赖

搜狗授权接口地址、可使用范围和文档待用户提供。可先做自有目录/缓存接入，不以获取不到第三方图片为由制造未经许可的参考图或假来源。300词草案由用户确认优先批次后按小批继续生成。

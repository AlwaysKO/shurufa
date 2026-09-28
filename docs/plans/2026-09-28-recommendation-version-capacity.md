# 推荐版本独立与图库容量调整

目标：半小时探测只由推荐词/GIF更新触发目录同步；扩大原件存储预算，超过64MB仍能增量补齐。

设计：`versions?scope=recommendations` 返回独立内容版本，完整目录新增 recommendationVersion。默认 versions 保留原有全部表情目录版本，旧客户端不受影响。手机后台比较独立版本；最新目录包含全部当前有效词组，图片仍按 SHA 增量，不要求逐个历史版本补齐。旧服务器缺少字段时兼容原版本。

容量：用户已明确选择1GB。继续保留流式写入、单图上限、后台低存储约束，不把空间不足标记为完成。

步骤与验收：
1. 在 server/src/api/expressions.test.ts 加接口回归：AI修改只改变总版本；推荐词/GIF变化改变独立版本；完整目录公布对应标识。先失败，再改 expressionSnapshot.ts/expressions.ts。
2. ExpressionModels.kt/ExpressionSync.kt/ExpressionSyncJobService.kt 使用后台独立版本；ExpressionBackgroundSyncTest.kt 验证探测URL、跨版本补图和同版本无额外下载。
3. ExpressionQueryCache.kt 调整预算，回归超过64MB仍下载且不会无限重下；已达到新预算保留现有边界。
4. 运行相关客户端及服务端测试和服务端编译；独立测试结果目录避免并行Gradle干扰；审查后构建原签名APK复制本机apk目录。无SQL变更；不自动上线、安装、提交或覆盖并行改动。

验证结果：
- 客户端7个测试类96项通过；服务端 expressions.test.ts 16项通过；服务端 TypeScript 构建通过。
- 本地实际接口核验：scope=recommendations 仅返回 version，该值等于完整目录 recommendationVersion；默认版本接口仍等于完整目录 version。
- 覆盖 AI 改动隔离、推荐图内容/词语/删除改版、超过64MB后继续预取、1GB接近上限暂停、跨多个版本补图、旧目录相同总版本的独立版本迁移及跨实例读取、旧待办分批继续时条件请求。
- 缺少独立版本时才强制获取目录，避免旧缓存被304挡住；已有独立字段沿用条件请求，避免每批重下完整目录。代码审查通过。
- 保留系统低存储约束及每轮24张/2分钟边界；1GB是容量预算，不承诺固定时间内全库完成。服务端独立版本上线与手机安装新包须分别完成才完全生效。
- APK：`apk/shurufa-2026-09-28-v20260928.22-2026092822-debug-0fef5c8c.apk`，真实版本20260928.22/2026092822，SHA256 `0fef5c8ce03a097f8da9b62f59a0d2313d68c65bb81b070768b8a05b0ff0d0de`。源包与交付包一致；API23/27/28/32/36原签名通过，非testOnly。Mac交付目录可用，Windows E盘不可访问；未自动发布服务端或安装手机。

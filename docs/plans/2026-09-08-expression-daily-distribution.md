# 五词动态图库正式分发实现计划

> 使用 superpowers:subagent-driven-development，逐任务 TDD → 规格审查 → 质量审查。当前 main，不创建 worktree，不纳入已有 Android/发送/键盘未提交改动。

**目标：** 将用户已验收的 daily-01 四十张 GIF 正式接入，每词四张 APK 内置、另四张联网获取后本地缓存。

**架构：** 保持服务端与 APK 完整 catalog 元数据一致，缩略图全内置；仅二十张 remote 原 GIF 不打包。prebuiltAssets 增加可选 distribution=bundled|remote，缺省 bundled 兼容旧十二张。复用原 recommend、下载和 SHA 原子缓存，不新增接口/数据库，不依赖手机叠字。

**方案取舍：** 不用全量打包（违背每词四张），不裁减 Android catalog（需额外持久索引和条件版本协议）；完整元数据加分发标记改动最小。系统 cacheDir 可跨进程复用，但系统或用户清缓存后需重下载，不宣称永久保存。

## 任务1：服务端与正式素材（TDD）

文件：server/src/expression/assetGenerator.ts、assetGenerator.test.ts、server/src/types/expression.ts、assets/expression/manifest.source.json、scripts/tests/expression-assets-test.sh；新增 assets/expression/batches/daily-01/gifs/*.gif（验收原字节）。

1. 失败测试：explicit remote 原 GIF 不进 APK，缩略图和元数据保留；缺省 bundled 兼容；非法 distribution 在删除输出前拒绝。
2. 实现可选分发字段与精确 APK 子集选择，保持尺寸/帧/来源/SHA门禁。
3. 将40项按每词bundled优先的原清单顺序登记prebuiltAssets，记录原manifest/itemId/style/sourceType/SHA，提升catalog版本。旧三词十二张原创保持；本次五词旧静态预制项由新八张GIF替代，避免APK每词实际多于四张。其余短语用可选受控idPrefix固定原有ID，避免数组移除引起ID漂移。
4. 脚本基于manifest验证预制总数、每新词4+4、精确APK文件集合和原字节一致。
5. npm test、npm run build、npm run expression:prototype、npm run expression:generate、bash scripts/tests/expression-assets-test.sh。规格和质量审查后精确提交。

## 任务2：Android 本地优先及缓存复用（TDD）

文件：expression/model/ExpressionModels.kt（以实际模型路径为准）、ExpressionSync.kt、ExpressionRecommendationResolver.kt及对应测试、ExpressionPrebuiltAssetsTest.kt。

1. 失败测试：本地首个回调只呈现内置四张及已SHA验证的remote；未下载remote不能假装APK资源或阻塞首屏；进程重建后依靠完整内置索引发现有效缓存。
2. 模型可选distribution缺省bundled；Sync本地筛选remote缓存并提供真实本地GIF预览路径，磁盘校验在IO执行；远端回调继续用既有resolver下载原GIF。
3. resolver识别remote预制素材即使无显式URL也使用原文件解析，失败不冒充静态成功；prebuilt不进入合成器。
4. 真GIF回归：远端下载→SHA缓存→重建→断网预览/发送源逐字节一致；损坏缓存不显示为可用；旧请求和取消不回写UI。
5. 五新词每词四张实际内置，另外四张原件APK不存在，旧三词十二张测试保持。
6. expression catalog/cache/sync/resolver/preview/panel定向测试与 :app:assembleOfflineDebug。手机已拔掉，不操作、不虚报真机结果。

## 任务3：接口与交付验证

使用实际runtime catalog验证五词recommend各返回八张，前四为bundled、GIF URL/缩略图/SHA正确；已有原创精确命中不调用外部热图搜索。记录生成与APK测试日志、提交号、部署边界。仅本地生成/构建不等于线上部署；无手机不宣称真机播放或微信发送验收。另行部署前核对现有服务运行方式，不擅自重启不相关服务。

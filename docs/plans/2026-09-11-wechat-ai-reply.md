# 微信关系画像与 AI 回复实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 从微信私聊或群聊的双方可见文字生成可审计的关系画像，并在输入法候选栏提供安全降级的 DeepSeek 回复。

**架构：** Android 保守解析可访问性树并共享短期活动会话；服务端按用户和会话隔离历史，人工训练版本化画像，统一路由零 Token 与 AI 回复；输入法只展示候选并由用户点击填入。

**技术栈：** Kotlin/Android Accessibility/Room/OkHttp、Node.js/TypeScript/Express/PostgreSQL、Vue 3、Vitest/JUnit/Robolectric。

---

### 任务 1：服务端 AI 领域模型与数据库

**文件：**
- 创建：`server/migrations/014_relationship_ai.sql`
- 创建：`server/src/types/relationshipAi.ts`
- 创建：`server/src/domain/relationshipAiValidation.test.ts`
- 创建：`server/src/domain/relationshipAiValidation.ts`

**步骤：** 先写失败测试覆盖画像 JSON、回复请求、刷新次数和候选去重；运行目标测试确认因实现缺失而失败；实现最小校验和迁移；再次运行测试。

### 任务 2：DeepSeek Provider、画像训练与回复路由

**文件：**
- 创建：`server/src/ai/deepSeekProvider.test.ts`
- 创建：`server/src/ai/deepSeekProvider.ts`
- 创建：`server/src/relationship/aiProfile.test.ts`
- 创建：`server/src/relationship/aiProfile.ts`
- 创建：`server/src/relationship/aiReply.test.ts`
- 创建：`server/src/relationship/aiReply.ts`
- 修改：`server/src/app.ts`
- 修改：`server/.env.example`

**步骤：** 先用注入式假 Provider 写失败测试，覆盖双方样本、群聊说话人、用户隔离、画像版本、第二次刷新触发 AI、非法结果和异常回退；实现 DeepSeek OpenAI-compatible HTTP 客户端与最小业务逻辑；运行相关及完整服务端测试。

### 任务 3：移动端及后台 API

**文件：**
- 创建：`server/src/api/relationshipAi.test.ts`
- 创建：`server/src/api/relationshipAi.ts`
- 修改：`server/src/api/mobileRelationships.ts`
- 修改：`server/src/api/relationshipDashboard.ts`
- 修改：`server/src/app.ts`

**步骤：** 先写 API 失败测试，固定训练、查看画像和移动回复协议及鉴权；实现路由，确保未知会话和 AI 故障仍返回历史候选；运行 API 与全量服务端测试。

### 任务 4：后台画像训练和 AI 预览

**文件：**
- 修改：`client/src/api/index.ts`
- 修改：`client/src/views/Relationships.vue`
- 创建：`client/tests/relationships-ai.test.ts`

**步骤：** 先写失败测试固定 API 地址和页面关键行为；增加“训练画像”、版本/摘要/证据展示以及 AI 回复预览；运行客户端测试和构建。

### 任务 5：微信页面解析与活动会话

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter/WeChatChatAdapterTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/WeChatChatAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/ActiveChatContextStoreTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/ActiveChatContextStore.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/AdapterRegistry.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/CaptureCoordinator.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`

**步骤：** 用脱敏合成树先写私聊、群聊、方向判断和含糊页跳过测试；实现保守解析器与有 TTL 的共享上下文；运行相关 Android 单测。

### 任务 6：输入法回复候选

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/relationship/RelationshipReplyClientTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/relationship/RelationshipReplyClient.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/relationship/RelationshipReplyControllerTest.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/relationship/RelationshipReplyController.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/keyboard/InputView.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/view/CandidatesBar.kt`

**步骤：** 先写请求 DTO、会话过期、刷新阈值、晚到响应丢弃和点击仅提交文字的失败测试；实现后台请求和候选条；运行相关与全量 Android 单测并构建 Debug APK。

### 任务 7：完成验证与文档

**文件：**
- 修改：`docs/android-integration.md`
- 修改：`docs/plans/2026-08-20-personal-input-method-design.md`

**步骤：** 记录 DeepSeek 环境变量、训练入口、降级行为和微信适配限制；依次运行服务端测试/构建、客户端测试/构建、Android 单测/Debug 构建；检查 `git diff --check` 和工作区，保留用户原有未跟踪文件不动。

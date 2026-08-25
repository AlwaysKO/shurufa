# 零 Token 表情反击链实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 从已采集的真实表情消息中生成按关系分层回退的零 Token 表情反击候选，并提供后台可解释预览。

**架构：** 复用 `chat_message`、`chat_message_asset`、`media_asset` 和 `relationship_profile`，不新增表。TypeScript 查询最近消息后在内存中完成紧邻配对、频次聚合和 SHA-256 去重；Express 暴露移动端与后台接口，Vue 关系页展示最近 incoming 表情和候选。

**技术栈：** Node.js 22、TypeScript、Express、PostgreSQL、Vitest、Supertest、pg-mem、Vue 3。

---

### 任务 1：定义表情候选类型与请求校验

**文件：**
- 修改：`server/src/types/relationship.ts`
- 修改：`server/src/domain/relationshipValidation.ts`
- 修改：`server/src/domain/relationshipValidation.test.ts`

**步骤 1：编写失败测试**

覆盖完整会话身份、可选合法 SHA-256、空 SHA-256、非法哈希、大于 20 的数量和缺失外部会话键。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/domain/relationshipValidation.test.ts`

预期：FAIL，提示 `validateStickerCandidateQuery` 不存在。

**步骤 3：实现最小类型与校验**

新增四种表情候选来源、资源候选字段、查询结果和 `validateStickerCandidateQuery`。复用现有会话身份校验，哈希统一要求 `/^[a-f0-9]{64}$/`，数量默认 6、范围 1–20。

**步骤 4：运行测试并提交**

```bash
cd server
npm test -- src/domain/relationshipValidation.test.ts
npm run build
cd ..
git add server/src/types/relationship.ts server/src/domain/relationshipValidation.ts server/src/domain/relationshipValidation.test.ts
git commit -m "test: 定义表情反击候选协议"
```

### 任务 2：实现四层表情反击候选引擎

**文件：**
- 创建：`server/src/relationship/stickerCounterattack.ts`
- 测试：`server/src/relationship/stickerCounterattack.test.ts`

**步骤 1：编写失败测试**

使用 pg-mem 加载 007–008，准备多个会话、关系档案、消息和资源，覆盖：

- 相同 incoming 资源后紧邻的 outgoing 表情排第一；
- 当前会话、同关系类型、用户通用依次回退；
- `unknown` 跳过关系类型层；
- 同一资源跨层只返回一次；
- 只接受 `emoji`/`sticker`、`content`、`position=0` 的资源；
- 普通图片、incoming 消息和其他用户资源不会成为候选；
- 未知会话安全返回空结果；
- 最近 incoming 表情列表按时间去重。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/relationship/stickerCounterattack.test.ts`

预期：FAIL，模块不存在。

**步骤 3：实现最小引擎**

暴露：

```ts
getStickerCounterattackCandidates(pool, userId, identity, incomingSha256, limit)
getRecentIncomingStickerAssets(pool, userId, conversationId, limit)
```

每层最多读取最近 2,000 条带首个 content 资源的表情消息。反击层把当前会话按时间升序排列，只统计 SHA-256 匹配的 incoming 表情后紧邻的第一条 outgoing 表情。聚合时保留最新资源元数据与时间。

**步骤 4：运行测试、构建并提交**

```bash
cd server
npm test -- src/relationship/stickerCounterattack.test.ts
npm run build
cd ..
git add server/src/relationship/stickerCounterattack.ts server/src/relationship/stickerCounterattack.test.ts
git commit -m "feat: 实现零Token表情反击候选"
```

### 任务 3：增加移动端与后台表情候选 API

**文件：**
- 修改：`server/src/api/mobileRelationships.ts`
- 修改：`server/src/api/relationshipDashboard.ts`
- 修改：`server/src/api/relationships.test.ts`

**步骤 1：编写失败 API 测试**

覆盖移动端完整身份查询、未知会话、非法哈希、后台会话归属、最近 incoming 表情列表和后台候选预览。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/api/relationships.test.ts`

预期：FAIL，新接口返回 404。

**步骤 3：实现接口**

新增：

```text
POST /api/v1/mobile/relationships/sticker-candidates
GET /api/v1/dashboard/relationships/:conversation_id/incoming-sticker-assets
GET /api/v1/dashboard/relationships/:conversation_id/sticker-candidates
```

后台两个接口先确认会话属于当前用户；候选预览复用精确会话身份和同一引擎。

**步骤 4：运行测试、构建并提交**

```bash
cd server
npm test -- src/api/relationships.test.ts
npm run build
cd ..
git add server/src/api/mobileRelationships.ts server/src/api/relationshipDashboard.ts server/src/api/relationships.test.ts
git commit -m "feat: 增加表情反击候选接口"
```

### 任务 4：在关系记忆页增加表情反击预览

**文件：**
- 修改：`client/src/api/index.ts`
- 修改：`client/src/views/Relationships.vue`

**步骤 1：增加前端类型和 API**

定义最近 incoming 表情与表情候选类型，增加列表和预览请求。

**步骤 2：实现预览区域**

选中关系时加载最近 incoming 表情；点击缩略图后请求反击候选，不选时显示关系高频表情。显示候选来源、次数、时间和资源缩略图，不添加发送、删除或 AI 操作。

**步骤 3：构建并提交**

```bash
cd client
npm run build
cd ..
git add client/src/api/index.ts client/src/views/Relationships.vue
git commit -m "feat: 增加表情反击预览"
```

### 任务 5：更新状态并完整验证

**文件：**
- 修改：`docs/plans/2026-08-20-personal-input-method-design.md`

**步骤 1：记录阶段状态**

把表情反击链标记为服务端和后台已实现，继续明确 Android 接入与真机适配未完成。

**步骤 2：运行完整验证**

```bash
cd server
npm test
npm run build
npm run migrate

cd ../client
npm run build

cd ..
bash scripts/check-passive-capture.sh
git diff --check
```

预期：全部退出 0，不新增数据库迁移，静态门禁无禁止调用。

**步骤 3：提交**

```bash
git add docs/plans/2026-08-20-personal-input-method-design.md
git commit -m "docs: 更新表情反击链实现状态"
```

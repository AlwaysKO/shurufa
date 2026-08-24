# 关系档案与零 Token 回复候选实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 为已采集聊天会话建立可人工维护的关系档案，并从用户真实历史回复中生成四层零 Token 候选。

**架构：** PostgreSQL 保存一会话一档案；TypeScript 候选引擎在查询时读取有限条历史文本，在内存中做上下文配对、频次统计、排序和去重。移动端只有提供完整会话身份才可查询，Vue 后台负责档案编辑与候选预览。

**技术栈：** Node.js 22、TypeScript、Express、PostgreSQL、Vitest、Supertest、pg-mem、Vue 3。

---

### 任务 1：定义关系领域类型与严格校验

**文件：**
- 创建：`server/src/types/relationship.ts`
- 创建：`server/src/domain/relationshipValidation.ts`
- 测试：`server/src/domain/relationshipValidation.test.ts`

**步骤 1：编写失败测试**

覆盖合法档案、未知关系类型、超出 0–100 的等级、过长别名与备注，以及候选查询缺少完整会话身份、非法数量。

```ts
expect(() => validateRelationshipProfileInput({
  relationship_type: 'stranger',
  intimacy_level: 50,
  humor_level: 50,
})).toThrow('relationship_type');
```

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/domain/relationshipValidation.test.ts`

预期：FAIL，提示找不到 `relationshipValidation.js`。

**步骤 3：实现最小类型与校验**

定义关系类型枚举、档案更新输入、会话身份、候选来源和候选结果。别名最多 100 字符、备注最多 2,000 字符，等级只接受整数 0–100，候选数量默认 6、范围 1–20。

**步骤 4：运行测试并提交**

```bash
cd server
npm test -- src/domain/relationshipValidation.test.ts
cd ..
git add server/src/types/relationship.ts server/src/domain/relationshipValidation.ts server/src/domain/relationshipValidation.test.ts
git commit -m "test: 建立关系档案领域模型"
```

### 任务 2：新增一会话一关系档案表

**文件：**
- 创建：`server/migrations/008_relationship_profile.sql`
- 测试：`server/src/db/relationshipProfileMigration.test.ts`

**步骤 1：编写失败迁移测试**

先加载 `007_chat_capture.sql`，再加载尚不存在的 `008_relationship_profile.sql`。验证同一用户和会话只能有一条档案、关系类型和两个等级受约束、删除会话级联删除档案。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/db/relationshipProfileMigration.test.ts`

预期：FAIL，迁移文件不存在。

**步骤 3：编写迁移**

创建 `relationship_profile`，字段包括 `user_id`、`conversation_id`、`relationship_type`、`alias`、`intimacy_level`、`humor_level`、`notes`、`created_at`、`updated_at`，并建立 `(user_id, relationship_type)` 索引。

**步骤 4：运行测试并提交**

```bash
cd server
npm test -- src/db/relationshipProfileMigration.test.ts
cd ..
git add server/migrations/008_relationship_profile.sql server/src/db/relationshipProfileMigration.test.ts
git commit -m "feat: 新增聊天关系档案表"
```

### 任务 3：实现四层零 Token 候选引擎

**文件：**
- 创建：`server/src/relationship/zeroTokenCandidates.ts`
- 测试：`server/src/relationship/zeroTokenCandidates.test.ts`

**步骤 1：编写失败测试**

使用 pg-mem 载入 007–008 迁移并准备多个用户、会话和档案，覆盖：

- 相同来信之后的真实回复排第一；
- 回退顺序为当前会话、同关系类型、用户通用；
- `unknown` 跳过关系类型层；
- 归一化文本跨层去重；
- 忽略 incoming、system、非文本、空白和超过 500 字符的消息；
- 不跨用户、不模糊匹配会话；
- 候选最多返回请求数量。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/relationship/zeroTokenCandidates.test.ts`

预期：FAIL，候选引擎模块不存在。

**步骤 3：实现最小查询与纯函数排序**

暴露：

```ts
getZeroTokenCandidates(pool, userId, identity, contextText, limit)
```

先用完整身份精确查询会话；找不到时返回 `conversation_not_found`。每层最多读取最近 2,000 条合格消息，在 TypeScript 中归一化、统计 `use_count`、保留最新原文和时间，并按来源优先级、次数、最近时间合并去重。相同上下文只把历史 incoming 后第一条 outgoing 文本视为回复。

**步骤 4：运行测试、构建并提交**

```bash
cd server
npm test -- src/relationship/zeroTokenCandidates.test.ts
npm run build
cd ..
git add server/src/relationship/zeroTokenCandidates.ts server/src/relationship/zeroTokenCandidates.test.ts
git commit -m "feat: 实现零Token关系回复候选"
```

### 任务 4：增加移动端和后台关系 API

**文件：**
- 创建：`server/src/api/mobileRelationships.ts`
- 创建：`server/src/api/relationshipDashboard.ts`
- 修改：`server/src/app.ts`
- 测试：`server/src/api/relationships.test.ts`

**步骤 1：编写失败 API 测试**

验证：移动端精确身份查询、未知会话安全空结果、后台分页列表、档案 UPSERT、其他用户会话返回 404、非法枚举与等级返回 400，以及后台候选预览。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/api/relationships.test.ts`

预期：FAIL，路由不存在或返回 404。

**步骤 3：实现并挂载路由**

挂载：

```text
POST /api/v1/mobile/relationships/candidates
GET  /api/v1/dashboard/relationships
PUT  /api/v1/dashboard/relationships/:conversation_id
GET  /api/v1/dashboard/relationships/:conversation_id/candidates
```

后台列表对无档案会话返回 `relationship_type=unknown` 和默认等级；更新前确认会话属于当前用户；候选预览通过会话 ID 读取精确身份后复用同一候选引擎。

**步骤 4：运行测试、构建并提交**

```bash
cd server
npm test -- src/api/relationships.test.ts
npm run build
cd ..
git add server/src/api/mobileRelationships.ts server/src/api/relationshipDashboard.ts server/src/api/relationships.test.ts server/src/app.ts
git commit -m "feat: 增加关系档案与候选接口"
```

### 任务 5：增加关系档案后台页面

**文件：**
- 修改：`client/src/api/index.ts`
- 创建：`client/src/views/Relationships.vue`
- 修改：`client/src/main.ts`
- 修改：`client/src/App.vue`

**步骤 1：定义前端 API 类型**

增加关系档案行、更新输入、候选结果类型，以及列表、PUT 更新、候选预览请求。补充通用 `put` JSON helper。

**步骤 2：实现页面**

路由 `/relationships`，导航名“关系记忆”。页面提供：会话列表、关系类型/别名/亲密度/玩笑尺度/备注编辑、保存按钮、上下文输入和候选预览。候选显示来源、次数与最近时间，不添加训练或 AI 操作。

**步骤 3：运行构建并提交**

```bash
cd client
npm run build
cd ..
git add client/src/api/index.ts client/src/views/Relationships.vue client/src/main.ts client/src/App.vue
git commit -m "feat: 增加关系记忆管理页"
```

### 任务 6：全量验证与文档状态

**文件：**
- 修改：`docs/plans/2026-08-20-personal-input-method-design.md`

**步骤 1：更新实现状态**

只记录本阶段已经实现的服务端关系档案、零 Token 候选和后台页面；明确 Android 候选栏接入及真实应用适配仍待完成。

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

预期：全部退出 0；服务端迁移执行到 008；静态门禁无禁止调用。

**步骤 3：提交**

```bash
git add docs/plans/2026-08-20-personal-input-method-design.md
git commit -m "docs: 更新关系候选实现状态"
```

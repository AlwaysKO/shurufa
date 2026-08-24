# 关系档案与零 Token 回复候选设计

日期：2026-08-24
状态：已确认，进入实现

## 1. 目标与边界

本阶段在现有 `chat_conversation`、`chat_message` 和聊天采集后台之上，增加可人工维护的关系档案，并从用户真实发送过的历史文本中生成零 Token 回复候选。候选生成不调用大模型、不生成新句子，只做确定性的历史匹配、频次统计、去重和分层回退。

本阶段不实现微信、QQ、抖音页面适配器，不在 Android 端猜测当前联系人，也不把关系候选注入键盘。移动端 API 必须收到完整的 `platform + account_key + external_key` 会话身份才返回候选；无法唯一定位会话时返回空结果。真实应用适配器完成后再接入候选栏。

## 2. 数据模型

新增 `relationship_profile`，每条记录严格绑定一个已有聊天会话：

- `user_id` 与 `conversation_id` 唯一，防止同一会话出现多个档案；
- `relationship_type` 支持 `unknown`、`friend`、`family`、`partner`、`colleague`、`customer`、`group`、`other`；
- `alias`、`intimacy_level`、`humor_level`、`notes` 由后台人工维护；
- 两个等级字段范围为 0–100；
- 未创建档案的会话按 `unknown` 展示，不自动猜测关系。

首版不新增候选物化表。候选直接从聊天消息查询，保证导入新消息后立即生效，并避免为尚未验证的规模引入定时聚合任务。

## 3. 候选路由

候选只允许使用 `direction = outgoing`、`message_type = text` 且非空、长度不超过 500 字符的真实历史消息。文本通过去除两端空白、合并连续空白进行归一化，但返回原始文本。

查询依次使用四层来源：

1. **相同上下文**：当前会话中，历史上与本次输入上下文相同的来信之后，用户实际发送的第一条回复；
2. **当前关系高频**：当前会话内用户最常发送的文本；
3. **关系类型高频**：其他同 `relationship_type` 会话中的用户高频文本；`unknown` 不使用这一层；
4. **用户通用高频**：用户所有会话中的高频文本。

每层按使用次数、最近使用时间排序，跨层按归一化文本去重，默认最多返回 6 条、硬上限 20 条。结果携带来源、使用次数和最近使用时间，便于后台解释，不返回 AI 标记。

## 4. API 与后台

移动端新增：

```text
POST /api/v1/mobile/relationships/candidates
```

请求包含完整会话身份、可选 `context_text` 和 `limit`。会话不存在时返回 `200`、空候选和 `conversation_not_found`，不尝试模糊匹配。

后台新增：

```text
GET /api/v1/dashboard/relationships
PUT /api/v1/dashboard/relationships/:conversation_id
GET /api/v1/dashboard/relationships/:conversation_id/candidates
```

管理页面 `/relationships` 提供会话列表、关系类型和等级编辑，以及输入一段上下文后的候选预览。首版不提供删除档案、AI 训练、批量分类或自动关系推断。

## 5. 错误处理与安全

- 所有枚举和等级在服务端严格校验，不静默修正；
- 更新关系档案前确认会话属于当前用户；
- 候选查询不得跨用户；
- 空上下文仍可返回频次候选；
- 未知关系类型不参与关系类型回退；
- 数据库或查询异常交给现有 Express 错误中间件，不返回聊天正文到日志；
- Android 在能够可靠识别会话前不消费该 API，优先避免关系串台。

## 6. 验证

自动测试覆盖：迁移唯一约束和字段范围、档案输入校验、四层候选顺序、去重、只使用真实发出文本、用户隔离、未知会话安全返回、后台档案读写和分页。完成后运行服务端全量测试与构建、数据库迁移、客户端生产构建、静态差异检查。

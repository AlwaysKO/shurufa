# 无感聊天采集与双层去重实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划。

**目标：** 在不点击、不滚动、不切换、不发送的前提下，为微信、QQ、抖音建立可持久化的被动聊天采集链路，并完成消息级与媒体资源级双层去重以及后台可观测页面。

**架构：** Android 无障碍服务只读取当前活动窗口，把页面转换为与 Android API 解耦的节点快照，再交给三个应用适配器解析。文本消息和截图裁剪媒体进入独立 Room outbox，上传到服务端后由 PostgreSQL 以消息指纹和内容哈希分别去重；后台仅提供采集结果与适配状态查看。本计划不实现关系训练、AI 回复、DeepSeek 中转或智能候选栏。

**技术栈：** Kotlin、Android `AccessibilityService`、Room、OkHttp、JUnit/Robolectric、Node.js 22、TypeScript、Express、PostgreSQL、Vitest、Supertest、pg-mem、Vue 3。

---

## 范围与约束

- 生产代码中禁止调用 `performAction`、`dispatchGesture`、`performGlobalAction`，禁止模拟点击、滚动或返回。
- 只采集用户自然浏览时已出现在当前窗口中的内容。
- Android 11（API 30）以下只采集节点中可读取的文本和元数据，不截图。
- 首版允许媒体只有屏幕显示质量；动态表情只保存当前可见帧。
- 会话标题为空、方向不明确或节点边界异常时跳过，不猜测归属。
- 微信、QQ、抖音适配必须先取得真实节点夹具再编写规则；没有真实夹具时不得用臆测资源 ID 宣称支持。
- 所有网络、截图、哈希、压缩和数据库操作离开输入法主线程。
- 每个任务完成后单独提交，提交信息使用中文。

## 任务 1：建立服务端聊天领域模型与测试基线

**文件：**
- 修改：`server/package.json`
- 修改：`server/package-lock.json`
- 创建：`server/src/types/chat.ts`
- 创建：`server/src/domain/chatValidation.ts`
- 测试：`server/src/domain/chatValidation.test.ts`

**步骤 1：安装测试依赖并添加脚本**

运行：

```bash
cd server
npm install -D vitest@4.1.11 supertest@7.2.2 @types/supertest@7.2.1 pg-mem@3.0.14
```

在 `server/package.json` 增加：

```json
{
  "scripts": {
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

**步骤 2：编写失败测试**

测试必须覆盖：合法消息、未知平台、未知消息类型、非 64 位十六进制指纹、过长文本和空会话键。

```ts
import { describe, expect, it } from 'vitest';
import { validateCapturedMessage } from './chatValidation.js';

describe('validateCapturedMessage', () => {
  it('拒绝非法消息指纹', () => {
    expect(() => validateCapturedMessage({
      id: crypto.randomUUID(),
      fingerprint: 'bad',
      content_fingerprint: '0'.repeat(64),
      sender_key: 'peer',
      direction: 'incoming',
      message_type: 'text',
      captured_at: new Date().toISOString(),
    })).toThrow('fingerprint');
  });
});
```

**步骤 3：运行测试验证失败**

运行：`cd server && npm test -- src/domain/chatValidation.test.ts`

预期：FAIL，提示找不到 `chatValidation.js`。

**步骤 4：实现最小类型与校验**

`server/src/types/chat.ts` 至少定义：

```ts
export const CHAT_PLATFORMS = ['wechat', 'qq', 'douyin'] as const;
export const CHAT_DIRECTIONS = ['incoming', 'outgoing', 'system'] as const;
export const CHAT_MESSAGE_TYPES = [
  'text', 'emoji', 'image', 'sticker', 'video', 'voice', 'link',
  'file', 'music', 'location', 'contact', 'mini_app', 'red_packet',
  'transfer', 'system', 'recalled', 'unknown',
] as const;

export interface CapturedConversationInput {
  platform: typeof CHAT_PLATFORMS[number];
  account_key: string;
  external_key: string;
  display_name?: string | null;
  conversation_type: 'direct' | 'group' | 'unknown';
  identity_confidence: number;
}

export interface CapturedMessageInput {
  id: string;
  fingerprint: string;
  content_fingerprint: string;
  sender_key: string;
  sender_name?: string | null;
  direction: typeof CHAT_DIRECTIONS[number];
  message_type: typeof CHAT_MESSAGE_TYPES[number];
  text?: string | null;
  displayed_time?: string | null;
  occurred_at?: string | null;
  captured_at: string;
  sequence_hint?: number | null;
  asset_sha256?: string[];
  metadata?: Record<string, unknown>;
}
```

校验器不得静默修正未知枚举；文本截断上限设为 20,000 字符，指纹统一要求 `/^[a-f0-9]{64}$/`。

**步骤 5：运行测试与构建**

运行：

```bash
cd server
npm test -- src/domain/chatValidation.test.ts
npm run build
```

预期：测试 PASS，TypeScript 编译退出 0。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add server/package.json server/package-lock.json server/src/types/chat.ts server/src/domain/chatValidation.ts server/src/domain/chatValidation.test.ts
git commit -m "test: 建立聊天采集领域模型"
```

## 任务 2：创建服务端消息与媒体双层去重表

**文件：**
- 创建：`server/migrations/007_chat_capture.sql`
- 创建：`server/src/db/chatCaptureMigration.test.ts`

**步骤 1：编写失败的迁移测试**

使用 `pg-mem` 只加载 `007_chat_capture.sql`，验证：

- 同一用户、平台、账号和外部会话键只能有一个会话；
- 同一用户、平台和消息指纹只能有一条消息；
- 同一用户和 SHA-256 只能有一个媒体资源；
- 同一消息和资源只能关联一次。

预期测试形态：

```ts
await pool.query(sql);
await pool.query(`INSERT INTO media_asset (user_id, sha256, storage_path, byte_size)
  VALUES ($1, $2, $3, $4)`, [userId, 'a'.repeat(64), 'chat/aa/a.webp', 10]);
await expect(pool.query(/* 再次插入 */)).rejects.toThrow();
```

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/db/chatCaptureMigration.test.ts`

预期：FAIL，提示迁移文件不存在。

**步骤 3：编写迁移**

迁移创建四张表：

```sql
CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    platform VARCHAR(20) NOT NULL,
    account_key TEXT NOT NULL,
    external_key TEXT NOT NULL,
    display_name TEXT,
    conversation_type VARCHAR(20) NOT NULL,
    identity_confidence NUMERIC(4,3) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, account_key, external_key)
);

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    sha256 CHAR(64) NOT NULL,
    perceptual_hash VARCHAR(64),
    mime_type VARCHAR(100) NOT NULL,
    storage_path TEXT NOT NULL,
    byte_size BIGINT NOT NULL,
    width INT,
    height INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, sha256)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    content_fingerprint CHAR(64) NOT NULL,
    sender_key TEXT NOT NULL,
    sender_name TEXT,
    direction VARCHAR(20) NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    text TEXT,
    displayed_time TEXT,
    occurred_at TIMESTAMPTZ,
    captured_at TIMESTAMPTZ NOT NULL,
    sequence_hint BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, platform, fingerprint)
);

CREATE TABLE IF NOT EXISTS chat_message_asset (
    message_id UUID NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    asset_id BIGINT NOT NULL REFERENCES media_asset(id) ON DELETE RESTRICT,
    role VARCHAR(30) NOT NULL DEFAULT 'content',
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, asset_id, role)
);
```

补充会话时间、消息会话时间、媒体感知哈希索引。

**步骤 4：运行迁移测试和真实迁移**

运行：

```bash
cd server
npm test -- src/db/chatCaptureMigration.test.ts
npm run migrate
psql -d personal_ime -c "\\d chat_message"
```

预期：测试 PASS，真实数据库出现四张新表及唯一约束。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add server/migrations/007_chat_capture.sql server/src/db/chatCaptureMigration.test.ts
git commit -m "feat: 新增聊天消息与媒体去重表"
```

## 任务 3：实现服务端幂等消息写入仓库

**文件：**
- 创建：`server/src/chat/chatRepository.ts`
- 测试：`server/src/chat/chatRepository.test.ts`

**步骤 1：编写失败测试**

测试同一批消息重复写入两次后：会话 1 条、消息 1 条，第二次返回 `inserted: 0, duplicated: 1`。再测试相同文本但不同消息指纹会保留两条。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/chat/chatRepository.test.ts`

预期：FAIL，模块不存在。

**步骤 3：实现仓库事务**

暴露：

```ts
export async function ingestCapturedMessages(
  pool: pg.Pool,
  userId: string,
  deviceId: string,
  conversation: CapturedConversationInput,
  messages: CapturedMessageInput[],
): Promise<{ conversationId: number; inserted: number; duplicated: number; missingAssets: string[] }>;
```

实现要求：

1. `BEGIN`；
2. UPSERT `chat_conversation` 并返回 ID；
3. 查询本批 `asset_sha256` 是否存在；
4. 对缺失资源返回 `missingAssets`，相关消息不提前落库；
5. 消息 `ON CONFLICT (user_id, platform, fingerprint) DO NOTHING`；
6. 写 `chat_message_asset`；
7. `COMMIT`；任何异常 `ROLLBACK`。

不要把 SQL 拼接输入值；批量参数化且每批最多 200 条。

**步骤 4：运行测试**

运行：`cd server && npm test -- src/chat/chatRepository.test.ts`

预期：全部 PASS。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add server/src/chat/chatRepository.ts server/src/chat/chatRepository.test.ts
git commit -m "feat: 实现聊天消息幂等写入"
```

## 任务 4：实现媒体内容寻址上传接口

**文件：**
- 创建：`server/src/chat/assetStorage.ts`
- 创建：`server/src/api/chatCapture.ts`
- 修改：`server/src/app.ts`
- 测试：`server/src/chat/assetStorage.test.ts`
- 测试：`server/src/api/chatCapture.test.ts`

**步骤 1：编写失败测试**

覆盖：

- 上传 PNG/WebP 后按服务端实际字节重新计算 SHA-256；
- 客户端声明哈希不匹配返回 400；
- 同一文件上传两次只产生一个数据库资源和一个磁盘文件；
- 非允许 MIME、空文件、超过 5MB 返回 400；
- 批量消息接口重复提交返回正确 inserted/duplicated。

**步骤 2：运行测试验证失败**

运行：`cd server && npm test -- src/chat/assetStorage.test.ts src/api/chatCapture.test.ts`

预期：FAIL，模块或路由不存在。

**步骤 3：实现内容寻址存储**

文件路径固定为：

```text
server/uploads/chat/<sha256 前两位>/<sha256>.<扩展名>
```

核心签名：

```ts
export async function storeAsset(
  pool: pg.Pool,
  userId: string,
  input: { sha256: string; mime_type: string; file_base64: string; perceptual_hash?: string; width?: number; height?: number },
): Promise<{ id: number; sha256: string; duplicated: boolean }>;
```

先解码、校验大小、计算真实哈希，再写临时文件并原子重命名。数据库写入使用 `ON CONFLICT (user_id, sha256)`。

**步骤 4：挂载路由**

在 `createMobileRouter` 之外独立挂载：

```ts
app.use('/api/v1/mobile/chat', createMobileChatCaptureRouter(pool));
```

提供：

- `POST /api/v1/mobile/chat/assets`
- `POST /api/v1/mobile/chat/messages/batch`

消息接口必须验证 `device_id`、conversation、最多 200 条消息和每条字段枚举。

**步骤 5：运行测试和构建**

```bash
cd server
npm test -- src/chat/assetStorage.test.ts src/api/chatCapture.test.ts
npm run build
```

预期：测试 PASS，构建退出 0。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add server/src/app.ts server/src/api/chatCapture.ts server/src/chat/assetStorage.ts server/src/chat/assetStorage.test.ts server/src/api/chatCapture.test.ts
git commit -m "feat: 新增聊天消息与媒体上传接口"
```

## 任务 5：增加采集后台只读页面

**文件：**
- 创建：`server/src/api/chatDashboard.ts`
- 修改：`server/src/app.ts`
- 修改：`client/src/api/index.ts`
- 创建：`client/src/views/ChatCapture.vue`
- 修改：`client/src/main.ts`
- 修改：`client/src/App.vue`
- 测试：`server/src/api/chatDashboard.test.ts`

**步骤 1：编写失败的 API 测试**

验证以下接口返回稳定字段且支持分页：

- `GET /api/v1/dashboard/chat/overview`
- `GET /api/v1/dashboard/chat/conversations`
- `GET /api/v1/dashboard/chat/messages?conversation_id=&page=&page_size=`

概览至少返回会话数、消息数、媒体数、重复消息拒绝数暂不实现；重复数由上传响应和客户端日志观察，避免本阶段新增统计表。

**步骤 2：实现最小查询路由**

消息列表返回资源 URL、方向、类型、发送者、文本、时间和平台。静态资源继续复用 `/uploads`。

**步骤 3：运行服务端测试**

运行：`cd server && npm test -- src/api/chatDashboard.test.ts`

预期：PASS。

**步骤 4：实现 Vue 页面**

页面只包含：概览卡片、会话列表、选中会话后的消息时间线、消息类型筛选和媒体缩略图。不要在本阶段加入编辑、训练或删除能力。

路由：`/chat-capture`，导航名：`聊天采集`。

**步骤 5：运行前后端构建**

```bash
cd server && npm run build
cd ../client && npm run build
```

预期：两个构建均退出 0。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add server/src/api/chatDashboard.ts server/src/api/chatDashboard.test.ts server/src/app.ts client/src/api/index.ts client/src/views/ChatCapture.vue client/src/main.ts client/src/App.vue
git commit -m "feat: 新增聊天采集查看页"
```

## 任务 6：建立 Android 采集模型与纯函数指纹

**文件：**
- 修改：`android/YuyanIme/yuyansdk/build.gradle`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/model/CapturedConversation.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/model/CapturedMessage.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/Fingerprint.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/FingerprintTest.kt`

**步骤 1：添加本地单元测试依赖**

```gradle
testImplementation 'junit:junit:4.13.2'
```

**步骤 2：编写失败测试**

至少覆盖：

- 文本两端空白和连续空白被标准化；
- 同一消息在重叠页面中生成相同指纹；
- 相同文本但不同显示时间生成不同消息指纹；
- 相同资源字节生成相同 SHA-256；
- 会话键缺失时返回 null 而不是猜测。

```kotlin
@Test
fun sameMessageInOverlappingViewportHasSameFingerprint() {
    val a = messageFingerprint(sample.copy(viewportIndex = 1))
    val b = messageFingerprint(sample.copy(viewportIndex = 5))
    assertEquals(a, b) // 视口位置不得进入稳定指纹
}
```

**步骤 3：运行测试验证失败**

运行：

```bash
source ~/android-tools/env.sh
cd android/YuyanIme
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
```

预期：FAIL，采集模型或指纹函数不存在。

**步骤 4：实现最小指纹算法**

消息指纹组成：

```text
conversationKey | senderKey | direction | messageType |
contentFingerprint | displayedTime | previousContentFingerprint |
nextContentFingerprint | sameContentOrdinal
```

视口绝对坐标和列表位置不得进入稳定指纹。`contentFingerprint` 由标准化文本、消息类型和资源 SHA-256 组成。

**步骤 5：运行测试并提交**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/build.gradle android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture
git commit -m "feat: 建立聊天采集模型与稳定指纹"
```

## 任务 7：实现独立 Room 去重库与持久化 outbox

**文件：**
- 修改：`android/YuyanIme/yuyansdk/build.gradle`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/db/CaptureDatabase.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/db/CaptureDao.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/db/CaptureEntities.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/db/CaptureDatabaseTest.kt`

**步骤 1：添加测试依赖**

```gradle
testImplementation 'androidx.test:core:1.6.1'
testImplementation 'org.robolectric:robolectric:4.14.1'
testImplementation 'androidx.room:room-testing:2.6.1'
```

**步骤 2：编写失败测试**

测试：

- `seen_message` 重复插入使用 IGNORE，只保留一条；
- `pending_asset` 以 SHA-256 为主键；
- `pending_message` 仅在所需资源上传完成后可被取出；
- 超过 50,000 条 seen 记录时按最早时间裁剪；
- 上传确认后删除 outbox，但保留 seen 指纹。

**步骤 3：运行测试验证失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand`

预期：FAIL，Room 数据库不存在。

**步骤 4：实现独立数据库**

使用单独文件 `capture.db`，不要把采集 outbox 加入已有允许主线程查询的 `ime_db`。

实体：

```kotlin
@Entity(tableName = "seen_message")
data class SeenMessageEntity(
    @PrimaryKey val fingerprint: String,
    val firstSeenAt: Long,
)

@Entity(tableName = "pending_asset")
data class PendingAssetEntity(
    @PrimaryKey val sha256: String,
    val localPath: String,
    val mimeType: String,
    val perceptualHash: String?,
    val width: Int?,
    val height: Int?,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
)

@Entity(tableName = "pending_message")
data class PendingMessageEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val conversationKey: String,
    val payloadJson: String,
    val requiredAssetHashesJson: String,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
)
```

**步骤 5：运行测试并提交**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/build.gradle android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/db android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/db
git commit -m "feat: 新增采集去重库与持久化队列"
```

## 任务 8：实现 Android 媒体优先的可靠上传器

**文件：**
- 修改：`android/YuyanIme/yuyansdk/build.gradle`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/net/CaptureApi.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/net/CaptureUploader.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/net/CaptureUploaderTest.kt`

**步骤 1：添加 MockWebServer**

```gradle
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
```

**步骤 2：编写失败测试**

覆盖：

- 先上传资源，再上传引用该资源的消息；
- 资源响应 duplicated 仍视为成功；
- 429/500/断网后保留同一 outbox ID 和消息指纹；
- 指数退避有上限，不产生紧密重试循环；
- 消息成功后删除本地临时资源文件和 outbox；
- 单批最多 200 条消息。

**步骤 3：运行测试验证失败**

运行：`./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand`

预期：FAIL，上传器不存在。

**步骤 4：实现上传循环**

`CaptureUploader.start(context)` 使用 `SupervisorJob + Dispatchers.IO`，应用启动时恢复持久化队列。退避建议：30 秒、2 分钟、10 分钟、30 分钟，最大 30 分钟。

所有异常静默写入内部计数，不显示 Toast。不要复用只保存在内存中的 `DataCollector.queue`。

**步骤 5：运行测试并提交**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/build.gradle android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/net android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/net
git commit -m "feat: 实现聊天采集可靠上传队列"
```

## 任务 9：声明只读无障碍服务并添加被动性门禁

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/res/xml/passive_chat_accessibility_service.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`
- 创建：`scripts/check-passive-capture.sh`

**步骤 1：先编写会失败的静态门禁脚本**

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule"
if rg -n 'performAction\(|dispatchGesture\(|performGlobalAction\(' "$ROOT/data/capture" "$ROOT/service/capture"; then
  echo "发现禁止的主动 UI 操作 API" >&2
  exit 1
fi
```

先运行：`bash scripts/check-passive-capture.sh`

在服务尚不存在时脚本应因路径不存在失败；调整脚本只允许不存在目录时给出明确失败。

**步骤 2：声明服务配置**

XML 限制包名：

```xml
android:packageNames="com.tencent.mm,com.tencent.mobileqq,com.ss.android.ugc.aweme"
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"
android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
android:canRetrieveWindowContent="true"
android:canTakeScreenshot="true"
android:notificationTimeout="300"
```

Manifest 服务使用 `android.permission.BIND_ACCESSIBILITY_SERVICE`。不要申请 Root、悬浮窗或媒体投屏权限。

**步骤 3：实现最小只读服务**

```kotlin
class PassiveChatAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in SUPPORTED_PACKAGES) return
        coordinator.onWindowChanged(event.packageName.toString(), event.windowId)
    }

    override fun onInterrupt() = Unit
}
```

此任务只接收事件，不解析、不截图。

**步骤 4：运行门禁和构建**

```bash
bash scripts/check-passive-capture.sh
source ~/android-tools/env.sh
cd android/YuyanIme
./gradlew :yuyansdk:assembleOfflineDebug --configure-on-demand
```

预期：门禁无匹配，AAR 构建成功。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add scripts/check-passive-capture.sh android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml android/YuyanIme/yuyansdk/src/main/res/xml/passive_chat_accessibility_service.xml android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture
git commit -m "feat: 新增只读聊天无障碍服务"
```

## 任务 10：实现节点快照和滚动稳定去抖

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/ui/UiNodeSnapshot.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/ui/AccessibilityTreeReader.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/ui/ViewportDebouncer.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/ui/ViewportDebouncerTest.kt`

**步骤 1：编写失败测试**

验证：

- 300ms 内连续内容变化只触发一次；
- 相同视口签名不重复解析；
- 新签名在稳定 300ms 后触发；
- 新窗口出现时旧等待任务失效；
- 节点树最大深度和最大节点数达到上限后停止，防止卡顿。

**步骤 2：实现 Android 无关的快照 DTO**

```kotlin
data class UiNodeSnapshot(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: IntRect,
    val children: List<UiNodeSnapshot>,
)
```

读取 `AccessibilityNodeInfo` 后立即复制必要字段，不把节点对象传到后台线程。

**步骤 3：接入服务**

主线程只完成有上限的节点字段复制；解析和指纹计算进入单线程后台调度器。相同窗口和相同树摘要直接丢弃。

**步骤 4：运行测试、门禁和构建**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest :yuyansdk:assembleOfflineDebug --configure-on-demand
cd ../..
bash scripts/check-passive-capture.sh
```

预期：测试和构建成功，门禁无禁止调用。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/ui android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/ui
git commit -m "feat: 增加聊天页面快照与无感去抖"
```

## 任务 11：采集真实节点夹具并实现三个应用适配器

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/ChatAppAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/WechatAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/QqAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/DouyinAdapter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter/AdapterRegistry.kt`
- 创建：`android/YuyanIme/yuyansdk/src/test/resources/capture/wechat/*.json`
- 创建：`android/YuyanIme/yuyansdk/src/test/resources/capture/qq/*.json`
- 创建：`android/YuyanIme/yuyansdk/src/test/resources/capture/douyin/*.json`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter/WechatAdapterTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter/QqAdapterTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter/DouyinAdapterTest.kt`

**步骤 1：连接测试手机并收集夹具**

当前环境没有已连接 ADB 设备。执行本任务前必须：

```bash
source ~/android-tools/env.sh
adb devices -l
```

在三个应用中分别准备：私聊文本、群聊文本、连续重复文本、图片、表情、视频、语音、链接/卡片以及系统消息页面。通过仅限 debug 构建的快照导出入口把 `UiNodeSnapshot` JSON 拉到上述 test resources；夹具必须脱敏，但保留节点结构、相对坐标和资源 ID。

**步骤 2：先为每个夹具编写失败测试**

每个适配器至少断言：

- 会话标题和 direct/group 类型；
- 发送方向；
- 发送者键；
- 消息类型；
- 文本或媒体边界；
- 无法确认会话身份的夹具返回 `Skip(AmbiguousConversation)`。

**步骤 3：定义适配器接口**

```kotlin
interface ChatAppAdapter {
    val packageName: String
    fun parse(root: UiNodeSnapshot): ParseResult
}

sealed interface ParseResult {
    data class Success(val viewport: ParsedViewport) : ParseResult
    data class Skip(val reason: SkipReason) : ParseResult
}
```

禁止在适配器中持有 `AccessibilityNodeInfo` 或调用任何 UI 动作。

**步骤 4：按夹具逐个实现最小规则**

不要建立跨应用“万能猜测解析器”。每个应用只识别测试覆盖的结构；未知版本返回 Skip。会话键由包名、账号键、会话类型、标题及可见稳定副标题组成，身份置信度低于 0.8 不上传。

**步骤 5：运行测试与被动性门禁**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest --configure-on-demand
cd ../..
bash scripts/check-passive-capture.sh
```

预期：三个适配器夹具测试 PASS，门禁无匹配。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/adapter android/YuyanIme/yuyansdk/src/test/resources/capture android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter
git commit -m "feat: 增加微信QQ抖音聊天解析适配器"
```

## 任务 12：把文本与元数据解析结果接入本地去重队列

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/CaptureCoordinator.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/application/Launcher.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/CaptureCoordinatorTest.kt`

**步骤 1：编写失败测试**

使用 fake adapter、fake DAO 和 fake clock 验证：

- 第一次视口解析写入新消息；
- 同一视口第二次不写 outbox；
- 重叠视口只写新增消息；
- 相同文本的两条真实消息均保留；
- Skip 结果不写数据库；
- 解析异常被吞掉并记内部计数，不向服务线程抛出。

**步骤 2：实现协调器**

流程固定为：

```text
snapshot → adapter.parse → fingerprint → seen INSERT IGNORE
→ 仅对新 fingerprint 写 pending_message → uploader 唤醒
```

同一事务中写 seen 与 pending，避免进程中断造成“已见但未上传”。

**步骤 3：初始化上传器**

在 `Launcher.onInitDataChildThread()` 调用 `CaptureUploader.start(context)`，但无障碍服务本身由系统按权限启停，不由应用主动启动。

**步骤 4：运行测试和构建**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest :yuyansdk:assembleOfflineDebug --configure-on-demand
```

预期：PASS。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/CaptureCoordinator.kt android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveChatAccessibilityService.kt android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/application/Launcher.kt android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/CaptureCoordinatorTest.kt
git commit -m "feat: 接通无感文本消息采集链路"
```

## 任务 13：实现窗口截图、媒体裁剪和资源双层去重

**文件：**
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/media/WindowScreenshotter.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/media/MediaCropper.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/media/ImageHash.kt`
- 修改：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/CaptureCoordinator.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/media/MediaCropperTest.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/media/ImageHashTest.kt`

**步骤 1：编写失败测试**

覆盖：

- 多个媒体节点共用同一张窗口截图；
- 裁剪矩形被安全限制在窗口范围；
- 0 面积、过小、覆盖输入区或超出窗口的矩形被拒绝；
- 相同裁剪图 SHA-256 相同；
- 缩放或轻微压缩图 dHash 相近；
- 相同资源只写一个 pending_asset，但可被多条 pending_message 引用。

**步骤 2：实现截图版本降级**

- API 34+：优先 `takeScreenshotOfWindow(windowId, ...)`；
- API 30–33：`takeScreenshot(Display.DEFAULT_DISPLAY, ...)`；
- API 23–29：返回 `Unsupported`，文本采集继续。

安全窗口或截图频率过快错误直接跳过，不切换到 MediaProjection。

**步骤 3：实现后台裁剪与存储**

一次截图后在 IO/Default 调度器裁剪所有媒体区域，压缩为 WebP 或 PNG，写入 `cacheDir/chat-capture/<sha256>`，再写 `pending_asset`。Bitmap 用完立即回收引用，不在队列中保存 Bitmap。

**步骤 4：接入消息关联**

媒体消息只有在裁剪资源成功落地后才写入带 `asset_sha256` 的 pending_message；裁剪失败时可保存媒体元数据消息，但 metadata 标记 `asset_capture_failed`。

**步骤 5：运行测试、门禁和构建**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest :yuyansdk:assembleOfflineDebug --configure-on-demand
cd ../..
bash scripts/check-passive-capture.sh
```

预期：全部成功，无禁止 API。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/media android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/CaptureCoordinator.kt android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/media
git commit -m "feat: 增加聊天媒体裁剪与内容去重"
```

## 任务 14：增加通知增量采集并复用消息去重

**文件：**
- 修改：`android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveNotificationListener.kt`
- 创建：`android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/notification/NotificationParser.kt`
- 测试：`android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/notification/NotificationParserTest.kt`

**步骤 1：编写失败测试**

把 Android `Notification` 先转换为可测试 DTO，覆盖：

- 微信、QQ、抖音普通文本通知；
- 群聊标题和发送者拆分；
- 缺少标题或正文时跳过；
- 通知更新重复投递不重复入库；
- 页面采集到同一条消息时通过稳定指纹或内容邻接合并；
- 通知提供媒体 URI 时只在 URI 可读时采集，否则仅保存类型元数据。

**步骤 2：实现通知监听服务**

Manifest 使用 `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`。服务只处理 `onNotificationPosted`，不取消通知、不回复、不点击 PendingIntent。

**步骤 3：接入统一协调器**

通知来源消息 metadata 标记 `capture_source=notification`、较低身份置信度；用户打开页面后用更高置信度页面消息补充字段，但不得生成重复消息。

**步骤 4：运行测试、门禁和构建**

```bash
./gradlew :yuyansdk:testOfflineDebugUnitTest :yuyansdk:assembleOfflineDebug --configure-on-demand
cd ../..
bash scripts/check-passive-capture.sh
```

预期：全部成功。

**步骤 5：提交**

```bash
cd /home/ko/project/shurufa
git add android/YuyanIme/yuyansdk/src/main/AndroidManifest.xml android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/service/capture/PassiveNotificationListener.kt android/YuyanIme/yuyansdk/src/main/java/com/yuyan/imemodule/data/capture/notification android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/notification
git commit -m "feat: 增加聊天通知无感增量采集"
```

## 任务 15：端到端去重、无感与性能验收

**文件：**
- 修改：`docs/android-integration.md`
- 创建：`docs/testing/passive-chat-capture-checklist.md`

**步骤 1：运行全部自动验证**

```bash
cd server
npm test
npm run build
npm run migrate

cd ../client
npm run build

cd ../android/YuyanIme
source ~/android-tools/env.sh
./gradlew :yuyansdk:testOfflineDebugUnitTest :app:assembleOfflineDebug --configure-on-demand

cd ../..
bash scripts/check-passive-capture.sh
git diff --check
```

预期：所有命令退出 0，门禁不输出禁止调用。

**步骤 2：连接真机进行重复滚动验收**

安装 APK，首次手动启用输入法、无障碍和通知读取权限。分别在微信、QQ、抖音：

1. 打开固定会话并手动滚动一段历史；
2. 等待上传完成；
3. 再次滚动完全相同范围；
4. 查询消息数量不得增加；
5. 发送一条与旧消息文本相同的新消息，数量必须增加；
6. 重复出现同一表情时媒体文件数不增加，消息使用次数增加。

查询：

```sql
SELECT platform, COUNT(*) FROM chat_message GROUP BY platform;
SELECT sha256, COUNT(*) FROM media_asset GROUP BY sha256 HAVING COUNT(*) > 1;
SELECT fingerprint, COUNT(*) FROM chat_message GROUP BY fingerprint HAVING COUNT(*) > 1;
```

后两条查询预期均为 0 行。

**步骤 3：无感行为验收**

测试过程中确认：

- 页面没有自动滚动、点击、返回或焦点变化；
- 没有采集 Toast、振动、悬浮提示和录屏授权；
- 安全窗口截图失败时没有替代操作；
- 断网、服务端停止和上传失败不影响键盘输入；
- 无法识别会话时后台没有错误归属消息。

**步骤 4：性能验收**

使用 `adb shell dumpsys gfxinfo <package>` 和 Android Studio Profiler 对比开启/关闭采集：

- 快速滚动不逐帧截图；
- 主线程无磁盘和网络 I/O；
- 键盘按键与候选新增延迟目标低于 10ms；
- 连续浏览 30 分钟后无持续增长的 Bitmap、节点或 outbox 内存；
- 上传确认后临时媒体文件被清理。

**步骤 5：记录已支持的应用版本与已知限制**

在 `docs/android-integration.md` 写明真实验证过的微信、QQ、抖音版本、Android 版本、截图最低 API 和首次权限开启步骤。在测试清单记录每种消息类型的通过/跳过结果。

**步骤 6：提交**

```bash
cd /home/ko/project/shurufa
git add docs/android-integration.md docs/testing/passive-chat-capture-checklist.md
git commit -m "docs: 补充无感聊天采集验收记录"
```

## 完成定义

只有同时满足以下条件，本计划才算完成：

1. 服务端、客户端和 Android 构建全部通过；
2. 服务端与 Android 单元测试全部通过；
3. 三个应用均使用真实节点夹具完成适配器回归测试；
4. 真机重复滚动不产生重复消息或重复媒体文件；
5. 同内容的新消息不会被错误去重；
6. 静态门禁确认不存在主动 UI 操作 API；
7. 真机确认采集不造成自动操作、可见提示或键盘卡顿；
8. 文档记录真实验证版本和仍会保守跳过的页面类型。

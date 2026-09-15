# 表情语义素材组与完整文字变体实现计划

> **For Claude：** 必需子技能：使用 superpowers:executing-plans 来逐任务实现此计划；在当前工作目录工作，不创建 worktree。

**目标：** 同义及可共享动作的近义表达复用四套动画；有限口语尾词由完整文字变体覆盖，减少重复画图。

**架构：** 查询词/有限句式→受控语义素材组→四个不同风格的无字动作母版→确定性完整排字→服务端预制 GIF 与首帧 WebP。查询分组与完整 caption 身份分离，Android 只加载、缓存和发送成品，不现场编码。

**技术栈：** 本轮仅 JSON 草案、Markdown 及只读 Python 校验；未来实现使用现有 TypeScript 生成/接口链与 Kotlin catalog/cache，逐项 TDD 后接入。

日期：2026-09-09。状态：**待确认；仅设计与数据，无生产代码、无新图片、无 APK 构建。**

## 方案取舍

1. **推荐：有限语义组＋有限完整 caption 预制。** 同一动作覆盖近义词及口语变化，保留含义与文字差别；可审查、可离线缓存。
2. 一词一套：语义最直观，但 300 词至少 1,200 套动作，近义词重复成本高。
3. 任意长句现场改字：看似覆盖无限，但误判、手机首次编码延迟和缓存爆炸风险高，本阶段不采用。

## 分组结果与数量口径

- 原表保持不变：300 核心查询词、900 评审例句、30 查询覆盖批次。
- 新草案：**242 组，44 个多词组覆盖 102 词，198 个单词组**；每个原词恰好归入一组。
- 每组 4 个风格槽位，最低共享动作预算 **968**，对比逐词 1,200 少 **232（约 19.3%）**。不为凑减幅强合并。
- 目前收录 **28 条新增有限别名、328 条完整 caption 规划**。并非立即生成 328×4；首轮挑少量高频 caption，验收后扩展。900 例句不是自动变体白名单。
- 原有 20 词、140 张原创记录、52 张正式登记保持原样；有素材的组先评估无字母版复用，既有资产不删除、不重命名。动作预算不等于还要新画的数量，文字变体文件和 APK 字节也不会同比缩减。

## 代表性规则与非等价边界

| 查询 | 规划素材组 | 完整文字与限制 |
|---|---|---|
| 打你、扁你、揍你 | 友好玩笑打闹 | 共用软枕/轻拍等无伤害动作，各保留原完整 caption；仅明确友好玩笑语境 |
| 打你哦、打你啊、我打你呀 | 友好玩笑打闹 | 不是在旧 GIF 尾部多涂一个字，而是从无字母版重排整句 |
| 我要来抓你了 | 友好玩笑追赶 | 有限完整句式；没有友好追逐语境不自动判定 |
| 谢谢、谢谢啦、谢谢你 | 感谢致意 | 动作共用；请求“谢谢啦”成品不能误取“谢谢你”文件 |
| 明白、懂了 | 理解知悉 | 共用点头理解动作，文字分别保留 |
| 收到、同意 | 不合并 | 收到消息不代表同意其内容 |
| 开心、不开心 | 不合并 | 先否定保护，不以“开心”子串命中；不开心须另审低落映射 |
| 我不打你、我打你电话 | 不命中打闹 | 否定及打电话歧义保护 |
| 我打你、你打我 | 不直接共组 | 主客体不同；后者未在本轮别名白名单 |
| 救命、严重威胁句 | 不娱乐化处理 | 吐槽用法须有明确语境；真实求救不映射搞笑图 |

对于仅能共享手势而非严格同义的组（如“心累/疲惫”“新年快乐/春节快乐”），JSON notes 明确语境及 caption 差异。分组用于生产复用，不是通用自然语言理解，也不能绕过否定/指代/威胁保护。弱指代“你们/我们”继续遵循原草案，不强行编造情绪；不得读取宿主历史聊天推断。

## 四套共享动画及文字生产

每组规划：原创核心角色、原创动物、原创中国人物/生活场景、原创 3D/毛绒/黏土/抽象图形各一套。每套四个真实关键姿势，不做单图缩放；人物必须原创中国人，不模仿名人或知名 IP。

母版无文字，保存 master/poses 与动作参数。服务端将白名单中的**完整 caption**通过确定性布局渲染到每一帧，生成 240×240、10–20 帧、循环、低于 250KB 的 GIF 及首帧 WebP。文字溢出或无法保持可读性就阻止发布，不截断、不拼接已有嵌字 GIF；未知长句先受控返回基础图或无推荐，不触发无限生成。

已有固定文字成品保持不变；只有存在合格无字母版的资产可制作新变体。生成后仍需逐项审查动作、汉字、时长、字节、SHA 与版权，用户验收后发布。参考截图和搜狗缓存不是可发布资产来源；只能学习通用表现机制，原创重设计不能是细微改图。

## 本地与服务端身份边界

- `semanticGroupId`：选动作，不唯一标识成品文字。
- 成品渲染键：`assetVersion + assetId + fullCaption + layoutVersion`，结构化编码，避免拼接歧义。即使同一组、同一动作，caption 不同必须不同渲染身份。
- 查询索引有效期 **7 天（604800 秒）**；读取不续期，本地索引及所需文件有效时不请求推荐接口。过期可先显示旧图并后台刷新；迟到网络结果可落缓存，但不能覆盖已切换查询的界面。
- 同 SHA-256 的原件校验后复用，不能把同组所有文字共用同一个文件缓存键。索引有效但文件丢失/损坏时必须区分：可按已知 asset URL 补文件，不伪称已完整缓存，不重新搜索替代成品。
- APK 每个命中词仍展示本组四风格基础候选，重复 assetId 只打包一次；完整文字变体默认接口按需取回缓存，不承诺把全部变体打包。实际是否额外内置须逐批确认体积。
- 本文件仅约束后续改造，不声称现有缓存/接口已经使用这些键或已切换 `server/images`。

## 后续实现与验证顺序（本轮不执行）

### 任务 1：确认分组与有限句式

文件：`assets/expression/query/semantic-groups.draft.json`、`assets/expression/query/recommendation-cases.json`、`server/src/expression/queryMatching.test.ts`、Android `ExpressionQueryMatchingTest.kt`。

先审查分组；TDD 写打你/扁你/揍你、尾词、谢谢啦及否定/主客体/威胁反例，运行看到新能力失败，再最小修改两端匹配器，确保双方共用语义规格。先不要把 draft 文件直接载入生产。

### 任务 2：无字动作到有限 caption 预制

文件：`server/src/expression/expressionBatch.ts`、`server/src/expression/expressionBatch.test.ts`、相关 batch manifest（用户选定后再确定具体批次）。

先写同 master 不同完整 caption、字形溢出拒绝、首帧 WebP、GIF 帧数及 SHA 测试，看到失败后最小扩展生成器。只生成用户选定少量组和变体，审图后再登记，不重做已有合格动作。

### 任务 3：成品身份与缓存

文件：`server/src/expression/assetGenerator.ts`/对应测试、Android `ExpressionSync.kt`、`ExpressionCache.kt`/对应测试。

先写同组不同 caption 不串图、同 SHA 复用、7 天内不请求推荐、到期后台刷新、晚到不覆盖新查询及重启离线测试。失败后再调整实际接口/catalog 契约和缓存，保持预览与发送为同一 GIF。限量后再接 `server/images`，不把未验收图开放给接口。

### 任务 4：验收发布

服务端 `npm test && npm run build && npm run expression:prototype && npm run expression:generate`；根目录 `bash scripts/tests/expression-assets-test.sh`；Android 表情相关测试与 `:app:assembleOfflineDebug`。新图必须先视觉审查再统一 APK 接入，区分既有测试失败；无手机时不声称发送真机通过。每项实现后依次规格、质量审查与验证，保持 ko:ko，仅提交任务相关文件。

## 本轮数据校验

校验草案：原300词集合相等且各一次、groupId唯一、别名跨组零冲突、每组四种风格、所有 caption 为非空完整白名单、每个成员和别名都有对应 caption、原件来源 ID 与140条源 manifest一致、52条正式登记一致。报告：`artifacts/expression-library/semantic-groups-verification.json`。该报告只验证结构与来源，不代替语义人工审查，也不证明生产已实现。

## 完整分组表

| 稳定草案 ID | 素材意图 | 原300词成员 |
|---|---|---|
| intent-670d974354 | 你好 | 你好 |
| intent-951686aded | 早安 | 早安 |
| intent-7196ef2fbd | 晚安 | 晚安 |
| intent-c90a1cdb56 | 离开告别 | 再见、溜了、告辞 |
| intent-552594ef80 | 下午好 | 下午好 |
| intent-d8e9a7fa20 | 晚上好 | 晚上好 |
| intent-fceb19622c | 中午好 | 中午好 |
| intent-9fd8ddb6fc | 好久不见 | 好久不见 |
| intent-960d96a692 | 欢迎 | 欢迎 |
| intent-87537f4252 | 我来了 | 我来了 |
| intent-536edaf7cd | 肯定答应 | 好的、可以、没问题 |
| intent-e2c6751f82 | 收到 | 收到 |
| intent-8e37cd5f7f | 理解知悉 | 明白、懂了 |
| intent-de32e20193 | 知道了 | 知道了 |
| intent-905819e2e3 | 同意 | 同意 |
| intent-fac2a67ad8 | 确定 | 确定 |
| intent-0002e2d87f | 当然 | 当然 |
| intent-2e122a23d7 | 记住了 | 记住了 |
| intent-1bf7ac6885 | 感谢致意 | 谢谢、感谢支持、谢谢夸奖 |
| intent-73cc81388c | 请求谅解 | 对不起、抱歉 |
| intent-bd96bf364b | 辛苦了 | 辛苦了 |
| intent-2313dbbb68 | 不客气 | 不客气 |
| intent-e8b44a5169 | 没关系 | 没关系 |
| intent-f8e062dc45 | 麻烦你了 | 麻烦你了 |
| intent-14188c44b6 | 原谅我 | 原谅我 |
| intent-7a655569d7 | 大笑反应 | 哈哈、大笑、笑死 |
| intent-6e067999e2 | 开心欢呼 | 开心、好耶、乐开花 |
| intent-890cf29801 | 相信鼓励 | 加油、考试加油、你可以的 |
| intent-9ffacf656d | 表现赞赏 | 太棒了、厉害、优秀 |
| intent-f48293fdb1 | 恭喜 | 恭喜 |
| intent-aced5d7f9f | 点赞 | 点赞 |
| intent-8f694c7f4f | 期待 | 期待 |
| intent-d9c547699b | 难言无奈 | 无语、一言难尽 |
| intent-668ce09171 | 低落情绪 | 难过、失落、情绪低落 |
| intent-1080332a28 | 生气 | 生气 |
| intent-341533806f | 惊讶愣住 | 震惊、惊呆、愣住 |
| intent-ad353ac8ff | 委屈 | 委屈 |
| intent-b4ae3bd8b8 | 失望 | 失望 |
| intent-ab6085d2ab | 烦躁 | 烦躁 |
| intent-571a5f6206 | 害怕 | 害怕 |
| intent-313c224671 | 紧张 | 紧张 |
| intent-0b795c7fd6 | 尴尬 | 尴尬 |
| intent-53847fd903 | 亲近拥抱 | 抱抱、贴贴 |
| intent-174565a068 | 想你 | 想你 |
| intent-2db52f9416 | 爱意表达 | 爱你、比心 |
| intent-178b4da959 | 亲亲 | 亲亲 |
| intent-1e49d50414 | 摸摸头 | 摸摸头 |
| intent-72bda55a92 | 友好玩笑打闹 | 打闹 |
| intent-ab76051185 | 友好玩笑追赶 | 追赶 |
| intent-453fbb17b2 | 撒娇 | 撒娇 |
| intent-567a1cd8aa | 哄哄我 | 哄哄我 |
| intent-42e5dbf9de | 在吗 | 在吗 |
| intent-4f617edf45 | 请求宽限 | 等一下、再给点时间 |
| intent-f1aa0ef488 | 即将到达 | 马上来、快到了 |
| intent-8c03fbcb10 | 快点 | 快点 |
| intent-8c4f967187 | 暂缓回应 | 稍后回复、回头联系 |
| intent-e58d930c77 | 忙着呢 | 忙着呢 |
| intent-b6bcec4fea | 说来听听 | 说来听听 |
| intent-f380896d20 | 然后呢 | 然后呢 |
| intent-a632fb6f76 | 有空吗 | 有空吗 |
| intent-5ea0b37121 | 回个消息 | 回个消息 |
| intent-9ba359e542 | 能力不足 | 不行、做不到 |
| intent-77af2f337c | 不接受请求 | 不要、拒绝 |
| intent-ed5e76b48a | 温和谢绝 | 不了、不用了 |
| intent-6b75890295 | 算了 | 算了 |
| intent-00cbfcdd46 | 不方便 | 不方便 |
| intent-e77d55a4ca | 延后再议 | 下次吧、再说吧 |
| intent-0c04b3a5f1 | 不同意 | 不同意 |
| intent-603f2b1025 | 离谱 | 离谱 |
| intent-eb256defcc | 惊喜赞叹 | 绝了、好家伙 |
| intent-29b9062ff5 | 服了 | 服了 |
| intent-e8cbb0ba28 | 讽刺调侃 | 真有你的、不愧是你 |
| intent-3678de2c28 | 停止争闹 | 别闹、冷静 |
| intent-9fde47a68e | 救命 | 救命 |
| intent-2e46fcd26b | 又来了 | 又来了 |
| intent-48d556a5d8 | 听我解释 | 听我解释 |
| intent-b1c6ca920b | 你猜 | 你猜 |
| intent-6e70f66218 | 难以理解 | 搞不懂、不懂 |
| intent-15b61d749a | 旁观热闹 | 吃瓜、围观、围观群众、看热闹 |
| intent-9743d8f0a7 | 路过 | 路过 |
| intent-89ab26c310 | 破防 | 破防 |
| intent-8f5ec4e26d | 上头 | 上头 |
| intent-bd9bc18a4c | 拿捏 | 拿捏 |
| intent-7ad924a1c3 | 安排 | 安排 |
| intent-b8dceaf16c | 偷笑 | 偷笑 |
| intent-0567740eac | 憋笑 | 憋笑 |
| intent-e5bee77860 | 得意 | 得意 |
| intent-afc4f23aac | 会心欣喜 | 感动、欣慰 |
| intent-0eacd29905 | 惊喜 | 惊喜 |
| intent-4e8156e47e | 满足 | 满足 |
| intent-30724f3f0c | 激动 | 激动 |
| intent-c4ef574cf2 | 悲伤落泪 | 哭哭、心碎 |
| intent-5461fc14fc | 孤单 | 孤单 |
| intent-cbef484a5f | 想家 | 想家 |
| intent-d64c054950 | 精神耗竭 | 心累、疲惫 |
| intent-2061833475 | 情绪失控 | 崩溃、裂开 |
| intent-a59433c3c4 | 郁闷 | 郁闷 |
| intent-8bbc187b2c | 不想说话 | 不想说话 |
| intent-a3774e862c | 疑惑 | 疑惑 |
| intent-71350536e0 | 怀疑事实 | 真的假的、不敢相信 |
| intent-1b919bb478 | 为什么 | 为什么 |
| intent-49ffe32267 | 怎么回事 | 怎么回事 |
| intent-0fec78517d | 好奇 | 好奇 |
| intent-e155feffd2 | 意外 | 意外 |
| intent-9b0b52d472 | 别难过 | 别难过 |
| intent-8e447a56e0 | 别生气 | 别生气 |
| intent-4ab098e7a8 | 安心放慢 | 慢慢来、不着急、别急 |
| intent-1b5b4b4283 | 会好的 | 会好的 |
| intent-25c42fed08 | 我陪你 | 我陪你 |
| intent-297d70a39e | 支持你 | 支持你 |
| intent-b55115ea66 | 相信你 | 相信你 |
| intent-3b46a38525 | 辛苦自己 | 辛苦自己 |
| intent-a318068037 | 吃饭了 | 吃饭了 |
| intent-fb5099cf24 | 饿了 | 饿了 |
| intent-544a92f48d | 吃饱了 | 吃饱了 |
| intent-0148aa8ffb | 好吃 | 好吃 |
| intent-b10b8dbd74 | 想吃 | 想吃 |
| intent-a8720cdfc7 | 喝水 | 喝水 |
| intent-cb79e0cecd | 喝茶 | 喝茶 |
| intent-bca9f5c6ac | 喝咖啡 | 喝咖啡 |
| intent-d4507ecfd4 | 点外卖 | 点外卖 |
| intent-819cda41d1 | 吃夜宵 | 吃夜宵 |
| intent-62d4c42db6 | 困了 | 困了 |
| intent-a069a9e56d | 睡觉了 | 睡觉了 |
| intent-dff99bde96 | 起床了 | 起床了 |
| intent-f7146472af | 赖床 | 赖床 |
| intent-cf2d322fe9 | 睡不着 | 睡不着 |
| intent-38d457958b | 刚睡醒 | 刚睡醒 |
| intent-df4fb745c4 | 午休 | 午休 |
| intent-a309894974 | 洗澡去 | 洗澡去 |
| intent-b4ffef7017 | 刷牙 | 刷牙 |
| intent-cb07efc3e5 | 收拾房间 | 收拾房间 |
| intent-3ca65b53ae | 上班了 | 上班了 |
| intent-49b735b228 | 下班了 | 下班了 |
| intent-a270e67b50 | 加班 | 加班 |
| intent-b406359aa5 | 开会 | 开会 |
| intent-554abe09ef | 摸鱼 | 摸鱼 |
| intent-5704fe713b | 赶工 | 赶工 |
| intent-e6d471687c | 交作业 | 交作业 |
| intent-8602ef1b43 | 学习中 | 学习中 |
| intent-4203e358de | 完工庆祝 | 完成了、顺利完成 |
| intent-235f6c2af9 | 进度如何 | 进度如何 |
| intent-4dbd13ff3a | 还在处理 | 还在处理 |
| intent-1da0b2739e | 马上完成 | 马上完成 |
| intent-f5b9b95784 | 确认一下 | 确认一下 |
| intent-186e9edbbf | 请查收 | 请查收 |
| intent-60823aaec7 | 已发送 | 已发送 |
| intent-d3ccdf49ed | 重新发一下 | 重新发一下 |
| intent-aeee008a15 | 没看到 | 没看到 |
| intent-20bef866f7 | 看到了 | 看到了 |
| intent-31aaa1f369 | 出门了 | 出门了 |
| intent-d07feaef43 | 到家了 | 到家了 |
| intent-287b4a93b3 | 在路上 | 在路上 |
| intent-e6f764587e | 到了 | 到了 |
| intent-00fe848d37 | 堵车 | 堵车 |
| intent-0f15b0178a | 迟到了 | 迟到了 |
| intent-d9a9eb2bb5 | 迷路了 | 迷路了 |
| intent-23473c5877 | 等车 | 等车 |
| intent-cb296f6390 | 旅途平安 | 注意安全、一路平安、一路顺风 |
| intent-76182ecb2b | 下雨了 | 下雨了 |
| intent-eb77e95030 | 好热 | 好热 |
| intent-68cd021c04 | 好冷 | 好冷 |
| intent-d16b8ae721 | 带伞 | 带伞 |
| intent-c4c468d280 | 多穿点 | 多穿点 |
| intent-c9200ad6e8 | 感冒了 | 感冒了 |
| intent-bc91c3832c | 不舒服 | 不舒服 |
| intent-ea147afd20 | 早点休息 | 早点休息 |
| intent-5a3341b329 | 保重 | 保重 |
| intent-53dd881fd3 | 恢复啦 | 恢复啦 |
| intent-f5fd638d58 | 相约活动 | 一起玩、约起来 |
| intent-a8ab008348 | 款待邀约 | 请你吃饭、我请客 |
| intent-792c11bc85 | 轮到你了 | 轮到你了 |
| intent-35ef809bd9 | 算我一个 | 算我一个 |
| intent-5ce8b05ab0 | 带带我 | 带带我 |
| intent-dd6fbf763c | 生日快乐 | 生日快乐 |
| intent-f852679556 | 假日祝福 | 周末快乐、假期快乐 |
| intent-d00efc95d0 | 新春祝福 | 新年快乐、春节快乐 |
| intent-0f7f2c3e69 | 元宵快乐 | 元宵快乐 |
| intent-d2ef80500a | 端午安康 | 端午安康 |
| intent-6002826a62 | 中秋快乐 | 中秋快乐 |
| intent-b9d6992172 | 国庆快乐 | 国庆快乐 |
| intent-b528d1b500 | 节日快乐 | 节日快乐 |
| intent-64a023e54a | 开工大吉 | 开工大吉 |
| intent-a71b472969 | 祝你好运 | 祝你好运 |
| intent-d29a481772 | 躺平 | 躺平 |
| intent-44ce03c73a | 摆烂 | 摆烂 |
| intent-dc22f4134b | 麻了 | 麻了 |
| intent-c7d5119a60 | 打扰了 | 打扰了 |
| intent-eb36e0c282 | 摊手 | 摊手 |
| intent-d9aa747393 | 扶额 | 扶额 |
| intent-f0b130ca36 | 捂脸 | 捂脸 |
| intent-f9db0ae1c9 | 翻白眼 | 翻白眼 |
| intent-28808c413d | 假装没看见 | 假装没看见 |
| intent-725d0d7f2f | 无事发生 | 无事发生 |
| intent-6b58198955 | 撤回 | 撤回 |
| intent-573b634453 | 羞涩脸红 | 害羞、脸红 |
| intent-3346bd209f | 心动 | 心动 |
| intent-f98bd969da | 等你 | 等你 |
| intent-3d9b480dfd | 陪陪我 | 陪陪我 |
| intent-e1cfb22d3e | 舍不得 | 舍不得 |
| intent-ed8990cb05 | 牵手 | 牵手 |
| intent-7d818eb6cc | 和好吧 | 和好吧 |
| intent-a58ca07f0e | 不许走 | 不许走 |
| intent-24c8fca13f | 看电影 | 看电影 |
| intent-d46a9603ce | 追剧 | 追剧 |
| intent-f18ab4d297 | 听歌 | 听歌 |
| intent-15809dbe28 | 玩游戏 | 玩游戏 |
| intent-a9d130d795 | 运动 | 运动 |
| intent-d4e25a7e38 | 散步 | 散步 |
| intent-a12693d422 | 逛街 | 逛街 |
| intent-9f805978dd | 看书 | 看书 |
| intent-facfd1afaa | 撸猫 | 撸猫 |
| intent-4bd55aeba6 | 遛狗 | 遛狗 |
| intent-9bc51fc443 | 手机没电 | 手机没电 |
| intent-fa460fc845 | 信号不好 | 信号不好 |
| intent-e54714deeb | 断网了 | 断网了 |
| intent-e5676cc9bf | 卡住了 | 卡住了 |
| intent-eb1c154f83 | 听不清 | 听不清 |
| intent-a81243c902 | 发错了 | 发错了 |
| intent-4efc044bbb | 打错字 | 打错字 |
| intent-c5c77ee027 | 忘记了 | 忘记了 |
| intent-f6259612fe | 找到了 | 找到了 |
| intent-8d71eb661b | 找不到 | 找不到 |
| intent-a8491b5272 | 真可爱 | 真可爱 |
| intent-f2aedcbd1c | 好看 | 好看 |
| intent-d84faeec33 | 真聪明 | 真聪明 |
| intent-06e7800c89 | 赞叹认同 | 有道理、说得好 |
| intent-50ba5b142f | 靠谱 | 靠谱 |
| intent-ddd8fad55f | 钦佩能力 | 佩服、专业 |
| intent-f0e5cc1c20 | 学到了 | 学到了 |
| intent-04c129bcf0 | 再作考虑 | 考虑一下、再想想 |
| intent-7c33daf90f | 商量一下 | 商量一下 |
| intent-9846dbbd39 | 暂不确定 | 看情况、不一定 |
| intent-6533f1f894 | 有可能 | 有可能 |
| intent-fba668fc9e | 接受安排 | 听你的、都可以、没意见 |
| intent-94aff660cf | 定好了 | 定好了 |
| intent-110fcdaa26 | 改时间 | 改时间 |
| intent-267aca5ea0 | 取消了 | 取消了 |
| intent-384bb71f6d | 提前说 | 提前说 |
| intent-2ec25db270 | 记得提醒我 | 记得提醒我 |
| intent-084e365d5e | 别忘了 | 别忘了 |
| intent-8c9212704e | 明天见 | 明天见 |

## 独立审查补充的实现验收项

- “追你”存在追赶游戏/恋爱追求两种含义。至少覆盖“我要来抓你了”与“我想追求你”反例，不能仅凭同一个“追”字共用动作。
- 否定保护按具体意图处理，不能用全局否定词过滤器挡掉“不懂、不要、不可以”等本身合法的核心意图。
- 每组明确四个基础动画assetId，文字变体另用完整渲染身份关联；“打你哦”和“打你啊”可以共享无字动作，但不能用同一最终文件或查询缓存值冒充两个不同文字版本。

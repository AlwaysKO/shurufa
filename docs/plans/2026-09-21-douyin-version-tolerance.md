# 抖音跨版本识别与截图 Git 边界实现计划

> **For Claude：** 使用 superpowers:executing-plans 按任务实施，先失败测试再最小实现。

**目标：** 保留原抖音 40.5.0 路径，增加不依赖混淆 ID 的保守聊天识别；截图只留本地，验证后提交本轮代码。

**架构：** 精确匹配优先；失败后以同一页面中的返回、聊天设置、标题、消息列表、唯一编辑框及相对位置组合识别。不能只凭输入框或通用“更多”采集，不确定时拒绝。沿用公共采集、去重和上传链路，不动定位。记录有限枚举诊断，不记录标题、正文、节点树，在手机设置中直接查看。

**技术栈：** Kotlin、JUnit、Robolectric、Gradle、Git。

## 来源与边界
- 用户 2026-09-21 确认方案，明确截图不提交、加入忽略，完成后提交代码。
- 项目 AGENTS.md：统一采集规则、非聊天排除、保留已有改动、原签名与 E 盘 APK 交付。
- 合成树测试只证明识别规则，不代表未经取证的抖音版本或荣耀 X80 已真机验收。

## 任务 1：截图忽略
- 修改 `.gitignore`，添加 `/server/uploads/chat/`；135 个现有跟踪截图只从索引移除，核对文件仍存在且 SHA256 未变。不改历史、不删本地、不忽略运行资源或正式测试。
- 在 AGENTS.md 记录用户明确的本项目截图边界。
- 检查：`git ls-files server/uploads/chat` 无输出；`git check-ignore` 命中；提交前 `git ls-files -ci --exclude-standard` 无输出。

## 任务 2：识别回归和实现
- 修改 `android/YuyanIme/yuyansdk/src/test/java/com/yuyan/imemodule/data/capture/adapter/DouyinChatAdapterTest.kt`。
- 新测试：ID 全部改变、不同分辨率/额外容器、键盘收起、聊天标题歧义、两层会话、评论/直播/搜索/消息列表、正文含页面关键词不误拦、同会话输入变化与会话切换。
- 先运行 `source /home/ko/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*DouyinChatAdapterTest'`，确认兼容正例失败。
- 修改同路径 main 下 `DouyinChatAdapter.kt`，保留旧路径，增加结构识别和有限诊断码；公共采集不新增 App 特例。
- 重跑同一测试，要求全绿。

## 任务 3：本地无正文诊断
- 新增 `data/capture/adapter/DouyinCaptureDiagnostics.kt` 及 Robolectric 测试，先验证缺失行为失败，再实现固定容量最后状态及时间存储。
- `service/capture/PassiveChatAccessibilityService.kt` 仅在现有解析处记录诊断；`ui/fragment/OtherSettingsFragment.kt` 增加本地查看入口。显示识别成功不等于上传成功；无事件不猜测是版本问题。
- 同状态限频写入，设置页不显示或导出任何正文、标题、截图、设备标识。

## 任务 4：回归、交付、提交
- `./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*data.capture.*' --tests '*service.capture.*'`。
- `./gradlew :app:assembleOfflineDebug`；`tools/verify-delivery-apk.py` 核对原签名、非 testOnly、真实版本、源与 E 盘副本哈希。
- 审查本次 diff，已有未提交改动仅保留，重叠文件按本次补丁暂存，不使用 `git add .`。
- `git diff --cached --stat`、`git ls-files -ci --exclude-standard`、`git count-objects -vH`、`git diff --check`；提交中文消息，不 push。
- 报告提交 ID、推荐 APK 路径、验证范围、未完成真机验收及历史截图仍存在于旧提交的限制。

## 实施与审查记录
- 135 张已跟踪聊天 WebP 仅移出 Git 索引，本地文件逐一复核 SHA256 未变；`/server/uploads/chat/` 忽略规则覆盖后续运行截图。旧提交中的历史文件未清理，不自动重写历史或强推。
- 保留 40.5.0 精确匹配；结构路径支持控件 ID 更名/缺失、不同分辨率、额外容器及 ListView/RecyclerView。明确聊天设置，或“更多 + 同行明确语音切换”提供聊天语义；无法确认的页面仍拒绝，不用 msg_et 单独推断聊天。
- 首批兼容测试 13 项中 5 项预期失败，实现后全绿。代码审查发现跨层拼接风险，追加两轮失败回归：后台整个工具栏/列表、后台单独标题/设置/列表、后台语音证据。最终所有结构证据共用输入所在页面的分支边界，审查未发现剩余阻断项。
- 新诊断先在空实现下复现 2 项失败，再实现最后枚举状态/时间存储、同状态 60 秒限频、同意开关约束。入口为「设置 → 其他 → 抖音识别诊断」，无正文/标题/节点树/截图，不上传诊断；“识别成功”不等于截图或上传成功。
- 正式测试使用合成树与原有脱敏夹具。原夹具语音按钮语义已被脱敏，不能据此伪造所有 ID 变更后的真机成功证据。
- 原有未提交的微信标题/OCR、后台会话等改动保留，重叠服务文件仅暂存本轮诊断接入；测试和 APK 从当前工作区构建，包含那些已有改动，不冒充纯净提交单独构建。
- 不改定位、不操作手机、不自动发送聊天、不 push。荣耀 X80 与未知版本抖音仍待用户真机确认。

## 最终验证
- `:yuyansdk:testOfflineDebugUnitTest --tests '*data.capture.*' --tests '*service.capture.*'`：36 个测试类、232 项，失败/错误/跳过均为 0。其中抖音适配 18 项、本地诊断 4 项。日志：本地 `artifacts/diagnostics/2026-09-21-douyin/regression-final.log`。原有损坏图片测试会打印解码失败信息，JUnit 结果无失败。
- `:app:assembleOfflineDebug` 成功，打包版本 `20260921.21 / 2026092121`；构建脚本验证 API 23/28/36 原证书并复制到约定 E 盘目录。
- 推荐 APK：`E:\Projects\shurufa-android\apk\shurufa-2026-09-21-v20260921.21-2026092121-debug-cd16de7e.apk`；包名 `com.yuyan.pinyin.offline.debug`，minSdk 23，ARM64。`verify-delivery-apk.py` 独立验证 API 23/27/28/32/36 原证书及非 testOnly 通过。
- WSL `app/build/unix/outputs/apk/offline/debug` 源 APK 与 E 盘副本 SHA256 均为 `cd16de7e38b8939a18a4929c3653683db70480fe1a33aa9fda05993a6b8d4e54`；详见本地 `apk-check.log`。首次核对脚本因匹配到历史审计输出而主动停止，随后明确使用本轮 Unix 输出目录重新核对通过，未选用旧包。
- 提交前忽略检查 `git ls-files -ci --exclude-standard` 无输出；截图 SHA256 保留检查通过；`git diff --check` 和暂存检查通过。仓库对象目前 43.50 MiB loose / 138.57 MiB packed，移出索引不代表历史体积已缩小。

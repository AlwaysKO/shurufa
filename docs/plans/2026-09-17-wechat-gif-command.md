# 微信GIF专用命令交付修复

## 范围与事实

2026-09-17 用户要求先整理暂停采集修改，优先修GIF发送。采集待办见统一采集规则文档；本批不改采集/OCR。微信、QQ、抖音不能共用未经验证的交付方式；本批先修微信已复现的静态化，QQ/抖音发送后续独立适配。

已知：16帧GIF原件与FileProvider字节一致，微信通用commitContent返回true，实际保存静态单帧。历史commitText(uri)会泄露URI到正文，不恢复该方案、不恢复.0后缀变换。默认兼容IME组件仍是本应用自身的既有组件，不修改包名/签名/AppID。

## 本机版本接收端证据（仅本地保存原始分析）

从已安装微信8.0.78读取APK期间设备断开，APK整体不完整，不能声称完整APK校验通过；已从完整ZIP条目中恢复并逐项CRC/长度校验17个DEX，足够核对相关接收类。原始APK/DEX/反编译输出仅在Git忽略的 `.runtime/multi-app-chat/`，不纳入仓库。

- MMEditText编辑器声明 `SUPPORT_SOGOU_EXPRESSION=1`；onPrivateIMECommand委托到tt5.i。
- 接收命令字符串 `com.sogou.inputmethod.exp.commit`，Bundle参数 `EXP_PATH_URI` 为Uri，直接交给当前会话的输入图片处理；不是commitText写正文。
- 当前会话处理通过原文件头识别GIF；只有既有兼容IME完整组件字符串符合该版本判断，才进入WXEmojiObject分支，否则可能走普通图片。当前本应用选中组件满足已存在兼容入口，但仍需运行时严格核验，不能泛化所有版本。
- 接收端出现当前聊天内的确认流程，用户确认后再交付动图；不自动确认、不选联系人。此静态分析是试验依据，不等于手机GIF保真已验收。

## 最小实现与门控

1. 仅微信 + image/gif进入专用分支；只放行已核对版本8.0.78、编辑器能力声明、默认IME精确为本应用既有兼容组件。其他微信版本不猜测能力。
2. 原GIF保持.gif与image/gif，不转码、不改后缀；校验GIF文件头，授予com.tencent.mm该单一URI的临时只读权限，将Uri放入命令Bundle。绝不调用commitText(uri)、发系统分享Intent或自动点击发送。
3. 微信GIF不再回退到已实测静态化的标准commitContent。未知/拒绝明确返回不支持或失败，既有失败保留输入行为不改。其他App、静态图保留原协议。
4. 复用WechatSubmitted表达“请求已交接”，不返回Sent、不清原输入/推荐卡片。Android协议是异步的，true不能证明接收端处理或发送完成，依据[Android InputConnection文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection#performPrivateCommand(java.lang.String,%20android.os.Bundle))。
5. 测试先红后绿：专用命令与参数、真实Provider字节/MIME、精确授权、绝无URI正文/标准GIF回退/分享、能力/版本/IME缺失拒绝、命令拒绝与异常、非微信及静态图回归；AI合成GIF共用相同sender，不区分来源降低格式。
6. 独立产物构建，保留并行文字识别改动。设备断连期间不安装、不宣称完成；重新连接后用户在文件传输助手对同一原GIF和AI合成GIF分别确认，保存接收件核验帧数。若失败，不继续盲目尝试其他协议或自动重发。

## 实施进展

- 新增专用命令测试6项，原sender下4项明确断言失败：预期交接/拒绝却仍调用已知静态化标准接口。实现专用命令后，sender/controller/renderer联合首轮通过。旧的标准GIF字节/MIME/授权断言移到非微信目标继续保留，不删除质量检查。
- 原始/合成GIF均按PreparedExpression的image/gif进入同一分支；合成renderer仍逐帧输出，未修改或重绘素材。
- 独立审查发现撤权三参数API从26提供；新增SDK23/25路由边界先失败，再增加API>=26门控，低版本不授权/不发命令/不回退静态化。边界测试是在当前Robolectric沙箱修改SDK_INT，不能称旧版系统真机验证。审查复核门控顺序通过。
- 最终联合回归包含新命令、标准sender、flow/controller、GIF渲染及InputView准备/交接保留输入用例，并构建APK，仍进行中。
- 手机在读取安装包时断连；尚未安装本轮包，未执行新的实际发送。当前路线待用户确认弹框、实际GIF接收与保存帧数，不能把静态接收端分析/单测通过当作保真完成。

## 最终代码阶段验证

- 定向回归41项，0失败/错误/跳过；包含7项专用命令、标准sender/controller、逐帧GIF渲染和InputView准备/交接保留输入。提示语变更造成的2项旧文案断言已同步为更准确的交接提示，保留卡片/文字断言未删。
- APK构建成功，版本20260917.18，SHA-256 `8ff7b34ca16b54d741eff8804eceef31c7f2c536381c07dccc37685884d6f2ad`。产物 `android/YuyanIme/app/build/capture-unified-check/outputs/apk/offline/debug/yuyanIme_2026091718_debug.apk`，隔离构建日志/验证汇总位于忽略的.runtime目录。
- 独立审查的API等级问题已修，复核无新增重要问题。git diff --check通过。没有安装、提交、发布或自动发送；当前仅微信该版本专用分支代码阶段完成，QQ/抖音保真发送未修复，微信原GIF及AI合成GIF的真机验收仍待连接设备。

- 补充单独运行实际命名的ExpressionFlowTest：14项通过，0失败/错误/跳过。前述41项不含此套件，合计55项（分两次执行）；初始通配名ExpressionFlowControllerTest未命中真实套件，现已补齐，不以命令参数冒充执行证据。

### AI 合成发送链路补充验证

用户断连前再次强调 AI 合成也必须发送 GIF。新增 `aiComposedGifReachesWechatAsTheActualAnimatedOutput`：真实内置模板与文字合成 → 真实 `ExpressionContentSender` → 微信命令中的真实 FileProvider URI。断言合成文件不是源模板、交付字节等于合成产物、MIME=image/gif、帧数与源相同且大于1。微信命令类共8项于本轮通过（`.runtime/multi-app-chat/ai-gif-command.log`），未改变渲染算法。现有 renderer 测试另行覆盖帧延迟和文字像素。

该结果只证明本应用端保真，不证明微信确认界面或接收消息已动态播放。用户最新授权恢复三App采集离线开发，真机仍待用户操作验收。

### 分App边界（断连开发阶段）

| 目标 | 原始GIF / AI合成GIF共用发送路径 | 状态 |
|---|---|---|
| 微信8.0.78 + 已核对编辑器能力/兼容IME | 原字节FileProvider→专用命令；不回退通用静态化路径 | 本应用交付单测通过，宿主确认/最终动画未真机验证 |
| 其他微信版本/未声明能力 | 明确不支持，保留输入与卡片 | 不猜协议、不冒充已发送 |
| QQ、抖音 | 保留原标准MIME协商，仅目标声明支持时交付真实GIF | 此前现场未声明可接收MIME，专用保真路径仍未完成；不擅自改分享/相册路线 |

本轮没有把普通静态素材或emoji组合强行转GIF；“AI合成保持GIF”指原底图为GIF的模板合成。

本轮最终合并采集改动后再次运行：微信GIF命令8项、Renderer4项、完整InputView64项均通过（计入129项最新定向回归）。输入准备阶段的旧“先清空”断言已按现行用户要求纠正；准备/失败/微信仅交接时保留卡片的回归仍保留。最新本地APK SHA256=`78166fdcfa5731b6957d629a34703d12a8fda58ab0e0c6a160d653cd01836894`，未安装、未证明宿主最终动画。

# 荣耀200候选与拼音状态错配调查

## 用户证据与范围

用户2026-09-30提供截图：输入框已有“我是说”，尝试输入“那个”，拼音行显示 `mai'e`，可见候选“也、让你、不了、过、话、到”。用户称昨天异常、现在恢复，不记得之后是否装包。截图仅用于定位，不写入Git，不保存完整聊天。

本轮只读调查及本机诊断，无生产代码修改、无安装/发送/清词库/重启手机操作，未查询生产数据库或复制手机聊天数据库。

## 手机只读核对

Windows ADB确认唯一连接设备型号 ELI-AN00（荣耀200）。当前默认输入法为项目debug包，versionName=20260929.18，versionCode=2026092918；包管理器 lastUpdateTime=2026-09-29 20:27:53。

若用户所说昨天22:03即截图拍摄时点，安装更新时间早于截图，不能以“截图后已装新包”解释恢复。没有从图片单独确认拍摄日期，未确定期间是否重启过进程。

## 已执行本地复现

临时程序只在 `/tmp/shurufa-0930-candidate-analysis/`，编译当前原文 T9PinYinUtils、T9Spelling、T9Lexicon、InputSpellingMatch，读取现有公共资产，不读取手机个人词库：

- `displayPendingDigits("6243")` → `mai'e`。
- `fullDisplayComposition("6243", "", emptyList())` → `mai'e`。
- `fullDisplayComposition("6243", "na'ge", listOf("na ge"))` → `na'ge`。
- 公共词库 `query("6243")` 前项：那个/na ge、哪个/na ge、买的/mai de；“那个”不是缺词。

这证明截图读音可以由“6243仍在但候选读音丢失”的真实显示路径产生，不代表已取得截图时的逐键日志。

## 当前代码中的确定缺口

1. `keyboard/InputView.kt:onUpdateSelection`：中文联想开启且光标起点变化时，直接 `getAssociateWord(textBeforeCursor)`；没有检查当前引擎是否正在组合下一词。
2. `service/DecodingInfo.kt:getAssociateWord`：将 `isAssociate=true`，调用引擎联想。
3. `inputmethod/RimeEngine.kt:predictAssociationWords`：清除 `personalCandidates`、`nativeCandidateMetadata`，用联想替换 `showCandidates` 并清空 `showComposition`，但不清除 `keyRecordStack`。
4. `getT9CompositionForDisplay` 仍以栈里的未锁定数字码生成显示；缺少候选读音时进入等价拼读回退，6243恰好显示mai'e。

因此，上一次正文上屏的宿主光标通知若迟到、撞上下一词输入，可以产生“旧正文联想+新按键回退拼音”的混合状态。截图候选外观与此一致，但尚未实机回放宿主通知时序，未独立核对当时完整联想来源或候选点击索引，不能声称已100%还原昨晚操作。

## 建议的下一步（尚未实施）

- 在正在组合时阻止迟到的正文联想覆盖，同时保留正常上屏后的联想；不能只隐藏拼音行、给“那个”加权或清理词库。
- 回归应包含：上屏→立即输入6243→迟到光标回调；既有拼音/候选/原生选择索引必须保持一致。对照空组合时正常联想、主动选区变化、删除与目标切换。
- 验证效果须区分单测、独立回放和用户真机体验。目前临时恢复不代表上述代码缺口消失。

## 继续后的第一批修复

用户要求继续后，已在 InputView、DecodingInfo 与 RimeEngine 增加活跃组合态保护，避免迟到选择回调/联想入口清除当前按键对应的候选与读音元数据。真实键栈、展示候选和 nativeIndex 回归先失败后通过，包含于最终 690 项关联验证。交付记录见 `2026-09-30-input-priority-fixes.md`。未安装新包、未完成荣耀 200 实机复现与验收；此修复不反向证明昨晚的完整事件链。

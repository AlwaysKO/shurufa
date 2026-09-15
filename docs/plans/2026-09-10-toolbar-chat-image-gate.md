# 工具栏缩放与聊天图片推荐门控

目标：工具图标统一缩小约 15%，保留点击槽位；仅向具有键盘图片接收能力的聊天输入框提供推荐图。

依据：KeyboardToolbarMetrics 集中控制工具图标；ChatEditorGate 原先仅判断包名和多行/SEND，误接收评论框。现有 YuyanEmojiCompat 已使用微信 extras.IS_CHAT_EDITOR 识别真实聊天框；ExpressionContentSender 只通过真实 MIME 协商发送。

步骤：
1. 测试先行：统一 ICON_SCALE=0.85，1080 参考宽度图标从 92 缩到 78，行高限制分支也同比缩小。
2. ChatEditorGate 保留包名/文本/密码搜索过滤，微信须 IS_CHAT_EDITOR=true，所有目标还须支持项目图片格式的 MIME；未知能力保守不显示。
3. ExpressionInputTargetTracker 纳入 MIME 和聊天标记，能力变化时取消并清理旧推荐。
4. 更新真实聊天测试夹具；验证从聊天切至不支持图片目标不显示旧推荐。
5. 运行相关 JVM 测试与 Debug 构建、diff 检查；打开 KeyboardToolbarModel.kt 缩放行供用户调整。保留其他任务未提交修改，不自动提交。

边界：声明 MIME 不保证宿主实际接受每张图片；不声明键盘图片能力的微信聊天框也不会显示推荐，避免展示只能另存相册的推荐。没有连接手机则真机验收待完成。

## 验证记录
- 修改前，图标缩放/图片能力/微信评论/编辑器能力变化四项回归按预期失败。
- 大范围 InputView 测试在默认 512 MB 测试堆出现 OutOfMemoryError；临时提高堆内存的整组重跑被终止（退出码 143），未声称整组通过。
- 最终使用 /tmp 中临时 Gradle init 脚本（1536 MB、每类独立进程）验证门控、目标跟踪、推荐状态、图标尺寸、菜单适配及两个相关 InputView 生命周期测试，全部通过。Debug APK 构建成功。
- 独立代码审查无阻断问题；git diff --check 通过。
- 已用 VS Code --goto 打开 KeyboardToolbarModel.kt:48；ICON_SCALE=0.85f，用户修改后须重新构建安装。
- 无真机连接，实际微信聊天与评论待验收；本地 SDK 配置已恢复，未自动提交。

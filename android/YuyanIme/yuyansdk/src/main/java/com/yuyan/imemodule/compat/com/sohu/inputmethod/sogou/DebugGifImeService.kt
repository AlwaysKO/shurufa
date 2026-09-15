package com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou

import com.yuyan.imemodule.service.ImeService

/**
 * 已实机验证的微信 GIF 兼容入口。它仍是雨燕输入法，复用原服务实现。
 * 保留历史组件名以维持 Android 的已启用/已选择状态；Debug 仅是内部旧命名。
 * 微信当前版本按完整组件字符串分流，勿随意重命名；更新微信后需复测。
 */
class DebugGifImeService : ImeService()
